# NewPipe Extractor 유지보수 가이드

YouTube 스트림 추출이 깨질 때마다 NewPipe Extractor 최신 버전으로 흡수하는 절차와 누적 지식. **현재 SubFeed가 의존하는 버전**: `gradle/libs.versions.toml`의 `newpipe = "v0.26.2"`.

배경 분석은 [`20260524_newpipe_solution_analysis.md`](20260524_newpipe_solution_analysis.md), 도입 과정은 [`20260524_potoken_implementation.md`](20260524_potoken_implementation.md) 참조.

---

## 빠른 진입점 — `/sync-newpipe` 슬래시 명령

재생이 깨졌을 때 가장 빠른 길은 Claude Code 에서 **`/sync-newpipe`** 호출. 또는 "재생 문제 해결해줘" 같은 자연어 트리거.

명령 파일: `.claude/commands/sync-newpipe.md` (project scope, 리포에 커밋됨)

### 명령이 자동으로 하는 것

1. **사전 조건 체크** — `gh auth status`, working tree clean, 이 문서 존재
2. **현재 vs 최신 버전 비교** — `gradle/libs.versions.toml` 의 `newpipe` 값과 GitHub Releases 최신 tag 대조
3. **릴리즈 노트 요약** — 변경된 영역이 스트림 추출 관련인지 RSS/UI/타 서비스만 영향인지 판단
4. **위험 신호 사전 점검** — 아래 "위험 신호" 섹션의 키워드가 노트에 있으면 사용자에게 명시적 경고
5. **새 브랜치 + 버전 수정** — `fix/newpipe-{버전}-{date}` 브랜치 생성, `libs.versions.toml` 한 줄 업데이트
6. **빌드 + 단말 설치** — `./gradlew assembleDebug` → `adb install -r`
7. **확인 리스트 출력** — 재생/seek/화질전환/라이브 확인 항목 (CLAUDE.md "빌드 후 워크플로우" 형식)
8. **결과 반영** — 사용자 OK 회신 시 이 문서의 "깨짐 사례 기록"에 신규 항목 추가 제안 → 이후 `/ship` 권장

### 명령이 자동으로 안 하는 것

- **단말 재생 검증** — 실제로 영상 재생해서 OK 여부 판단은 사용자가 직접
- **API breaking change 대응** — 빌드 깨질 경우 자동 진행 중단, 사용자에게 보고 (이 문서의 "API Breaking Change 대응" 섹션 안내)
- **자동 PR 생성** — `/ship` 과 결합해 사용자가 명시적으로 호출

### 무한 retry 금지 규칙

명령에 박혀 있음. 이 셋 중 하나라도 발생하면 1회 알림 후 중단:

- `gh` 호출 실패 (인증/네트워크)
- 빌드 실패 (자동 fix 시도 안 함)
- 단말 미연결

### 언제 명령 안 쓰고 수동으로 가야 하나

- NewPipe major 버전 점프 (`v0.26 → v0.27`) — breaking change 가능성 높아 단계별 수동 진행 권장
- 릴리즈 노트가 모호하거나 우리 코드에 영향 있는지 명확하지 않을 때
- 이전 업데이트 시도가 실패해서 어디가 깨졌는지 우선 조사 필요할 때

명령은 일상적인 업데이트를 빠르게 흡수하는 용도지, **모든 케이스를 자동화하는 도구가 아님**.

---

## NewPipe Extractor가 SubFeed에서 하는 일

| 영역 | 깨지는가? | 관련 NewPipe 모듈 |
|---|---|---|
| RSS 피드 파싱 (channel feed XML) | 거의 안 깨짐 | XML 파서만, YouTube 외부 API |
| 스트림 URL 추출 (InnerTube API) | 자주 | `YoutubeStreamExtractor`, `YoutubeStreamHelper` |
| n-param deobfuscation (chunk URL throttling 해제) | 자주 | `YoutubeThrottlingParameterUtils`, `YoutubeJavaScriptExtractor` |
| signature deobfuscation (sig cipher) | 가끔 | `YoutubeSignatureUtils` |
| PoToken 통합 (BotGuard 토큰 받기) | 가끔 | `PoTokenProvider` interface — SubFeed에서 직접 구현 |
| DASH manifest 생성 (1080p+ adaptive) | 가끔 | `dashmanifestcreators/` (`YoutubeProgressiveDashManifestCreator` 등) |

