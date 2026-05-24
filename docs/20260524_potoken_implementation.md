# PoToken 구현 (v1.0.2.0)

날짜: 2026-05-24
브랜치: `feat/potoken-2026-05`

## 배경

2025~2026년 YouTube가 InnerTube 클라이언트(iOS/Android/ANDROID_VR 등)에 대해 PoToken(Proof of Origin Token) 인증을 강제하기 시작. PoToken 없이는:

- iOS 클라이언트: metadata 응답은 OK이지만 stream chunk URL이 즉시 403 반환
- ANDROID_VR: `playabilityStatus=LOGIN_REQUIRED` ("로그인하여 봇이 아님을 확인하세요")
- ANDROID: 동일하게 PoToken 요구

증상:
- seek 시 즉시 재생 불가 (새 chunk 요청이 403)
- 짧은 재생 후 "외부 재생 차단" (subsequent chunk 403)

참고: [yt-dlp PO-Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide), [yt-dlp #15583](https://github.com/yt-dlp/yt-dlp/issues/15583)

## 해결책

**WebView에서 BotGuard JS VM을 실행해 PoToken 발급**

NewPipe의 PoToken 구현([PR #11955](https://github.com/TeamNewPipe/NewPipe/pull/11955))을 SubFeed로 포팅. RxJava 의존성이 없는 프로젝트이므로 Kotlin Coroutines로 변환.

### 아키텍처

```
NewPipeVideoExtractor.getStreamInfo(videoId)
  ├─ 1순위: tryWebClientWithPoToken(videoId)
  │   ├─ PoTokenProvider.getWebClientPoToken(videoId)
  │   │   ├─ fetchVisitorData()                                 # /youtubei/v1/visitor_id
  │   │   ├─ PoTokenWebView.create(context)                     # BotGuard VM 로드
  │   │   │   ├─ assets/po_token.html (BgUtils JS)
  │   │   │   ├─ POST jnn/v1/Create → challenge
  │   │   │   ├─ runBotGuard(challenge) → botguardResponse + webPoSignalOutput
  │   │   │   └─ POST jnn/v1/GenerateIT → integrityToken (12h TTL)
  │   │   ├─ generator.generatePoToken(visitorData) → streamingPot
  │   │   └─ generator.generatePoToken(videoId) → playerPot
  │   ├─ POST youtubei/v1/player (WEB context + visitorData + playerPot)
  │   └─ buildAdaptiveDashManifest(adaptiveFormats, urlSuffix="&pot=$streamingPot")
  │       → inline DASH MPD with all chunk URLs having &pot=
  └─ 2~4순위: 기존 iOS/ANDROID_VR/ANDROID fallback
```

### 파일 구성

| 파일 | 역할 | 라인 |
|---|---|---|
| `app/src/main/assets/po_token.html` | BgUtils JavaScript (loadBotGuard, snapshot, obtainPoToken) | 127 |
| `data/potoken/PoTokenException.kt` | PoTokenException, BadWebViewException | 11 |
| `data/potoken/PoTokenResult.kt` | (visitorData, playerRequestPoToken, streamingDataPoToken) | 7 |
| `data/potoken/PoTokenGenerator.kt` | suspend interface (generatePoToken, isExpired, close) | 12 |
| `data/potoken/JavaScriptUtil.kt` | challenge 파싱, base64 ↔ Uint8Array 변환 | 95 |
| `data/potoken/PoTokenWebView.kt` | WebView에서 BotGuard 실행, JS Interface 콜백 | 205 |
| `data/potoken/PoTokenProvider.kt` | @Singleton, generator 캐시 + 재시도 로직 | 80 |

수정된 파일:

- `data/NewPipeVideoExtractor.kt`
  - `@Inject` PoTokenProvider 추가
  - `tryWebClientWithPoToken()` 메서드 추가 (1순위 경로)
  - `buildAdaptiveDashManifest()` 에 `urlSuffix` 파라미터 추가

### 주요 설계 결정

1. **WEB 클라이언트 우선**: iOS/ANDROID_VR/ANDROID 가 PoToken 없으면 모두 실패하는 시점이라 WEB+PoToken을 메인 경로로. 실패 시 legacy 폴백.

2. **inline DASH 빌드**: WEB 응답의 `adaptiveFormats` 를 받아 각 URL에 `&pot=<streamingPot>` 붙인 inline DASH MPD 빌드. HLS/DASH manifest URL을 직접 쓰지 않는 이유 — manifest 안의 chunk URL에 PoToken을 주입할 수 없어서.

3. **Coroutines 포팅**: NewPipe 원본은 RxJava 기반(Single). 프로젝트가 RxJava를 안 쓰므로 `CompletableDeferred` + `suspendCoroutine` 으로 변환.

4. **PoToken 캐싱**:
   - integrityToken: 12h TTL (Provider singleton 안에서 유지)
   - visitorData: 1회 발급 후 재사용
   - streamingPot: visitorData 변경 안 되면 재사용
   - playerPot: 영상별로 새로 발급

5. **WebView 생성은 main thread 강제**: `PoTokenWebView.create()`는 `withContext(Dispatchers.Main)` 내부에서 인스턴스화. Android requirement.

6. **JavaScriptUtil**: NewPipe는 nanojson + okio. SubFeed는 `org.json` + `android.util.Base64` 로 대체.

## 테스트

- 빌드: `BUILD SUCCESSFUL`
- 플립 단말(R3CX705W62D) 검증 진행 중
- 로그 태그: `SubFeedPoToken`, `PoTokenWebView`, `SubFeedStream`

## 알려진 제약

- WebView 초기화에 2~5초 소요 (첫 영상 재생 시 지연)
- integrityToken 만료 시 자동 재생성
- WebView 미지원 단말에서는 legacy 클라이언트로 자동 폴백
- YouTube가 BotGuard JS 인터페이스를 바꾸면 po_token.html 갱신 필요 (NewPipe 추적)
