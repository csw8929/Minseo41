# SubFeed 개발 기록

> 작성일: 2026-05-05  
> 현재 상태: **영상 재생 동작 확인**

---

## 프로젝트 개요

**목적**: 개인 사용 Android YouTube 구독 피드 앱  
**사용 기기**: 갤럭시 폰 2대 + 태블릿  
**핵심 요구사항**:
- 오늘 구독 채널의 새 영상 목록 표시
- 광고 없는 재생
- 기기 간 시청 위치 이어보기 동기화 (Firebase Firestore)
- YouTube Takeout XML로 구독 채널 가져오기

---

## 기술 스택

| 항목 | 선택 | 이유 |
|---|---|---|
| 언어 | Kotlin | Android 표준 |
| UI | Jetpack Compose | 선언형 UI, 최신 표준 |
| DI | Hilt 2.52 | Compose 연동 공식 지원 |
| 영상 재생 | Media3 ExoPlayer 1.5.1 | DASH/HLS 지원 |
| 채널 피드 | YouTube RSS Atom | 안정적, API 키 불필요 |
| 스트림 추출 | YouTube InnerTube API (iOS 클라이언트) | bot detection 우회 |
| 동기화 | Firebase Firestore | 실시간 크로스디바이스 동기화 |
| 인증 | Firebase Auth (Google Sign-In) | |
| 이미지 | Coil 2.7.0 | Compose 연동 |
| HTTP | OkHttp 4.12.0 | |
| 빌드 | Gradle 8.13 + KSP 2.1.0 | |

---

## 아키텍처

```
app/
└── java/com/minseo41/subfeed/
    ├── data/
    │   ├── VideoExtractor.kt         # 인터페이스 — 피드/스트림 추출 계약
    │   ├── NewPipeVideoExtractor.kt  # 구현체 (채널피드: RSS, 스트림: InnerTube)
    │   ├── SubscriptionRepo.kt       # 채널 목록 관리 + 오늘 피드 조회
    │   └── SyncRepo.kt               # Firebase 시청 위치 읽기/쓰기
    ├── model/
    │   ├── VideoItem.kt              # 영상 데이터 모델
    │   ├── SubscribedChannel.kt      # 채널 모델
    │   └── WatchPosition.kt          # 시청 위치 모델
    ├── ui/
    │   ├── FeedScreen.kt             # 오늘 영상 목록 화면
    │   ├── FeedViewModel.kt          # 피드 로딩 상태 관리
    │   ├── PlayerScreen.kt           # ExoPlayer 재생 화면
    │   ├── PlayerViewModel.kt        # 스트림 URL 로딩 + 위치 저장
    │   ├── SettingsScreen.kt         # 구독 채널 관리
    │   └── SettingsViewModel.kt      # 채널 가져오기/저장
    ├── di/AppModule.kt               # Hilt 의존성 주입 설정
    ├── MainActivity.kt               # Navigation 설정
    └── SubFeedApp.kt                 # Application 클래스
```

### 핵심 설계 원칙: VideoExtractor 인터페이스

```kotlin
interface VideoExtractor {
    suspend fun getChannelFeed(channelUrl: String): List<VideoItem>
    suspend fun getStreamUrl(videoId: String): String
}
```

YouTube 추출 방식이 바뀌어도 `NewPipeVideoExtractor.kt` 하나만 수정하면 됨.  
나머지 코드는 인터페이스만 의존하므로 영향 없음.

---

## 주요 기능별 구현

### 1. 채널 피드 (YouTube RSS)

```
GET https://www.youtube.com/feeds/videos.xml?channel_id={channelId}
```

- API 키 불필요, 안정적
- 채널당 최신 15개 영상 반환
- XML Pull Parser로 직접 파싱 (title, videoId, publishedAt, thumbnail)
- 68개 채널 병렬 조회 (`async { }.awaitAll()`)
- 오늘 날짜 필터링 후 업로드 시각 역순 정렬

### 2. 광고 없는 스트림 재생

YouTube InnerTube API를 iOS 클라이언트로 직접 호출.  
자세한 내용은 아래 "재생 버그 해결 과정" 섹션 참고.

### 3. 시청 위치 동기화 (Firebase Firestore)

**저장 경로**: `users/{uid}/positions/{videoId}`