**판단 기준**: 재생이 깨지면 99% 스트림 측. RSS는 별개 — 채널 목록 갱신 깨짐은 다른 원인.

---

## 깨짐 사례 기록 (시간순)

### 2026-05 — PoToken 도입 + n-param 통합

- **증상**: 짧은 재생 후 chunk 403, seek 시 거의 항상 403
- **원인**: YouTube 외부 클라이언트 차단 강화 — PoToken(BotGuard) 요구 + n-param JavaScript 난독화
- **NewPipe 해결**: v0.26.x — Rhino JS 인터프리터로 n-param/signature 실행, PoTokenProvider interface로 외부 위임
- **SubFeed 흡수**: PR #21 (`feat/potoken-2026-05`). v1.1.0.0
- **남은 우리 코드**: `PoTokenWebView` (BgUtils JS 실행) — NewPipe의 `PoTokenProvider`에 위임됨

### (다음 깨짐 사례를 여기 추가)

---

## 업데이트 절차

### 사전 점검

- [ ] `gh auth status` — 인증 안 되어 있으면 `gh auth login` 먼저
- [ ] working tree clean (`git status` — 다른 변경 없음 확인)
- [ ] 현재 NewPipe 버전 확인: `grep newpipe gradle/libs.versions.toml` → 현재 `v0.26.2`

### 최신 릴리즈 조회

```bash
gh api repos/TeamNewPipe/NewPipeExtractor/releases/latest --jq '.tag_name'
gh api repos/TeamNewPipe/NewPipeExtractor/releases/latest --jq '.body'
```

### 의사 결정

| 상태 | 행동 |
|---|---|
| 최신 = 현재 | NewPipe도 아직 fix 안 나옴. 다른 원인 의심 (앱 코드 자체 버그? PoToken WebView 문제?). |
| 최신 > 현재 + 노트가 스트림 관련 (n-param, signature, InnerTube, PoToken, DASH) | 업데이트 진행 |
| 최신 > 현재 + 노트가 RSS·UI·다른 서비스만 영향 | 굳이 업데이트 안 해도 됨 |

### 업데이트 적용

1. 새 브랜치: `git checkout -b fix/newpipe-{버전}-{date}` 예: `fix/newpipe-v0.26.3-2026-06`
2. `gradle/libs.versions.toml`의 `newpipe = "v0.26.2"` 한 줄을 새 버전으로 수정
3. 빌드: `./gradlew assembleDebug`
   - **컴파일 깨짐** = NewPipe API breaking change. 이 가이드 마지막의 "API breaking change 대응" 섹션 확인.
   - **빌드 성공** = 다음 단계
