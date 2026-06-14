# SubFeed × Chromium — 임베디드 광고제거 재생 설계

작성일: 2026-06-14
상태: 설계 (구현 시작 전). codex 자문 종합 + 실무 함정 보강.
대상 독자: Chromium-for-Android 빌드/수정 경험자.

---

## 0. 목적과 전제

**목적**: SubFeed의 핵심(채널 RSS로 오늘 영상 리스트 → 광고 없이 재생)을, NewPipe/yt-dlp 추출 전쟁을 끝내고 **진짜 Chromium 웹 엔진**으로 재생해서 구현한다.

**확정 사항(잠금)**
- 호스트 앱 = **SubFeed가 주인**. Chromium은 재생/엔진 컴포넌트로 *임베드*된다 (반대 아님).
- **시청 위치 동기화는 이번 범위 제외.**
- 피드 UI(Compose)는 네이티브 그대로. **재생 화면만** Chromium 렌더 서피스로 바뀐다.

**비목표(이번엔 안 함)**: 위치 동기화, 자막/챕터 커스텀 UI, 댓글/추천/검색, 로그인 기반 기능.

**핵심 전환**: "스트림 URL을 우리가 뽑는다"(NewPipe/yt-dlp/NAS) → **"진짜 브라우저가 재생하고, 우리는 광고만 제거한다."** 실패 모드가 *"아무것도 재생 안 됨"*에서 *"광고가 한 번 나옴"*으로 완화되는 게 본질적 이득.

---

## 1. 전체 아키텍처

```
┌──────────────────────── SubFeed (Android 앱, 주인) ────────────────────────┐
│                                                                            │
│   네이티브(Kotlin/Compose) — 기존 그대로                                    │
│   ┌──────────────┐   ┌───────────────┐   ┌────────────────────────────┐   │
│   │ FeedScreen   │   │ SettingsScreen │   │ PlayerHost (신규)           │   │
│   │ RSS 피드     │──▶│ (그대로)       │   │  = subfeed_engine AAR 뷰    │   │
│   └──────────────┘   └───────────────┘   └────────────┬───────────────┘   │
│        ▲ 영상 탭                                        │ loadVideoId(id)   │
│        │                                                ▼                   │
│   ┌────┴───────────────────────────────────────────────────────────────┐  │
│   │  subfeed_engine.aar  ← Chromium 빌드 산출물 (//subfeed_engine)       │  │
│   │  ┌──────────────────────────────────────────────────────────────┐  │  │
│   │  │ Java 공개 API: SubfeedChromium.init / SubfeedPlayerView /     │  │  │
│   │  │                loadVideoId / 생명주기 / fullscreen 콜백        │  │  │
│   │  ├──────────────────────────────────────────────────────────────┤  │  │
│   │  │ content/ WebContents (멀티프로세스 Blink + 미디어 파이프라인)  │  │  │
│   │  │   └ youtube.com/embed/{id} 로드 → 실제 YouTube 플레이어 재생   │  │  │
│   │  ├──────────────────────────────────────────────────────────────┤  │  │
│   │  │ 광고 제거 모듈 (C++ 네트워크 훅)                               │  │  │
│   │  │   - 요청 차단(URLLoaderThrottle): ad ping/telemetry          │  │  │
│   │  │   - 응답 재작성(URLLoaderFactory wrapper):                    │  │  │
│   │  │       youtubei/v1/player JSON 의 adPlacements/adSlots… 제거   │  │  │
│   │  └──────────────────────────────────────────────────────────────┘  │  │
│   └─────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘

영상 데이터는 WebContents 안에서 googlevideo ↔ Chromium 미디어 파이프라인으로 직접 흐름.
(ExoPlayer/NewPipe/yt-dlp/NAS 는 이 모드에서 안 씀.)
```

---

## 2. 임베딩 방식 — content/shell 스타일 커스텀 임베더

**결정: (a) `content/` 직접 임베드** (content_shell 패턴). `android_webview` 아님, `chrome/` 리스킨 아님.

- `content/` = Chrome 제품 기능(sync/확장/autofill) 없는 **웹 엔진 코어**. `content_shell`이 그 위의 샘플 임베더. 우리가 필요한 건 딱 이 레이어.
- `android_webview`는 "시스템 WebView 대체"용 아키텍처라, "내 앱에 사설 Chromium 하나 넣기"엔 부적합(프로바이더/프레임워크 제약).
- `chrome/` 리스킨은 호스트 소유권 위배 + 제품 가정 제거에 리베이스 예산 낭비.

