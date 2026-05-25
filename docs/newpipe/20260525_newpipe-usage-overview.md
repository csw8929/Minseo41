# SubFeed의 NewPipe Extractor 활용 구조

작성일: 2026-05-25  
대상 독자: 이 프로젝트를 처음 보는 개발자, 또는 한동안 손을 떼고 있다가 돌아온 경우  
관련 문서:
- [maintenance.md](maintenance.md) — 깨졌을 때 버전 업 절차
- [20260524_newpipe_solution_analysis.md](20260524_newpipe_solution_analysis.md) — 도입 방안 분석 (v0.26 도입 전 조사)
- [20260524_potoken_implementation.md](20260524_potoken_implementation.md) — 도입 과정 기록

---

## 1. 왜 NewPipe가 필요한가

YouTube는 공식 API 없이 외부 클라이언트가 영상을 재생하지 못하도록 여러 장치를 쓴다.

```
유저가 영상 탭 →  스트림 URL 필요  →  그냥 요청하면 403 또는 throttling
```

구체적으로 세 가지 장벽이 있다.

| 장벽 | 설명 | 안 뚫으면 |
|---|---|---|
| **PoToken (BotGuard)** | YouTube가 요청 주체가 봇인지 판별하는 토큰. InnerTube API 요청에 포함해야 함 | InnerTube 자체가 스트림 정보 안 줌 |
| **n-param deobfuscation** | 스트림 chunk URL에 붙는 `&n=` 파라미터. 난독화된 JavaScript로 변환해야 함 | seek 시 403, 수 KB/s로 throttling |
| **signature deobfuscation** | 일부 영상 chunk URL의 `&sig=` cipher. 역시 JavaScript 실행으로 해석 | 해당 영상 재생 불가 |

NewPipe Extractor(`v0.26.2`)가 이 세 가지를 모두 처리한다.  
n-param/signature는 YouTube의 `base.js`를 다운로드해 Rhino(JS 인터프리터)로 실행하고,  
PoToken은 외부 구현을 주입할 수 있는 인터페이스(`PoTokenProvider`)를 열어 놓았다.

---

## 2. 역할 분담 — NewPipe가 하는 일 vs. 우리가 하는 일

```
SubFeed 앱
├── [우리] RSS 파싱        채널의 최신 영상 목록 가져오기 (NewPipe 안 씀)
│
└── [NewPipe] 스트림 추출   videoId → 재생 가능한 URL
    ├── [NewPipe] InnerTube API 호출       YouTube 내부 API로 스트림 정보 요청
    ├── [NewPipe] n-param deobfuscation    Rhino + base.js 로 chunk URL throttling 해제
    ├── [NewPipe] signature deobfuscation  Rhino + base.js 로 sig cipher 해독
    ├── [NewPipe] DASH manifest URL 추출   고화질 adaptive streaming 주소
    ├── [우리]   PoToken 발급              WebView + BotGuard JS (NewPipe에 주입)
    ├── [우리]   Inline DASH 빌드          NewPipe가 준 스트림 목록으로 MPD XML 조립
    └── [우리]   자막/챕터 수집            NewPipe extractor에서 꺼냄
```

RSS 파싱은 YouTube의 공개 Atom 피드를 직접 읽기 때문에 NewPipe가 필요 없고, 안정적이다.

---

## 3. 관련 파일 지도

```
SubFeedApp.kt                       NewPipe 초기화 진입점
│
├─ data/newpipe/
│   ├─ SubFeedDownloader.kt         NewPipe의 모든 HTTP 요청을 OkHttp로 중계
│   └─ SubFeedPoTokenProvider.kt    NewPipe PoTokenProvider ↔ 우리 PoTokenWebView 연결
│
├─ data/potoken/
│   ├─ PoTokenWebView.kt            WebView에서 BotGuard JS 실행 → PoToken 발급
│   ├─ PoTokenProvider.kt           PoTokenWebView 생명주기 + 캐싱 관리 (싱글턴)
│   ├─ PoTokenGenerator.kt          인터페이스 (PoTokenWebView가 구현)
│   ├─ PoTokenResult.kt             visitorData + playerPot + streamingPot 묶음
│   ├─ PoTokenException.kt          BotGuard 실패 예외
│   └─ JavaScriptUtil.kt            BotGuard 응답 파싱 유틸
│
├─ data/
│   ├─ VideoExtractor.kt            인터페이스 (구현체 교체 지점)
│   └─ NewPipeVideoExtractor.kt     핵심 구현 — RSS 파싱 + 스트림 추출 + 챕터/자막
│
└─ di/AppModule.kt                  VideoExtractor → NewPipeVideoExtractor 바인딩
```

