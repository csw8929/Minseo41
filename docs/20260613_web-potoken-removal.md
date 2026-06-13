# WEB PoToken 경로 제거 (dead code 정리)

작성일: 2026-06-13

## 배경

codex 자문 + 기존 분석(`20260529_youtube-potoken-quality-wall.md`)이 동일한 결론으로 수렴:

- 우리가 생성 가능한 PoToken 은 **WEB-context** 뿐이다 (`PoTokenWebView` 가 데스크톱 Chrome UA + BotGuard 실행).
- NewPipe 가 실제로 쓰는 스트림 클라이언트는 ANDROID/IOS/visionOS 이고, 각 chunk URL 은 **해당 클라이언트 context 의 PoToken** 을 요구한다.
- WEB 토큰과 NewPipe 가 쓰는 토큰은 **플랫폼 간 상호 교환 불가** (yt-dlp [PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide) 도 명시).
- 현재 고화질 재생을 살리는 v0.26.3 **visionOS 경로는 PoToken 을 아예 쓰지 않는다** (visitorData 를 InnerTube 에서 자체 발급).

결론: `SubFeedPoTokenProvider` + `data/potoken/` WebView 기반 토큰 생성 machinery 는 **사실상 아무 데도 안 쓰이는 dead weight** 였다. 빌드/유지보수 부담(WebView, BotGuard 호출, 12h 만료 재생성 로직)만 남기므로 제거한다.

## 제거 내역

### 삭제 파일
- `data/potoken/PoTokenProvider.kt`
- `data/potoken/PoTokenWebView.kt`
- `data/potoken/PoTokenGenerator.kt`
- `data/potoken/PoTokenResult.kt`
- `data/potoken/PoTokenException.kt`
- `data/potoken/JavaScriptUtil.kt`
- `data/newpipe/SubFeedPoTokenProvider.kt`
- `assets/po_token.html`

### 수정 파일
- `SubFeedApp.kt` — `setPoTokenProvider()` 호출 + 주입 + import 제거. `NewPipe.init()` 은 유지.
- `data/http/OkHttpDownloader.kt` — PoToken 전용이던 `post()` + 미사용 import(`toMediaType`, `toRequestBody`) 제거. `get()` 은 RSS/자막에서 계속 사용하므로 유지.
- `app/build.gradle.kts` — 의존성 주석에서 PoToken 언급 제거. `okhttp`, `newpipe.extractor` 의존성 자체는 유지.
- `data/newpipe/NewPipeStreamFetcher.kt`, `data/newpipe/README.md` — PoTokenProvider 관련 주석/표/API 섹션 정리.

DI 변경 없음 (두 provider 모두 `@Inject constructor` 기반이라 모듈 바인딩 부재).

## 빌드 / 설치

- `./gradlew assembleDebug` 성공 (새 경고/에러 없음).
- 플립(R3CX705W62D) 설치 완료.

## 확인 리스트

1. 앱 정상 실행 (크래시 없이 피드 로드)
2. 인앱 플레이어 모드로 전환 후 영상 재생 — visionOS 경로로 기존과 동일하게 고화질(DASH/HLS) 재생되는지
3. 자막 표시 정상 (OkHttpDownloader.get 경로)
4. RSS 피드 갱신 정상
5. 첫 재생 시 WebView 워밍업이 사라지면서 초기 재생 지연이 줄었는지 (체감)

## 위험 / 롤백

- 제거 대상은 현재 미사용으로 판단되나, 만약 WEB 토큰이 일부 영상의 WEB 메타데이터 추출에 기여하고 있었다면 해당 영상에서 메타데이터 누락 가능성. 위 확인 리스트 2~3 으로 검증.
- 문제 시 `git revert` 로 단일 커밋 롤백 가능.