4. 설치: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`
5. 검증 (사용자 직접):
   - 한 영상 재생 시작 — 첫 chunk OK?
   - seek 동작 — 중간 위치로 점프했을 때 chunk 403 안 나는지
   - 화질 전환 — 144p ↔ 1080p 정상?
   - 라이브 방송 (가능하면) — 정상 재생?

### 마무리

- OK면 commit + push + PR (`/ship` 사용 가능)
- 이 문서의 "깨짐 사례 기록" 섹션에 신규 항목 한 줄 추가
- 실패면 `git checkout main && git branch -D fix/newpipe-...` 으로 폐기, 원인 추가 조사

---

## 위험 신호 — 업데이트 전에 한 번 더 보자

다음 항목이 릴리즈 노트에 나오면 **꼼꼼히 확인**하고 업데이트:

- `PoTokenProvider` interface 변경 — `SubFeedPoTokenProvider`/`PoTokenWebView` 수정 필요할 수 있음
- `Downloader` interface 변경 — `OkHttpDownloader`/`SubFeedDownloader` 수정 필요할 수 있음
- `StreamInfo`/`StreamExtractor` API 변경 — `NewPipeVideoExtractor`에서 호출하는 메서드 시그니처 확인
- protobuf 버전 변경 — `app/build.gradle.kts`의 `resolutionStrategy { force("com.google.protobuf:protobuf-javalite:...") }` 와 충돌 가능. Firebase BOM과 매치되는 버전 유지 필수.
- Rhino 버전 점프 — ProGuard 룰 재점검 필요

major 버전 점프 (`v0.26.x → v0.27.0`) 시 위 항목들이 동시에 바뀌었을 가능성이 높음.

---

## API Breaking Change 대응

빌드가 깨지면:

1. 컴파일 에러 위치 확인 — 보통 `NewPipeVideoExtractor.kt`, `SubFeedPoTokenProvider.kt`, `SubFeedDownloader.kt` 중 하나
2. NewPipe Extractor 릴리즈 노트와 [Migration Guide](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/MIGRATION.md) (있다면) 확인
3. 본가 NewPipe 앱 ([github.com/TeamNewPipe/NewPipe](https://github.com/TeamNewPipe/NewPipe))이 동일 버전에서 같은 인터페이스를 어떻게 쓰는지 보고 참고
4. 30분 이상 막히면 일단 롤백: `libs.versions.toml`을 이전 버전으로 되돌리고 다음 release를 기다림

---

## 디버깅용 로그 (TAG 가이드)

NewPipe 측은 블랙박스가 많아서 깨졌을 때 어디서 막혔는지 빨리 좁히는 게 핵심. 4개 영역에 전략적으로 로그가 박혀 있음.

### TAG 매트릭스

| 파일 | TAG | 언제 찍히나 |
|---|---|---|
| `data/newpipe/SubFeedDownloader.kt` | `SubFeedNpDownload` | NewPipe 의 **모든** HTTP 호출 (RSS, InnerTube, base.js, chunk URL) |
| `data/newpipe/SubFeedPoTokenProvider.kt` | `SubFeedNpPoToken` | PoToken 발급 요청 시 (WEB/WEB_EMBED/ANDROID/IOS) |
| `data/newpipe/NewPipeStreamFetcher.kt` | `SubFeedStream` | 영상별 스트림 추출 진행 단계 |
| `data/newpipe/DashMpdBuilder.kt` | `SubFeedNpDash` | inline DASH MPD 빌드 (1080p+ 재생 path) |

### 한 번에 다 보려면

```bash
adb -s <serial> logcat -c    # 버퍼 클리어
adb -s <serial> logcat | grep -E "SubFeedNp|SubFeedStream"
```

특정 영역만:

```bash
adb logcat -s SubFeedStream:* SubFeedNpDownload:*
```

### 각 로그가 의미하는 것

**`SubFeedNpDownload`** — `METHOD path → CODE body=XB Yms`
- 2xx → `D` (debug). 정상 응답.
- 4xx/5xx → `W` (warning). 응답 코드와 메시지 함께 찍힘.
- 예외 → `E` (error). 예외 타입과 메시지.
- `redirected=Y` — request URL 과 final response URL 이 다를 때

**`SubFeedNpPoToken`** — `client=WEB videoId=X → ok (Yms) [cached] visitor=AAAA… player=BBBB… streaming=CCCC…`
- 토큰 값은 prefix 8자만. 캐시 hit/miss 추적 용도.
- `[cached]` — latency `< 200ms` 일 때 표시. PoTokenWebView 캐시가 작동 중인 신호.
- `→ null` — 우리 PoTokenProvider 가 null 반환. PoToken 발급 실패. WebView 초기화 또는 BgUtils 실행 깨진 상태 의심.
- `→ failed after Xms` + stack trace — 예외 발생.

**`SubFeedStream`** — 한 영상 fetch 의 단계별 흐름
```
fetch start videoId=X
fetchPage OK videoId=X in 7234ms          ← 첫 호출은 5~10초 정상, 두 번째부터 ms 단위
streams videoId=X durationSec=N
  dashUrl=true hlsUrl=false                ← 어떤 URL 이 추출됐나
  videoOnly=12 audio=3                     ← 트랙 수
  videoMuxed=4 chapters=0