---

## 4. 초기화 흐름 (앱 시작 시 1회)

`SubFeedApp.onCreate()`에서 NewPipe를 초기화한다.

```kotlin
NewPipe.init(
    SubFeedDownloader,               // 모든 HTTP를 OkHttp로 처리
    Localization("ko", "KR"),        // 자막/제목 기본 언어
    ContentCountry("KR"),
)
YoutubeStreamExtractor.setPoTokenProvider(poTokenProvider)  // 우리 PoToken 연결
```

`SubFeedDownloader`는 NewPipe가 내부적으로 InnerTube API, `base.js`, DASH manifest 등을 가져올 때 사용하는 HTTP 클라이언트다. OkHttp 위에 얇은 래퍼로 만들어, NewPipe가 요청하는 URL/헤더/바디를 그대로 전달한다.

---

## 5. 스트림 URL 추출 흐름

`PlayerViewModel`이 `VideoExtractor.getStreamInfo(videoId)`를 호출하면 `NewPipeVideoExtractor`가 처리한다.

```
videoId
  │
  ▼
extractor.fetchPage()   ← InnerTube 호출 + base.js 파싱 + n-param/sig deobfuscate
                           (내부에서 PoToken도 SubFeedPoTokenProvider를 통해 발급)
  │
  ├─ 1순위: DASH manifest URL이 있으면  →  "dash:<url>"  (최고화질, ExoPlayer가 직접 fetch)
  │
  ├─ 2순위: HLS manifest URL이 있으면  →  "hls:<url>"   (라이브 방송, 일부 영상)
  │
  ├─ 3순위: videoOnlyStreams + audioStreams로 Inline DASH 빌드
  │         → "dash:data:application/dash+xml;base64,<mpd>"  (YouTube DASH URL이 없을 때)
  │
  └─ 4순위: muxed videoStreams 중 최고 해상도  →  직접 URL  (last resort, 보통 720p 이하)
```

실제로 대부분의 영상은 1순위 DASH manifest URL로 처리된다.

### Inline DASH 빌드 (`buildDashFromNewPipeStreams`)

NewPipe가 준 `videoOnlyStreams`와 `audioStreams` 목록을 받아 MPD XML을 직접 조립한다.

- 비디오: 1080p 이하, 각 해상도를 별도 Representation으로 노출 → ExoPlayer ABR + 앱의 QualityMenu가 선택 가능
- 오디오: 언어별로 AdaptationSet 분리 (한국어 더빙 영상 지원)
- NewPipe가 이미 n-param/sig deobfuscate한 URL을 BaseURL에 그대로 넣는다
- 완성된 MPD XML을 Base64로 인코딩해 `data:` URI로 ExoPlayer에 전달

---

## 6. PoToken 흐름 (가장 복잡한 부분)

PoToken은 YouTube가 "이 요청은 실제 브라우저/앱에서 온 것"임을 확인하는 토큰이다. YouTube의 BotGuard JavaScript를 실제로 실행해야 발급된다.

### 전체 흐름

```
[NewPipe] extractor.fetchPage()
  │ (InnerTube 요청 직전)
  │ setPoTokenProvider()로 등록된 SubFeedPoTokenProvider.getXxxClientPoToken(videoId) 호출
  ▼
SubFeedPoTokenProvider
  │ (NewPipe 인터페이스 ↔ 우리 구현 연결 브릿지)
  │ runBlocking { ourProvider.getWebClientPoToken(videoId) }
  ▼
PoTokenProvider (싱글턴, Mutex로 동시성 보호)
  │ 1. visitorData 없으면 InnerTube /visitor_id 호출 → visitorData 발급
  │ 2. generator 없거나 만료면 PoTokenWebView.create() 호출 (WebView 초기화)
  │ 3. streamingPot = generator.generatePoToken(visitorData)  (1회, 재사용)
  │ 4. playerPot   = generator.generatePoToken(videoId)        (영상마다)
  │ 5. PoTokenResult(visitorData, playerPot, streamingPot) 반환
  ▼
SubFeedPoTokenProvider
  │ PoTokenResult → NpPoTokenResult로 변환
  ▼
[NewPipe] InnerTube 요청에 PoToken 포함 → YouTube가 스트림 정보 반환
```