**저장 정책**:
- 30초 debounce: 재생 중 30초마다 위치 저장 (불필요한 Firestore write 최소화)
- 즉시 저장: 뒤로가기 또는 화면 dispose 시
- 충돌 해결: 더 큰 `positionMs` 우선 (뒤로 이동하지 않음)

**재개 흐름**:
1. PlayerViewModel이 `loadVideo()` 호출 시 Firestore에서 저장된 위치 읽기
2. ExoPlayer `seekTo(resumePositionMs)` 후 재생 시작

### 4. 구독 채널 가져오기

YouTube 계정 → 데이터 내보내기 → `subscriptions.xml` (YouTube Takeout XML 형식)  
ADB로 기기에 파일 전송 후 Settings 화면에서 불러오기.

### 5. ExoPlayer DASH/HLS 처리

`getStreamUrl()`이 `"hls:"` 또는 `"dash:"` 접두사로 URL 타입을 표시:

```kotlin
val mediaItem = when {
    url.startsWith("dash:") -> MediaItem.Builder()
        .setUri(url.removePrefix("dash:"))
        .setMimeType(MimeTypes.APPLICATION_MPD)
        .build()
    url.startsWith("hls:") -> MediaItem.Builder()
        .setUri(url.removePrefix("hls:"))
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .build()
    else -> MediaItem.fromUri(url)
}
```

---

## 빌드 환경

- **compileSdk / targetSdk**: 36
- **minSdk**: 26 (Android 8.0)
- **JDK**: Android Studio 내장 JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`)
- **Gradle**: 8.13 (wrapper 수동 생성 — gradle-wrapper.jar 없이 시작했기 때문)
- **NewPipe Extractor**: `com.github.TeamNewPipe:NewPipeExtractor:v0.26.1` (JitPack)  
  → 현재 채널 피드 파싱에는 미사용 (RSS로 대체), OkHttpDownloader 클래스만 유지

**빌드 명령**:
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

**설치 명령**:
```bash
~/Library/Android/sdk/platform-tools/adb -s R3CT70FY0ZP install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 재생 버그 해결 과정

이 앱에서 가장 많은 시간이 걸린 부분. YouTube의 bot detection 정책 때문에 여러 접근법을 시도했음.

### 시도 1: NewPipe StreamExtractor (실패)

**에러**: `Got HTML document, expected JSON response (latest url was: youtubei/v1/visitor_id)`

**원인**: NewPipe v0.26.1이 스트림 추출 전 `visitor_id`를 YouTube API에서 가져오는데,  
YouTube가 2025년 2월부터 랜덤 생성 visitor_data를 거부하고 HTML 동의 페이지를 반환.  
NewPipe v0.27.6+ 에서 WebView 기반 PoToken 생성으로 수정됐으나 JitPack에 미출시.

### 시도 2: Invidious API (실패)

**에러**: `Read error: ssl=... TLSV1_ALERT_INTERNAL_ERROR`

**원인**: Invidious API가 반환하는 `hlsUrl`은 Invidious 서버를 통해 프록시된 URL.  
이 URL의 스트림 데이터는 Invidious 서버 IP에 바인딩되어 있어,  
클라이언트(Android)에서 직접 요청하면 TLS 오류 또는 403 발생.

### 시도 3: Piped API (실패)

**에러**: `Value Piped of type java.lang.String cannot be converted to JSONObject`

**원인**: 2025년 말 이후 대부분의 공개 Piped 인스턴스가 다운 또는 차단 상태.  
API JSON 대신 "Piped"라는 텍스트(웹 홈페이지)를 반환.

### 시도 4: YouTube InnerTube API — ANDROID 클라이언트 (실패)

**에러**: `HTTP 400 FAILED_PRECONDITION`

**원인 (잘못 파악)**: API 키가 revoked된 것으로 오해.  
**실제 원인**: `youtubei.googleapis.com`이 아닌 `www.youtube.com` 엔드포인트를 써야 함.  
+ `ANDROID` 클라이언트에 A/B 테스트로 integrity check가 적용되기 시작함.

> API 키 `AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w`는 revoked가 아님.  
> yt-dlp 최신 버전도 동일 키 사용 중.

### 시도 5: TVHTML5_SIMPLY_EMBEDDED_PLAYER (실패)

**에러**: `streamingData 없음 — playabilityStatus: null`

**원인**: 두 가지 문제:
1. `clientScreen: "EMBED"` 필드 누락 → YouTube가 요청 거부
2. API 키 없이 요청 → 인증 실패