### 빌드 통합 모델 (중요)

```
Chromium 트리                                    SubFeed (일반 Gradle 앱)
//subfeed_engine/        ── GN/Ninja ──▶  subfeed_engine.aar  ──▶  app/build.gradle.kts
  ├ Java glue + 공개 API                  (Java glue + .so +        (local Maven 또는
  ├ C++ 임베더 + JNI                       paks/리소스/매니페스트)     체크인 artifact 로 소비)
  ├ 광고 모듈
  └ GN targets
```

- **Compose 앱을 GN 타깃으로 넣지 않는다.** Compose-in-GN은 무의미한 마찰 + 제품 UI를 Chromium 리베이스에 결합시킴.
- 엔진은 **AAR로 패키징**(Java glue + native .so + paks/리소스 + renderer 서비스 매니페스트 항목 포함), SubFeed Gradle 앱이 그걸 의존.
- 공개 API는 **좁게**: `SubfeedChromium.init(context)`, `SubfeedPlayerView`, `loadVideoId(id)`, 생명주기, fullscreen 콜백, `destroy()`.

---

## 3. 재생 표현 — YouTube IFrame embed

**결정: WebContents에 `youtube.com/embed/{id}` 한 페이지만 로드.**

```
https://www.youtube.com/embed/{VIDEO_ID}?autoplay=1&playsinline=1&controls=1&rel=0&enablejsapi=1
```

- 가장 깔끔한 "비디오만" 서피스. m.youtube.com watch 페이지의 Polymer DOM/동의창/댓글/추천/anti-adblock 탐침을 피함.
- `enablejsapi=1` → 나중에 postMessage로 play/pause/currentTime 제어 가능(향후 위치동기화 복귀 시 사용).
- InnerTube 플레이어 페이지 자작 금지(추출 전쟁으로 회귀).

### ⚠️ 보강: 임베드 불가 영상 (codex 미강조, 실무 필수)

일부 채널은 **임베드를 비활성화**한다 → `/embed/{id}` 가 "재생 불가". 임의 구독 채널을 다루는 피드 앱엔 치명적.

→ **정책: embed-first + watch-page 폴백.**
```
/embed/{id} 로드 → "playback disabled / unavailable" 감지
   → m.youtube.com/watch?v={id} 로 폴백 로드
```
광고 제거 훅은 **두 경로 모두 youtubei/v1/player를 호출**하므로 그대로 적용된다. watch 폴백 시엔 사이트 chrome을 CSS로 트림하거나 풀스크린 플레이어로 진입.

### DRM / 코덱 (빌드 플래그)
- 일반 크리에이터 업로드는 Widevine 불필요. 렌탈/영화/일부 음악만 필요 → **최소 슬라이스에서 Widevine 제외.**
- 단, **proprietary codecs(H.264/AAC) 빌드 플래그는 필요**(오픈 Chromium 기본엔 없음). `ffmpeg_branding="Chrome"`, `proprietary_codecs=true`.

---

## 4. 광고 제거 — 본질 (CRUX)

### 사실 (2025-2026, codex 검증)
- **URL/도메인 차단만으로는 부족.** YouTube 광고 결정은 대부분 **InnerTube `player` 응답** 안(`adPlacements`/`adSlots`/`playerAds`)에 있고, 콘텐츠와 같은 googlevideo 호스트를 공유한다.
- uBlock Origin도 호스트 차단이 아니라 **player 응답의 ad 필드를 prune/replace** 한다 (uAssets `quick-fixes.txt`가 `adPlacements`/`adSlots`/`playerResponse.adPlacements`/`adSlots`를 `player`·`youtubei/v1/player` 응답에서 제거).
- **SSAI(서버측 광고 삽입) 위험 상승**: 광고가 미디어 세그먼트에 stitch되면 응답 제거로 못 지운다 (중장기 리스크).

### 메커니즘 — 우리는 네트워크 스택을 소유한다
두 갈래로 건다 (Chromium 임베더 훅):

