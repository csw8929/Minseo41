# YouTube 재생 문제 원인 분석 & 해결 (PoToken + NewPipeExtractor)

날짜: 2026-05-24
브랜치: `feat/potoken-2026-05`
상태: 검증 완료 — 재생/seek 정상, 화질 개선 패치 적용

---

## 1. 증상

1. **재생 직후 "외부 재생 차단" 에러로 중단** — 짧게 재생 후 chunk 403
2. **seek 시 무조건 재생 불가** — mid-video chunk 가 즉시 403

logcat 핵심 에러:
```
onPlayerError code=2004(ERROR_CODE_IO_BAD_HTTP_STATUS) httpStatus=403
httpUrl=https://...googlevideo.com/videoplayback?...&c=IOS&...
retry-403 exhausted after 2 attempts — surfacing error
```

## 2. 원인 분석 — 3개 레이어 동시 필요

YouTube 외부 클라이언트 재생을 2025~2026 정책 아래 안정적으로 하려면 다음 모두 처리해야 합니다:

| 레이어 | 역할 | 누락 시 증상 |
|---|---|---|
| **PoToken (BotGuard)** | "봇 아님" 증명 토큰. InnerTube 요청과 stream URL 양쪽에 필요. | LOGIN_REQUIRED 또는 chunk 403 |
| **n-param deobfuscation** | googlevideo chunk URL 의 `&n=<obfuscated>` 를 `base.js` 의 JS 함수로 변환. | YouTube 가 throttling 적용 또는 mid-video chunk 403 |
| **signature deobfuscation** | 일부 클라이언트는 `&sig=` cipher 처리 필요. | manifest URL 거부 |

각 레이어 검증 근거:
- yt-dlp PO-Token-Guide: iOS/ANDROID 둘 다 PoToken 필수
- NewPipe `YoutubeThrottlingParameterUtils.java`: n-param 처리 로직
- NewPipe `YoutubeJavaScriptPlayerManager.kt`: base.js 다운로드 + 캐싱
- 실제 로그 URL 확인: 우리 IOS 폴백 URL 에 `&n=` 도 `&pot=` 도 없음 → mid-video bytes 거부

**우리 초기 구현의 한계**: PoToken 만 구현. n-param/sig 누락. NewPipe 의 검증된 솔루션을 도입하는 게 가장 합리적.

## 3. 해결 — NewPipeExtractor 라이브러리 도입

### 의존성

```kotlin
// app/build.gradle.kts
implementation(libs.newpipe.extractor) {
    // Firebase Firestore 의 protolite-well-known-types 와 duplicate class 충돌 회피.
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
}
```

```toml
# gradle/libs.versions.toml
newpipe = "v0.26.2"  # 2026-05-23 릴리즈
newpipe-extractor = { group = "com.github.TeamNewPipe", name = "NewPipeExtractor", version.ref = "newpipe" }
```

### 아키텍처

```
SubFeedApp.onCreate()
  ├─ NewPipe.init(SubFeedDownloader, Localization("ko","KR"), ContentCountry("KR"))
  └─ YoutubeStreamExtractor.setPoTokenProvider(SubFeedPoTokenProvider)

영상 재생 → PlayerViewModel.loadVideo(videoId)
  → NewPipeVideoExtractor.getStreamInfo(videoId)
      ├─ ServiceList.YouTube.getStreamExtractor(watchUrl)
      ├─ extractor.fetchPage()
      │   ├─ NewPipe 가 InnerTube 호출 (ANDROID/IOS/WEB)
      │   │   └─ SubFeedPoTokenProvider 가 우리 PoTokenWebView 위임
      │   │       └─ data/potoken/PoTokenWebView.kt (BgUtils JS)
      │   ├─ base.js 다운로드 (SubFeedDownloader → OkHttp)
      │   └─ n-param/sig deobfuscate (Rhino JS 인터프리터)
      ├─ extractor.videoOnlyStreams → URL n-param 처리 끝남
      ├─ extractor.audioStreams      → URL n-param 처리 끝남
      └─ buildDashFromNewPipeStreams() → inline DASH MPD (base64 data: URL)
          → ExoPlayer 재생
```

### 새로 추가된 파일

