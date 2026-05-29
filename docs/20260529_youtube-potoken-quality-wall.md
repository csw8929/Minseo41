# YouTube PoToken — 인앱 고화질 재생의 구조적 한계 조사

작성일: 2026-05-29
관련 브랜치: `experiment/potoken-web-only-2026-05` (폐기), `feature/open-in-browser-fallback-2026-05` (채택, PR #25)
결론: **이 아키텍처(NewPipe Extractor + WebView WEB PoToken)로는 인앱 고화질 일관 재생 불가.** 360p + 브라우저 폴백이 현실적 최선.

## 증상

- 모든 영상이 360p muxed 로만 재생됨 (adaptive 고화질 스트림이 안 잡힘).
- 고화질을 강제로 끌어오면(IOS 클라이언트 활성화) 추출은 1080p까지 되나, 재생 단계(chunk fetch)에서 403/EOF 로 아예 재생 불가.

## 근본 원인 (NewPipe release 0.26.2 + dev 소스로 2회 확인)

NewPipe 의 YouTube 스트림 추출 구조:

| 클라이언트 | 용도 | PoToken |
|---|---|---|
| ANDROID | 스트림 (항상 fetch) | ANDROID-context, null 이면 reel(Shorts) 응답 → adaptive 없음 → 360p |
| IOS | 스트림 (`setFetchIosClient(true)` 일 때만) | IOS-context, 최근 streaming URL 에 `&pot=` 요구 |
| WEB | **메타데이터/썸네일 전용** | 스트림에 안 씀 |

핵심 모순:
- 우리가 생성할 수 있는 PoToken 은 **WEB-context** 뿐이다 (`PoTokenWebView` 가 데스크톱 Chrome UA 로 BotGuard 실행).
- NewPipe 는 스트림을 **ANDROID/IOS 에서만** 받고, 그 chunk 는 해당 클라이언트 context 의 PoToken 을 요구한다.
- ANDROID/IOS context PoToken 은 DroidGuard / 기기 attestation 기반이라 WebView 로 생성 불가.
- 즉 **우리가 만들 수 있는 토큰(WEB)과 NewPipe 가 쓰는 토큰(ANDROID/IOS)이 영구적으로 어긋난다.** 우리 `PoTokenWebView` 토큰은 현재 사실상 아무 데도 안 쓰인다.

## 시도한 것과 결과

1. **ANDROID/IOS PoToken null + WEB UA (experiment 1차)**
   - ANDROID null → NewPipe 가 reel 응답으로 빠짐 → `videoOnly=0` → 360p muxed.
   - 로그 증거: `reel/reel_item_watch` 매 영상 호출, WEB player 응답 3KB(stripped).

2. **+ setFetchIosClient(true) + IOS UA (experiment 2차)**
   - IOS adaptive 추출 성공: `videoOnly=12 audio=15`, DASH-INLINE 1080p 빌드 OK.
   - 그러나 chunk fetch 실패:
     - `403` on `c=IOS` audio URL — `pot=` 없음 (IOS streaming PoToken 요구).
     - `EOFException` (서버가 연결 끊음) — 다수 영상.
   - 4개 영상 0개 재생 성공.

## 검증된 결론

- NewPipe release(0.26.2) 와 dev 브랜치 **둘 다** WEB 을 스트림에 쓰지 않음. 버전 업으로 해결 안 됨.
- YouTube 가 IOS streaming 에도 PoToken 게이팅을 확대 중. IOS 무토큰 경로(과거 1080p 우회로)가 닫히고 있음.
- 따라서 NewPipe 를 extractor 로 쓰는 한, pot 게이팅 영상의 인앱 고화질 재생은 불가능.

## 유일하게 능력에 맞는 대안 (미채택, 향후 옵션)

yt-dlp 방식: **WEB(또는 tv_embedded/mweb) 클라이언트로 직접 InnerTube player 요청 + 우리 WEB streamingDataPoToken 을 chunk URL 에 `&pot=` 으로 부착.** WEB PoToken 은 우리가 만들 수 있으므로 원리상 가능.

- 문제: NewPipe 가 이 경로를 제공하지 않음. 우리가 InnerTube WEB player 요청 + base.js 의 signature/n-param deobfuscation 을 **자체 구현**해야 함.
- 비용: 수일, YouTube 변경마다 깨짐. base.js 파싱 유지보수 부담.
- 판단: 개인용 앱 범위에 과함. 정말 필요해질 때 재검토.

## 채택한 해결책

**브라우저 폴백 (PR #25)** — 재생 실패 시 "브라우저로 열기" 버튼으로 YouTube 앱/브라우저에 넘김. NewPipe·Tubular·PipePipe 등 extractor 기반 앱이 공통으로 쓰는 표준 탈출구. 인앱은 가능한 영상(대개 360p)을 재생하고, 막힌 영상은 외부로 핸드오프.

## 한 줄 요약

고화질은 이 구조로 못 잡는다. 원인은 "우리가 만들 수 있는 PoToken(WEB)"과 "NewPipe 가 쓰는 PoToken(ANDROID/IOS)"의 영구 불일치. 360p + 브라우저 폴백이 정직한 최선.