```
[요청 차단]  ContentBrowserClient::CreateURLLoaderThrottles()
   → ad ping/telemetry 차단:
       youtube.com/api/stats/ads, youtube.com/pagead/,
       doubleclick.net, googleadservices.com, googlesyndication.com,
       ActiveView/광고 측정 엔드포인트
   → googlevideo.com 은 과차단 금지(콘텐츠/광고 인프라 공유)

[응답 재작성]  ContentBrowserClient::WillCreateURLLoaderFactory()
   → 네트워크 URLLoaderFactory 를 wrapper로 감싸 가로채기
   → 대상 URL:
       www/m.youtube.com/youtubei/v1/player
       youtubei.googleapis.com/youtubei/v1/player
       youtubei/v1/get_watch, playlist?
       (ytInitialPlayerResponse 품은 watch/embed HTML 도)
   → body 버퍼링 → JSON 파싱 → 아래 필드 제거 → 수정본 서빙:
       adPlacements, adSlots, playerAds, adBreakHeartbeatParams,
       중첩된 playerResponse.adPlacements / .adSlots / .playerAds,
       그 ad 필드를 품은 배열 요소
```

- **주의**: body 변형은 `URLLoaderThrottle`에서 하지 말 것. **`URLLoaderFactory`/`URLLoaderClient` wrapper**로 버퍼링 후 변형.
- **룰 소스**: uBlock `uAssets`의 YouTube quick-fixes를 미러링/추종하면 유지보수 부담을 외부 지성에 위탁할 수 있다.

### 실패 모드의 우아한 저하 (이 설계의 진짜 장점)
- 룰이 뒤처져도 **엔진은 계속 재생** → 최악이 "광고가 한 번 나옴". NewPipe처럼 "전면 재생 불가"가 아님.

---

## 5. 데이터 흐름

```
[피드] FeedScreen (네이티브) ── RSS(youtube.com/feeds/videos.xml) ──▶ 영상 리스트
   │  (NewPipe 무관, 기존 그대로)
   │  영상 탭
   ▼
[재생] PlayerHost → subfeed_engine.loadVideoId(id)
   │
   ├ WebContents: youtube.com/embed/{id} 로드
   │     (불가 시 → m.youtube.com/watch?v={id} 폴백)
   │
   ├ 광고모듈: youtubei/v1/player 응답에서 ad 필드 strip + ad ping 차단
   │
   └ Chromium 미디어 파이프라인이 googlevideo 에서 직접 재생 → SurfaceView
```

피드/RSS/설정은 전부 네이티브 유지. 바뀌는 건 "재생 화면 속" 뿐.

---

## 6. 패치 표면 / 리베이스 전략

**원칙: 전부 additive, `//chrome` 무수정, `//content`에 YouTube 로직 하드코딩 금지.**

```
//subfeed_engine/                          (신규 디렉토리)
  java/org/subfeed/chromium/*              공개 API
  SubfeedContentMainDelegate               C++ 임베더
  SubfeedContentBrowserClient              ← 여기서 throttle/factory 훅 등록
  SubfeedContentRendererClient
  SubfeedBrowserContext
  SubfeedWebContentsHost
  ad/YoutubeAdResponseRewriter             광고 응답 재작성
  ad/YoutubeRequestThrottle                ad ping 차단
  ad/testdata/*.json                       캡처한 player 응답 픽스처
  BUILD.gn → subfeed_engine_{java,native,aar,apk}
```

기존 Chromium 파일 수정은 **GN visibility/BUILD include 1~2곳 정도**로 최소화. 리베이스는 제품 로직 충돌이 아니라 BUILD/API drift 해소 위주가 되도록 유지.

---

## 7. 리스크 (가능성 순) + 완화

| # | 리스크 | 수준 | 완화 |
|---|---|---|---|
| 1 | **YouTube anti-adblock 탐지** (재생오류/페이지 degradation) | 높음 | embed 경로가 watch보다 탐침 적음. 룰을 uBO 추종. 최악도 "광고 1회"로 저하 |
| 2 | **player 응답 스키마 변동** (adPlacements/adSlots churn) | 높음 | 룰을 데이터/설정으로 분리, uAssets 미러. 주~월 단위 유지보수 전제 |
| 3 | **SSAI(세그먼트 stitch 광고)** | 중상 | 응답 제거로 못 지움. 발생 시 별도 대응 필요(미해결) |
| 4 | **임베드 불가 / 연령·로그인 게이트** | 중 | watch-page 폴백. 로그인 필요 영상은 범위 밖 |
| 5 | **Widevine/보호 미디어** | 중(렌탈) / 낮(일반) | 최소 슬라이스 제외, 필요 시 추가 |
| 6 | **autoplay/fullscreen/오디오포커스/Compose 생명주기** | 중 | 회전/백그라운드/복귀 집중 테스트 |
| 7 | **AAR 패키징**(매니페스트/native/리소스/paks) | 중 | content_shell 패키징 참고 |
| 8 | **ToS/배포 리스크** | 공개배포 시 높음 | 개인용 + MinseoStore/NAS 배포 전제 |