### PoTokenWebView 내부 초기화

WebView가 생성되고 `PoTokenWebView.create()`가 완료될 때까지 약 2~5초가 걸린다 (BotGuard JS 다운로드 + 실행). 이후 토큰 발급은 ms 단위.

```
PoTokenWebView.create(context)
  │
  ├─ assets/po_token.html 로드 (WebView 기반 YouTube 환경 흉내)
  ├─ YouTube BotGuard 서버 (jnn/v1/Create) 에서 challenge 데이터 받기 (OkHttp)
  ├─ WebView에서 runBotGuard(data) 실행 → botguardResponse 획득
  ├─ YouTube 서버 (jnn/v1/GenerateIT) 에서 integrityToken 발급 (OkHttp)
  └─ 완료 — 이후 generatePoToken(identifier) 반복 호출 가능
```

WebView의 네트워크는 `blockNetworkLoads=true`로 막혀 있다. BotGuard가 내부적으로 추가 네트워크 요청을 시도하더라도 차단되고, 실제 HTTP는 OkHttp를 통해서만 나간다.

### SubFeedPoTokenProvider가 4개 클라이언트 모두 지원하는 이유

NewPipe의 기본 구현(`PoTokenProviderImpl`)은 WEB/WEB_EMBEDDED 클라이언트에만 PoToken을 제공하고 ANDROID/IOS는 null을 반환한다. 우리는 같은 visitor 세션 PoToken을 ANDROID/IOS에도 제공해 더 많은 영상에서 동작하게 했다.

---

## 7. 자막 수집

`extractor.subtitlesDefault`를 호출하면 NewPipe가 영상의 자막 트랙 목록을 반환한다.

```kotlin
subtitles.map { sub ->
    CaptionTrack(
        languageCode = sub.languageTag,
        displayName   = sub.languageTag,
        baseUrl       = sub.content,      // TimedText XML URL
        isAutoGenerated = sub.isAutoGenerated,
    )
}
```

자막 URL만 받아 놓고, 실제 내용은 유저가 자막을 선택했을 때 `PlayerViewModel`이 OkHttp로 별도 다운로드한다. `TimedTextToSrt.kt`가 YouTube TimedText XML을 SRT 형식으로 변환한다.

---

## 8. 챕터 파싱

챕터는 NewPipe가 직접 지원하지 않는다(extractor API 미제공). 대신 영상 description 텍스트를 직접 파싱한다.

```
description 텍스트에서 타임스탬프 패턴 찾기:
  "[?(HH:)?MM:SS]? <제목>" 형태의 줄
  → 3개 이상 연속 + 단조 증가 → 유효한 챕터 목록
```

유효하지 않으면 빈 리스트 반환 (챕터 UI 숨김).

---

## 9. 의존성 충돌 처리

NewPipe Extractor는 `protobuf-javalite`를 내부 의존성으로 들고 온다. Firebase Firestore도 같은 라이브러리를 사용하므로 버전이 맞지 않으면 `duplicate class` 빌드 오류가 발생한다.

해결: `app/build.gradle.kts`에서 NewPipeExtractor 의존성 선언 시 protobuf를 제외한다.

```kotlin
implementation(libs.newpipe.extractor) {
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
}
```

Firebase BOM이 관리하는 버전을 그대로 사용하게 되며, NewPipe의 protobuf 사용 코드는 스트림 추출 경로와 무관한 부분에 한정되어 있어 실제 동작에 영향 없다.

---

## 10. 버전 업데이트

NewPipe 버전은 `gradle/libs.versions.toml`의 한 줄만 바꾸면 된다.

```toml
newpipe = "v0.26.2"  # ← 여기만 바꿈
```

YouTube 정책 변경으로 재생이 깨질 때 대부분 NewPipe 버전 업으로 해결된다.  
상세 절차는 [maintenance.md](maintenance.md) 참조.