| 파일 | 역할 | 라인 |
|---|---|---|
| `app/src/main/assets/po_token.html` | BgUtils JavaScript (BotGuard VM 실행) | 127 |
| `data/potoken/PoTokenException.kt` | 예외 타입 | 11 |
| `data/potoken/PoTokenGenerator.kt` | suspend interface | 12 |
| `data/potoken/PoTokenResult.kt` | data class | 7 |
| `data/potoken/JavaScriptUtil.kt` | challenge 파싱, base64↔Uint8Array 변환 | 95 |
| `data/potoken/PoTokenWebView.kt` | WebView에서 BotGuard 실행 (Coroutines 포팅) | 205 |
| `data/potoken/PoTokenProvider.kt` | @Singleton, generator 캐싱 + 재시도 | 80 |
| `data/newpipe/SubFeedDownloader.kt` | NewPipe Downloader → OkHttp 어댑터 | 55 |
| `data/newpipe/SubFeedPoTokenProvider.kt` | 우리 PoToken 을 NewPipe interface 에 plug | 45 |

### 수정된 파일

- `app/build.gradle.kts` — NewPipeExtractor dep + protobuf exclude + packaging excludes
- `gradle/libs.versions.toml` — v0.26.2 핀
- `SubFeedApp.kt` — `NewPipe.init()` + `setPoTokenProvider()` 호출
- `NewPipeVideoExtractor.kt` — InnerTube 직접 호출 제거, NewPipe `getStreamExtractor()` 위임
- `PlayerViewModel.kt` — friendly error message (LIVE_STREAM_OFFLINE / LOGIN_REQUIRED / UNPLAYABLE / AGE)
- `PlayerScreen.kt` — onPlayerError 의 httpUrl 로그 truncate 제거 (디버깅 편의)

## 4. 화질 개선 (2025-05-24 추가)

NewPipe 의 `extractor.dashMpdUrl` 이 일부 영상에서 빈 string 반환 → fallback 으로 `videoStreams`(muxed, 360~720p) 사용 → 360p로 떨어짐.

**해결**: `videoOnlyStreams` + `audioStreams` 결합 inline DASH MPD 빌더 신규 추가. NewPipe 가 URL의 n-param/sig 를 이미 처리한 상태이므로 그대로 BaseURL 에 박으면 됨.

```kotlin
// NewPipeVideoExtractor.kt:buildDashFromNewPipeStreams()
// - 비디오: 1080p 이하 모든 트랙을 Representation 으로 노출 → ExoPlayer ABR + 우리 QualityMenu 가 선택
// - 오디오: 언어별 best-bitrate 1개씩 (audioLocale 기준) → 한국어 더빙 우선
// - SegmentBase indexRange/initRange 는 itagItem 의 getInitStart/End, getIndexStart/End 활용
```

우선순위 흐름:
1. NewPipe `dashMpdUrl` (있으면 사용)
2. NewPipe `hlsUrl` (라이브)
3. **`videoOnlyStreams` + `audioStreams` → inline DASH** ← 새로 추가 (대부분의 VOD 가 이 경로)
4. `videoStreams` (muxed, 마지막 fallback)

## 5. 검증

테스트 단말: 플립 (R3CX705W62D)

```
logcat:
  PoTokenWebView: init done, expirationSec=43200
  SubFeedPoToken: PoToken init OK, visitorData(len=48) streamingPot(len=166)
  SubFeedStream: getStreamInfo OK type=DASH-INLINE video=N audio=M
  savePositionNow positionMs=1536029  ← 25분 시점 (seek + 시청 확인)
```

## 6. 알려진 제약 / 위험

- **PoToken WebView 초기화**: 첫 영상 재생 시 BotGuard 다운로드 + JS 실행으로 2~5초 지연. 이후 12시간 캐시.
- **YouTube 의 base.js / botguard.js 변경**: NewPipeExtractor 가 추적. 라이브러리 업데이트로 대응.
- **APK 크기 증가**: NewPipeExtractor + Rhino 로 약 2MB.
- **GPL-3.0**: NewPipeExtractor 라이센스. 개인 사용 전제라 무관.
- **Protobuf 충돌**: NewPipe 의 `protobuf-javalite` exclude 로 Firebase Firestore 우선. Firestore 정상 동작 확인 필요 (현재 시청 위치 동기화 검증됨).

## 7. 참고

- yt-dlp PoToken Guide: https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
- NewPipe PoToken PR: https://github.com/TeamNewPipe/NewPipe/pull/11955
- 의사결정 근거: `docs/20260524_newpipe_solution_analysis.md`