---

## 8. 빌드 순서 (작게 증명 가능한 슬라이스부터)

1. `subfeed_engine_apk`: **하드코딩한 임베드 영상 1개가 재생**된다.
2. 같은 엔진을 **AAR로 패키징** → 기존 Gradle/Compose 재생 화면에서 로드.
3. fullscreen / 오디오 포커스 / 생명주기 / 뒤로가기 처리.
4. 모든 YouTube player/embed 로드에 **요청 로깅** 추가.
5. **응답 재작성기**: `youtubei/v1/player`의 `adPlacements`/`adSlots` strip → 광고 박힌 영상이 광고 없이 바로 시작됨을 증명.
6. 보수적 **ad ping 차단** 추가.
7. 캡처 player 응답으로 **픽스처/테스트**.
8. **피드 연동**: RSS 항목 탭 → `loadVideoId(id)`.
9. (보강) **임베드 불가 → watch 폴백** + 진단 화면(마지막 player URL/재작성 결과/재생가능 상태/검출된 ad 필드).

---

## 9. 기존 SubFeed와의 관계 — 무엇을 남기고 무엇이 대체되나

| 영역 | 이 설계에서 |
|---|---|
| 채널 RSS 피드 | **그대로 유지** (네이티브) |
| 설정 / Firebase 로그인 | 유지 (위치동기화는 범위 밖이라 휴면) |
| NewPipe / yt-dlp / NAS 추출 | **이 재생 모드에선 미사용** (대체). 다른 모드로 남길지는 선택 |
| ExoPlayer 재생 / PlayerScreen | **미사용** (Chromium WebContents가 대체) |
| 시청 위치 동기화 | **범위 밖** (향후 enablejsapi postMessage로 복귀 가능) |

> 재생 방식 토글에 "Chromium" 모드를 하나 더 추가하는 식으로 **공존**시킬 수도 있고, 완전히 갈아탈 수도 있다. 초기엔 별도 모드로 두고 검증 권장.

---

## 10. 미해결 / 다음 결정 필요

- **SSAI 대응**: 서버측 stitch 광고가 실제로 적용되면 응답 제거가 안 통함 → 그때 전략 재검토.
- **광고룰 갱신 채널**: 빌드 내장 vs 원격 갱신(NAS에서 룰 JSON 받기) — 후자가 앱/엔진 재빌드 없이 대응 가능.
- **embed vs watch 기본값**: embed-first 확정, 단 폴백 비율이 높으면 watch-first 재검토.
- **공존 여부**: 기존 NewPipe/NAS 경로를 폴백으로 남길지.

---

## 부록 A. codex 자문 핵심 출처

- content 레이어 정의 / content_shell: chromium `content/README.md`, `docs/android_build_instructions.md`
- 임베더 훅: `content/public/browser/content_browser_client.h` (`CreateURLLoaderThrottles`, `WillCreateURLLoaderFactory`)
- 광고 필드 제거 근거: uBlock Origin `uAssets/filters/quick-fixes.txt` (adPlacements/adSlots 등)
- IFrame 파라미터: developers.google.com/youtube/player_parameters
- WebView 아키텍처(왜 부적합): chromium `android_webview/docs/architecture.md`
- fullscreen 패턴: chromium `android_webview/docs/full-screen.md`
- Widevine/코덱: Chromium(브라우저) 위키 — 오픈 빌드엔 Widevine/proprietary codecs 미포함

## 부록 B. 한 장 요약

```
SubFeed(주인) = 네이티브 피드(RSS) + subfeed_engine.aar(임베드 Chromium content)
재생 = youtube.com/embed/{id} (불가 시 watch 폴백)
광고제거 = youtubei/v1/player 응답에서 ad 필드 strip (+ ad ping 차단)  ← 네트워크 스택 소유의 이점
이득 = 추출전쟁 종료 + 실패해도 "광고 1회"로 저하(전면불가 아님)
유지보수 = uBlock uAssets YouTube 룰 추종 (주~월 단위)
빌드 = //subfeed_engine GN → AAR → Gradle 소비 (Compose는 GN 밖)
```