### 시도 6: ANDROID_VR + TVHTML5 (실패)

**에러**: `playabilityStatus.reason: "이 애플리케이션 또는 기기에서 YouTube가 더 이상 지원되지 않습니다."`

**원인**: YouTube가 Oculus Quest VR 클라이언트 요청을 실제 VR 기기가 아닌 것으로 탐지.  
한국 기기에서 해당 클라이언트 차단 중 (지역별 A/B 테스트).

### 시도 7: iOS 클라이언트 ✅ 성공

**현재 구현. 재생 확인됨.**

```
POST https://www.youtube.com/youtubei/v1/player?key=AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc&prettyPrint=false
User-Agent: com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)
X-YouTube-Client-Name: 5
X-YouTube-Client-Version: 21.02.3
Content-Type: application/json

{
  "videoId": "VIDEO_ID",
  "context": {
    "client": {
      "clientName": "IOS",
      "clientVersion": "21.02.3",
      "deviceMake": "Apple",
      "deviceModel": "iPhone16,2",
      "osName": "iPhone",
      "osVersion": "18.3.2.22E252",
      "hl": "ko",
      "gl": "KR"
    }
  }
}
```

**성공 이유**:
- iOS 클라이언트 전용 API 키 사용 (`AIzaSyB-63vPrd...`)
- iOS 클라이언트는 HLS manifest를 기본 반환 → ExoPlayer에 최적
- PoToken 불필요
- 엔드포인트: `www.youtube.com` (googleapis.com 아님)

**응답에서 추출**:
1. `streamingData.hlsManifestUrl` → `"hls:{url}"` 형태로 반환 (1순위)
2. `streamingData.formats[].url` 중 최고 height → 그대로 반환 (2순위)

**폴백 순서** (코드에 구현됨):
1. iOS 클라이언트
2. ANDROID_VR (Oculus Quest 1.65.10)
3. TVHTML5_SIMPLY_EMBEDDED_PLAYER

---

## YouTube InnerTube API 2026년 현황 정리

| 클라이언트 | PoToken 필요 | 현황 |
|---|---|---|
| `WEB` | 필요 (BotGuard) | 사실상 사용 불가 |
| `ANDROID` | 일부 필요 | 지역별 A/B 테스트 중 |
| `ANDROID_VR` (≤1.65.10) | 불필요 | 일부 지역 차단 |
| `IOS` | 불필요 | **현재 안정적** ✅ |
| `TVHTML5_SIMPLY_EMBEDDED_PLAYER` | 불필요 | `clientScreen:EMBED` 필수 |

**중요**: YouTube는 bot detection 정책을 지속적으로 강화 중.  
현재 동작하는 클라이언트도 언제든 차단될 수 있음.  
차단 시 `getStreamUrl()` 내 `attempts` 리스트 순서/파라미터를 업데이트하면 됨.

---

## 기기 설정

**테스트 기기**: SM-F936N (Galaxy Z Fold 4), device serial `R3CT70FY0ZP`

**구독 채널 XML 경로 (기기 내)**:
```
/sdcard/Download/subscriptions.xml
```

**ADB 파일 전송**:
```bash
~/Library/Android/sdk/platform-tools/adb -s R3CT70FY0ZP push subscriptions.xml /sdcard/Download/
```

---

## 향후 개선 사항

1. **클라이언트 버전 자동 갱신**: iOS/Android YouTube 앱 버전이 올라가면 clientVersion도 업데이트 필요
2. **NewPipe Extractor 업그레이드**: v0.27.6+ 출시 시 JitPack 버전 업데이트 후 `getStreamUrl()` 내 NewPipe 방식 복원 가능 (VideoExtractor 인터페이스 덕분에 isolated 변경)
3. **화질 선택**: 현재 HLS manifest가 있으면 그대로 사용 (ExoPlayer가 자동 품질 선택). 추후 adaptive format에서 특정 해상도 고정 옵션 추가 가능
4. **영상 시간 표시**: RSS에 duration 정보가 없어 현재 0으로 표시. 재생 시작 후 ExoPlayer에서 채워넣는 방식으로 개선 가능
5. **영상 다운로드**: 초기 요구사항에 있었으나 미구현. `adaptiveFormats`에서 최고화질 video+audio 선택 후 OkHttp로 저장하는 방식으로 추가 가능