fetch OK videoId=X type=DASH ...           ← 또는 type=HLS / DASH-INLINE / MUXED
```
- `type=DASH` (1순위), `type=HLS` (2순위), `type=DASH-INLINE` (3순위 — 우리가 직접 MPD 빌드), `type=MUXED` (4순위, 최후)
- `DEGRADED` — MUXED 경로 진입 시 표시. 1080p 안 됨, 360~720p 한계.
- `fetch FAIL` — 모든 경로 실패. 스트림 카운트가 0인지, fetchPage 자체가 실패했는지 같은 줄에 진단 정보.

**`SubFeedNpDash`** — `build → null` 시 사유, 성공 시 트랙 요약
- `필터 후 video 0개. 원본=N heights=[...] noRanges=M` — 1080p 이하인 영상이 없거나 initRange/indexRange 메타데이터 누락
- `필터 후 audio 0개. 원본=N noItag=A noRanges=B` — itag 없거나 range 메타데이터 누락
- `build OK: video=N heights=[...] audio=M langs=[...] mpdLen=K` — 정상

### 자주 보는 디버깅 시나리오

**시나리오 1: 재생 시작은 되는데 5초 후 끊김**
- `SubFeedNpDownload` 로그에 chunk URL 호출이 `→ 403` 으로 찍힘
- → n-param 또는 signature deobfuscation 실패 의심
- → NewPipe upstream 에 같은 증상 issue 있는지 확인 후 업데이트 시도

**시나리오 2: 어떤 영상은 되고 어떤 영상은 안 됨**
- 안 되는 영상의 `SubFeedStream` 로그에서 `dashUrl=false hlsUrl=false videoOnly=0 audio=0` 패턴
- → fetchPage 자체가 빈 응답을 받음
- → 해당 영상의 PoToken 검증 실패 가능성 (`SubFeedNpPoToken` 의 발급은 성공해도 InnerTube 측에서 거부)
- → 해당 영상이 연령제한/지역제한/멤버십 제한인지 확인

**시나리오 3: 1080p 가 안 되고 360p 로만 나옴**
- `SubFeedStream` 에 `type=MUXED` + `DEGRADED` 로그
- → 그 직전 `SubFeedNpDash` 의 `build → null` 사유 확인 (`initRange/indexRange 필요` 등)
- → NewPipe 의 ItagItem 데이터에 SegmentBase range 가 안 들어옴 → upstream 변경 의심

**시나리오 4: 영상 시작이 느려짐**
- `SubFeedNpPoToken` latency 가 매번 큰 값 + `[cached]` 표시 없음
- → 매번 새 토큰 발급 중. 캐시 동작 의심.
- → `SubFeedNpDownload` 로 InnerTube 를 몇 번 호출하는지 가시화 (보통 영상당 1~2번이 정상, 그 이상이면 NewPipe 가 client fallback 중)

### 사생활/보안

절대 안 찍는 것:
- 전체 URL (sig/n-param 같은 sensitive query 제외)
- 토큰 값 (visitor / player / streaming PoToken 전체 — prefix 8자만)
- 응답 body 내용 (length 만)

찍는 것:
- URL path (query 제거 후)
- 토큰 prefix 8자 (캐시 추적용)
- 응답 코드 + body 크기 + 지연

### 로그 추가 / 수정 시 주의

- 새 로그는 같은 TAG 컨벤션 유지 (`SubFeedNp*` 또는 `SubFeedStream`)
- 토큰/시그니처 같은 sensitive 값은 prefix 만 또는 boolean (`isNotEmpty()`)
- 로그 메시지에 변하는 데이터 (videoId, 카운트, 시간) 가 들어가야 추적 가치 있음. "도착함" 같은 vague 메시지는 의미 없음.

---

## 자주 참고할 외부 링크

- NewPipe Extractor releases: [github.com/TeamNewPipe/NewPipeExtractor/releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases)
- NewPipe 앱 (참조 구현): [github.com/TeamNewPipe/NewPipe](https://github.com/TeamNewPipe/NewPipe)
- yt-dlp 이슈 (YouTube 변화 빠른 추적 채널): [github.com/yt-dlp/yt-dlp/issues](https://github.com/yt-dlp/yt-dlp/issues)
