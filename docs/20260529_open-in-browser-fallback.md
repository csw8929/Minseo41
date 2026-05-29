# 재생 실패 시 "브라우저로 열기" 폴백

작성일: 2026-05-29
브랜치: `feature/open-in-browser-fallback-2026-05`
버전: 1.1.2.0 → 1.2.0.0

## 배경

SubFeed는 NewPipe Extractor로 YouTube 스트림을 추출해 인앱 재생한다. YouTube의 anti-bot(PoToken/BotGuard) 정책 때문에 extractor 기반 앱은 **누구도 100% 인앱 재생이 불가능**하다. 특정 영상은 추출 자체가 막히거나 chunk fetch에서 403이 난다.

NewPipe·Tubular·PipePipe 등 같은 계열 앱들은 이 한계를 인정하고, 인앱 재생이 안 되는 영상은 **외부(YouTube 앱/브라우저)로 넘기는 폴백**을 둔다. SubFeed도 같은 전략을 채택한다.

## 변경 내용

`ui/PlayerScreen.kt` — 재생 실패 화면(`uiState.error != null`)에 "브라우저로 열기" 버튼 추가.

```kotlin
Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
```

YouTube 앱이 설치돼 있으면 앱으로, 없으면 기본 브라우저로 열린다. 기존 "목록으로" 버튼은 그대로 유지.

## 위치

- 에러 UI 블록: `PlayerScreen.kt` `uiState.error != null ->` 분기
- import 추가: `android.content.Intent`

## 한계 / 다음 단계

이 폴백은 근본 해결이 아니라 탈출구다. 인앱 재생률 자체를 올리려면 PoToken 사용 방식을 고쳐야 한다(WEB 클라이언트 전용 토큰 라우팅 + WEB UA 일관성). 별도 브랜치에서 진행 예정.
