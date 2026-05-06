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

---

## PlayerScreen 전면 리뉴얼 구현 (2026-05-05)

design 문서: `docs/player-screen-renewal-2026-05-05.md` (Status: APPROVED, Mode: Builder)

### 추가/변경 파일

**신규 (10개)**
- `service/SubFeedMediaSessionService.kt` — Media3 service 골격 (Step 9 player 이전은 보류)
- `ui/player/PlayerControls.kt` — 가운데 3등분 ±30초/Play 큰 IconButton
- `ui/player/PlayerTopBar.kt` — 뒤로/title/화질 pill/자막/PIP/옵션 메뉴
- `ui/player/PlayerBottomBar.kt` — 시간/Slider/전체화면 토글
- `ui/player/DoubleTapSkipOverlay.kt` — 좌/우 더블탭 핫존 + ripple
- `ui/player/QualityMenu.kt` — HLS variants 화질 BottomSheet
- `ui/player/CaptionMenu.kt` — 자막 트랙 BottomSheet
- `ui/player/ResumeBanner.kt` — "X:XX부터 이어보기 / 처음부터" 배너
- `data/CaptionTrack.kt` — `CaptionTrack`, `StreamInfo` 모델
- `data/TimedTextToSrt.kt` — YouTube timedtext XML → SRT 변환
- `data/AuthRepo.kt` — Firebase + Google Sign-In + runtime resource lookup

**수정 (7개)**
- `AndroidManifest.xml` — PIP 속성, FOREGROUND_SERVICE 권한, MediaSessionService 등록
- `data/VideoExtractor.kt` — `getStreamUrl()` 제거, `getStreamInfo()` 추가
- `data/NewPipeVideoExtractor.kt` — InnerTube `captions.captionTracks` 추출
- `ui/SettingsScreen.kt` — Google 계정 연동 섹션
- `ui/SettingsViewModel.kt` — `AuthRepo` 통합
- `ui/PlayerViewModel.kt` — captions/quality/fullscreen/pip/banner state
- `ui/PlayerScreen.kt` — 전면 재작성 (`useController=false` + Compose 오버레이)
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — 의존성 추가

### 추가된 의존성

| 라이브러리 | 버전 | 용도 |
|---|---|---|
| `kotlinx-coroutines-play-services` | 1.9.0 | `Tasks.await()` (기존 SyncRepo도 이미 사용 중이었음 — 누락 수정) |
| `play-services-auth` | 21.2.0 | Google Sign-In |
| `compose-material-icons-extended` | (composeBom) | Forward30/Replay30/Fullscreen/ClosedCaption 아이콘 |

### 빌드 검증

```
BUILD SUCCESSFUL in 1m 24s
43 actionable tasks: 13 executed, 30 up-to-date
APK: app/build/outputs/apk/debug/Minseo41.apk
```

장애 해결 기록:
1. `local.properties` 없음 → `D:\AndroidDK` 지정 (Minseo21 참고)
2. `google-services.json` 없음 → placeholder 생성 (실제 Firebase 사용 시 user가 본인 파일로 교체)
3. `androidx.activity.PictureInPictureModeChangedInfo` Unresolved → `addOnPictureInPictureModeChangedListener` 폐기, `LocalConfiguration` 변경 시 `Activity.isInPictureInPictureMode` 폴링으로 단순화
4. `androidx.compose.ui.unit.dp` import 누락 → 추가

빌드 경고: `GoogleSignIn`/`GoogleSignInClient`가 deprecated. Android 권장은 Credential Manager (`androidx.credentials`)로 마이그레이션. 동작은 정상이나 이후 별도 라운드에서 교체 가능.

### 보류된 항목 (Step 9)

design doc `Next Steps` 9번 — **MediaSessionService에 Player 이전 + 알림 컨트롤**.

현재 상태:
- service는 manifest 등록 + 골격 클래스만 (자체적으로 빈 ExoPlayer 인스턴스 생성)
- `PlayerScreen`이 ExoPlayer를 직접 hold (화면 dispose 시 release)
- 옵션 메뉴의 "백그라운드 재생: 켬/끔" 토글은 SharedPreferences만 갱신 — 실제 동작 영향 없음

미보류 사유: PlayerScreen이 `MediaController`로 service의 player와 연결되도록 바꾸려면 비동기 connection + 화면 전환 race 가능성 (design doc Open Question #4). 별도 commit/세션에서 신중히.

### 주요 인터페이스 변경

**Before:**
```kotlin
interface VideoExtractor {
    suspend fun getStreamUrl(videoId: String): String
}
```

**After:**
```kotlin
interface VideoExtractor {
    suspend fun getStreamInfo(videoId: String): StreamInfo
}

data class StreamInfo(
    val streamUrl: String,
    val captionTracks: List<CaptionTrack>,
)
```

`PlayerViewModel.loadVideo()` 호출 한 번에 stream URL과 caption tracks를 동시에 받음 (InnerTube 응답 1회 호출 분).

### Open Issues (다음 라운드)

1. **Step 9 — MediaSessionService에 Player 이전**: 백그라운드 재생 옵션 실제 동작
2. **Sign-In 마이그레이션**: GoogleSignIn → Credential Manager (`androidx.credentials`)
3. **Cross-device 이어보기 실측**: 폴드 → 탭에서 Resume banner 노출 시나리오 3회 검증
4. **`google-services.json` 진짜 파일 적용**: 현재 placeholder. 실제 Firebase 프로젝트의 파일로 교체 필요.

### 설치 명령

```bash
adb -s R3CT70FY0ZP install -r app/build/outputs/apk/debug/Minseo41.apk
```

---

## PlayerScreen 리뉴얼 — 후속 fix 및 단말 검증 (2026-05-05)

design 문서 구현(위 섹션) 후 폴드(R3CT70FY0ZP)에 설치하면서 발견된 추가 이슈들과 그에 대한 해결을 시간순으로 기록한다.

### A. 빌드 환경 — `local.properties` 누락

**증상**: `./gradlew assembleDebug` 실행 시
```
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
or by setting the sdk.dir path in your project's local properties file at
'D:\workspace\Minseo41\local.properties'.
```

**원인**: 처음 클론된 상태로 `local.properties`가 없고, Windows 환경변수 `ANDROID_HOME`도 비어 있었다. 자동 탐색 위치(`%LOCALAPPDATA%\Android\Sdk`, `C:\Android\Sdk`)에도 SDK 없음.

**해결**: 같은 workspace의 `D:\workspace\Minseo21\local.properties`에서 SDK 경로(`D:\AndroidDK`)를 발견. `D:\workspace\Minseo41\local.properties`를 같은 값으로 생성.

```properties
# local.properties (gitignore됨, 커밋 금지)
sdk.dir=D\:\\AndroidDK
```

### B. `google-services.json` 누락 → placeholder → 진짜 파일

**증상 1**: SDK 셋업 후 빌드 시
```
Execution failed for task ':app:processDebugGoogleServices'.
> File google-services.json is missing.
```

**임시 해결**: 빌드 검증을 위해 `app/google-services.json` placeholder 작성. 단지 컴파일 통과용 가짜 값.

**증상 2**: placeholder json으로 빌드 → 설치 → Sign-In 시 `ApiException statusCode=10` (DEVELOPER_ERROR). placeholder의 `client_id`가 실제 Firebase 프로젝트와 매치되지 않으니 당연한 실패.

**최종 해결**: 진짜 Firebase 프로젝트(`ongoingview-3e904`, project_number `217716213799`) 생성. SHA-1 등록. Authentication > Google 활성화. Firestore 생성. `google-services.json` 다시 다운로드해 placeholder 교체.

자세한 단계는 별도 문서: `docs/firebase-setup-2026-05-05.md`

### C. protobuf 의존성 충돌 — Firestore vs NewPipe

이 세션에서 가장 시간이 많이 든 디버깅. 세 단계의 충돌이 연쇄적으로 발견됐다.

**시도 1 — `protolite-well-known-types` exclude (최초 상태)**
```kotlin
implementation(libs.firebase.firestore) {
    exclude(group = "com.google.firebase", module = "protolite-well-known-types")
}
```
초기 개발자가 적어둔 설정. 이전엔 Firestore를 실제로 호출 안 해서 잠재 버그가 가려져 있었음.

**증상**: Sign-In 성공 직후 Firestore 첫 호출 시 crash:
```
java.lang.RuntimeException: Internal error in Cloud Firestore (25.1.1).
Caused by: java.lang.NoClassDefFoundError: Failed resolution of: Lcom/google/type/LatLng;
```
Firestore 25.x는 protolite-well-known-types에 들어있는 LatLng를 사용한다. 그걸 exclude하면 시작 단계에서 죽는다.

**시도 2 — exclude 제거 (protolite keep)**
```kotlin
implementation(libs.firebase.firestore)   // exclude 제거
```
**증상**: 빌드 단계에서
```
Duplicate class com.google.protobuf.* found in modules
  protobuf-javalite-4.34.1.jar
  protolite-well-known-types-18.0.0.aar
```
NewPipe Extractor 또는 다른 transitive가 `protobuf-javalite`를 끌고 들어와서 protolite와 동일 클래스 충돌.

**시도 3 — `protobuf-javalite` exclude**
```kotlin
configurations.all {
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
}
```
**증상**: 다시 Firestore crash, 이번엔 다른 클래스:
```
NoClassDefFoundError: Failed resolution of: Lcom/google/protobuf/ByteString;
```
protolite도 javalite도 양쪽이 일부 클래스만 가지고 있다. **둘 중 어느 쪽을 빼도 다른 쪽에서 반대 클래스가 사라짐**. 하나만 keep으로는 해결 불가.

**시도 4 — `proto-google-common-protos` 추가**
```kotlin
implementation(libs.firebase.firestore) {
    exclude(group = "com.google.firebase", module = "protolite-well-known-types")
}
implementation(libs.proto.google.common.protos)   // LatLng를 별도로 제공
```
**증상**: `proto-google-common-protos`가 `protobuf-java` (full, lite 아님)을 끌고 들어와 또 Duplicate Class.

**최종 해결 — NewPipe Extractor 의존성 자체 제거**

코드 검토 결과 NewPipe Extractor 라이브러리는 사실상 **사용되지 않고 있었다**:
- `NewPipeVideoExtractor.kt`에서 `org.schabi.newpipe.extractor.downloader.{Downloader,Request,Response}`를 import하지만, `OkHttpDownloader`가 `Downloader`를 extend만 하고 있고 그 인스턴스(`OkHttpDownloader.INSTANCE`)는 코드 어디에서도 호출되지 않는다.
- 실제 HTTP 호출은 `OkHttpDownloader.companion`의 `get()`, `post()` static helper만 사용. 이건 OkHttp 직접 호출.
- 채널 피드는 RSS XML pull parsing, 스트림 URL은 InnerTube API 직접 호출 — NewPipe의 Extractor 클래스를 한 번도 거치지 않는다.

→ NewPipe 의존성을 제거하면 그 transitive로 따라오던 `protobuf-javalite`도 빠지고, Firestore가 끌고 오는 protolite-well-known-types만 남아 충돌이 깔끔하게 사라진다.

**적용 변경**:
1. `app/build.gradle.kts`에서 `implementation(libs.newpipe.extractor)` 제거
2. `NewPipeVideoExtractor.kt`에서 NewPipe import 제거, `OkHttpDownloader`를 `private constructor() : Downloader()`에서 그냥 `object`로 단순화
3. `firebase-firestore`는 exclude 없이 그대로 (protolite가 정상 동작)
4. `proto-google-common-protos` / `configurations.all { exclude }` 등 시도 중에 추가했던 우회 모두 제거

`libs.versions.toml`에는 `newpipe-extractor` 항목을 남겨뒀다 — 코드 정리 시점이라 dep만 끊은 상태. 향후 NewPipe v0.27.6+ 등에서 PoToken 기반 추출이 안정화되면 재도입 가능.

### D. 회전(rotation) 동작 안 함

**증상**: 단말을 가로로 돌려도 PlayerScreen이 portrait 그대로.

**원인 1**: `PlayerScreen.kt`에서 전체화면 토글 시 명시적으로
```kotlin
} else {
    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    ...
}
```
즉 평소 모드에서 portrait 강제. 단말 자동 회전 설정과 무관하게 portrait 고정.

**원인 2** (잠재): `LocalContext.current as? ComponentActivity`가 Compose ContextWrapper 때문에 null로 떨어질 수 있어 `act.requestedOrientation` 설정 자체가 동작 안 함.

**해결**:
- 평소 모드 orientation을 `SCREEN_ORIENTATION_UNSPECIFIED`로 변경 → 단말 자동 회전 설정에 위임
- `findComponentActivity()` extension function 추가, `ContextWrapper.baseContext`를 거슬러 올라가며 ComponentActivity를 안정적으로 찾음

```kotlin
private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
```

전체화면 모드는 그대로 `SCREEN_ORIENTATION_LANDSCAPE` 강제.

### E. status / navigation bar 영역과 콘텐츠 겹침

**증상**: 전체화면 아닐 때 PlayerScreen의 영상이 status bar / navigation bar 영역까지 그려져 system bar와 겹침.

**원인**: `MainActivity.onCreate()`에서 `enableEdgeToEdge()`를 호출하므로 Activity 전체가 edge-to-edge 모드. Compose 측에서 systemBars insets를 직접 처리해야 하는데 PlayerScreen이 `Box(Modifier.fillMaxSize())`만 쓰고 padding 없음.

**해결**: 평소 모드에서만 systemBars insets만큼 padding, 전체화면에서는 풀스크린 유지:

```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .then(
            if (uiState.isFullscreen) Modifier
            else Modifier.windowInsetsPadding(WindowInsets.systemBars)
        ),
)
```

### F. PIP 라이프사이클 — `addOnPictureInPictureModeChangedListener` API 변경

**증상**: 빌드 시 컴파일 오류
```
Unresolved reference 'PictureInPictureModeChangedInfo'
Unresolved reference 'isInPictureInPictureMode'
```

**원인**: `androidx.activity.PictureInPictureModeChangedInfo` 타입이 일부 환경에서 import 해석되지 않음 (활성 SDK 버전과 transitive 호환성 issue로 추정).

**해결**: PIP listener 등록 패턴 자체를 단순화. PIP 진입/이탈은 `manifest`에 `configChanges="orientation|screenSize|...|screenLayout"`이 걸려 있어 Compose의 `LocalConfiguration.current`가 새 인스턴스로 갱신되는 시점에 `Activity.isInPictureInPictureMode`를 직접 폴링:

```kotlin
val configuration = LocalConfiguration.current
LaunchedEffect(configuration) {
    val isInPip = activity?.isInPictureInPictureMode == true
    viewModel.setInPipMode(isInPip)
    if (isInPip) controlsVisible = false
}
```

listener 등록/해제 코드와 `Consumer<PictureInPictureModeChangedInfo>` import를 제거. PIP 진입은 그대로 `enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())`.

### G. cross-device 이어보기 — JobCancellationException

design doc + 첫 구현 직후 단말에서 검증 시 처음 발견.

**증상**: 영상 30초 이상 시청하고 뒤로가기 → logcat:
```
SubFeedSync: savePosition start: uid=..., positionMs=1125237
SubFeedSync: getPosition start: ...
SubFeedSync: getPosition read failed
  kotlinx.coroutines.JobCancellationException: Job was cancelled;
  job=SupervisorJobImpl{Cancelling}@...
SubFeedSync: savePosition write failed
  kotlinx.coroutines.JobCancellationException: ...
```

**원인**: `PlayerScreen`이 dispose되면서 `PlayerViewModel`도 곧 `cleared` 상태로 진입 → `viewModelScope` cancel → 그 안에서 await 중인 Firestore call이 같이 취소됨. 화면 이탈 시점의 마지막 위치가 commit되지 못한다.

**해결**: `SyncRepo`에 ViewModel 라이프사이클과 무관한 별도 process-level scope 도입.

```kotlin
@Singleton
class SyncRepo @Inject constructor(...) {
    // ViewModel scope이 cancel되어도 살아남는 detached scope.
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun savePositionDetached(videoId: String, positionMs: Long) {
        detachedScope.launch { savePosition(videoId, positionMs) }
    }
    ...
}
```

`PlayerViewModel.savePositionNow()`와 `onPositionChanged()` 둘 다 `syncRepo.savePositionDetached(...)` fire-and-forget으로 위임.

**현재 상태**: 첫 영상 commit은 성공해 cross-device resume 동작 확인 ("되네"). 다만 `StandaloneCoroutine was cancelled` 잔여 현상이 가끔 logcat에 보임 — 즉시 commit이 100% 보장되진 않는다. 향후 `Application` scope 또는 `ApplicationScope` Hilt module로 이전이 더 안전. 별도 commit 후속 작업.

### H. 이어보기 단위 30초 → 10초

**user 요청**: 가운데 ±버튼과 양면 더블탭의 스킵 단위가 30초는 너무 큰 것 같다. 10초로.

**적용**:
- `PlayerControls.kt`: `Forward30/Replay30` icon → `Forward10/Replay10`, contentDescription `"30초 …"` → `"10초 …"`
- `DoubleTapSkipOverlay.kt`: 같은 icon 교체, ripple label `"-30초"`/`"+30초"` → `"-10초"`/`"+10초"`
- `PlayerScreen.kt`: 4군데의 `30_000L` (가운데 ±버튼 onClick + 양면 더블탭 onSkipBack/onSkipForward) → `10_000L`

cross-device debounce save 주기(`PlayerScreen.kt`의 `LaunchedEffect`에 있는 `delay(30_000L)`)는 그대로 30초 유지. user 요청은 ±스킵 단위에 한정.

### I. 진단용 로그 태그 (디버깅 도구로 사용)

위 단계들을 진단하기 위해 다섯 개 태그 도입:

| 태그 | 위치 | 출력 내용 |
|---|---|---|
| `SubFeedAuth` | `data/AuthRepo.kt` | webClientId 해석, Sign-In intent 생성, Google account email/idToken 유무, Firebase signIn 결과, `ApiException.statusCode` |
| `SubFeedSettingsVM` | `ui/SettingsViewModel.kt` | sign-in result extras, success/failure |
| `SubFeedSettingsScreen` | `ui/SettingsScreen.kt` | launcher result code (0/-1), data 존재 여부 |
| `SubFeedPlayerVM` | `ui/PlayerViewModel.kt` | loadVideo의 savedPosition, debounce save trigger, savePositionNow 호출 |
| `SubFeedSync` | `data/SyncRepo.kt` | uid 상태, getPosition/savePosition start/ok/skip/failed |

logcat 명령:
```bash
adb -s R3CT70FY0ZP logcat -s SubFeedAuth:V SubFeedSettingsVM:V SubFeedSettingsScreen:V SubFeedPlayerVM:V SubFeedSync:V AndroidRuntime:E '*:S'
```

이 태그들은 운영용은 아니고 디버깅용. 추후 동작이 안정되면 일부 정리 가능. 현재는 cross-device sync 진단에 가장 유용해 그대로 둔다.

### J. 단말 검증 결과

**단말**: 폴드 (R3CT70FY0ZP)
**단말 셋업 트러블**: 첫 install 시 서명 불일치 (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — 이전 macOS PC의 debug.keystore 서명과 현재 Windows PC의 debug.keystore 서명이 다름. uninstall 후 재설치로 해결. (구독 채널 SharedPreferences는 사라짐, YouTube Takeout XML로 다시 import 필요.)

**검증한 시나리오**:
- [x] 빌드 통과 (BUILD SUCCESSFUL)
- [x] 앱 설치 + launch
- [x] PlayerScreen 가운데 3등분 큰 컨트롤 + 양면 더블탭 ±10초 노출
- [x] 자동 회전 동작 (단말 회전 잠금 해제 시 가로/세로 따라감)
- [x] status/navigation bar와 안 겹침
- [x] Settings → Google 계정 연동 성공 (`Firebase signIn ok: uid=VLc86xzggPdiDFn7ir1WW3enTx13`)
- [x] 영상 재생 → 30초 이상 시청 → 뒤로가기 → 같은 영상 재진입 → ResumeBanner 노출 + 마지막 위치부터 재생

**미검증 / 향후**:
- [ ] 폴드 → 탭/플립으로 cross-device resume 실측 (단일 단말 self-resume만 확인됨)
- [ ] PIP 진입/이탈
- [ ] 백그라운드 재생 (Step 9 — MediaSessionService에 player 이전, 보류 상태)
- [ ] 자막 트랙 fetch + ExoPlayer subtitle config (코드 경로는 있으나 실측 안 함)
- [ ] 화질 메뉴 — HLS adaptive에서 해상도 선택 실측 안 함

### K. `scripts/apk.sh` (workspace 공통 도구)

`Minseo41/scripts/apk.sh`는 두 줄짜리 wrapper로, 부모 `D:\workspace\scripts\apk.sh` (workspace 공통 APK 매니저)에 `minseo41` 인자를 자동으로 넘긴다.

```bash
#!/usr/bin/env bash
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/apk.sh" minseo41 "$@"
```

workspace 공통 스크립트(`D:\workspace\scripts\apk.sh`)는 5개 Android 프로젝트(Minseo, Minseo21, Minseo3, **Minseo41**, MyCard)를 통합 관리하는 메뉴형 도구:

- 프로젝트별 메뉴: install / build+install / uninstall / git branch+pull / logcat grep / build / releases 설치
- 단말 자동 인식 + 별명 매핑: `R54Y1003KXN`(탭) / `R3CT70FY0ZP`(폴드) / `R3CX705W62D`(플립) / `T813128GB25301890106`(미니) / `R34YA0007ZJ`(xr)
- 일괄 액션: 전 프로젝트 build all, build + releases/ 복사, 전 프로젝트 git pull

사용:
```bash
bash scripts/apk.sh           # → 부모 스크립트의 Minseo41 메뉴로 바로 진입
bash ../scripts/apk.sh        # → 프로젝트 선택 메뉴부터
```

logcat grep 액션은 매칭 라인을 자동으로 클립보드(Windows clip.exe / macOS pbcopy / Linux xclip)에 복사해 주는 기능까지 포함되어 있어 디버깅 시 편함.

---

## 채널 필터 + 즐겨찾기 + Room DB 도입 (2026-05-06)

### 배경

PlayerScreen 리뉴얼 직후 채널 필터/즐겨찾기 기능 요구가 나옴. 동시에 SharedPreferences 기반 채널 직렬화(`id;;name;;url|...`) 한계 때문에 필드 추가가 어려웠음. **Room으로 전면 이전 + 즐겨찾기 테이블 신설**로 한 번에 정리.

design doc: `docs/channel-filters-favorites-2026-05-06.md` (Status: APPROVED)

### 결정사항

| 항목 | 값 |
|---|---|
| DB | Room 2.6.1, `subfeed.db`, version 1 |
| 테이블 | `channels` (id PK, windowDays, maxCount, sortOrder), `favorites` (videoId PK + 메타) |
| import 형식 | **JSON only** (XML 직접 import 제거). Takeout XML은 converter로 JSON 변환 후 사용 |
| import 동작 | channels wipe + insert / favorites 보존 |
| 기본값 | `windowDays=1`, `maxCount=15` |
| 즐겨찾기 모델 | videoId PK + title/썸네일/channelName/uploadedAt 메타 같이 저장 (RSS에서 사라져도 즐겨찾기 탭에서 표시) |
| 즐겨찾기 동기화 | 로컬만 (Firestore X) — 향후 별도 PR |
| UI | FeedScreen 상단 `TabRow` 2개 탭(오늘의 구독영상 / 즐겨찾기) + 영상 row 우측 ⭐ 토글 |
| 채널 편집 | Settings → "채널 편집" 라우트 → 행별 편집 다이얼로그 / 삭제 |
| Shorts 제외 | 이번 범위 제외 |

### 의존성 변경

`gradle/libs.versions.toml`:
```toml
[versions]
room = "2.6.1"
kotlinxSerialization = "1.7.3"

[libraries]
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx     = { group = "androidx.room", name = "room-ktx",     version.ref = "room" }
room-compiler= { group = "androidx.room", name = "room-compiler",version.ref = "room" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

`app/build.gradle.kts`:
- plugin `kotlin-serialization` 추가
- `implementation(libs.room.runtime)` / `implementation(libs.room.ktx)` / `ksp(libs.room.compiler)`
- `implementation(libs.kotlinx.serialization.json)`

### 신규 / 수정 파일

```
app/src/main/java/com/minseo41/subfeed/
├── data/
│   ├── db/                         # 신규 패키지
│   │   ├── ChannelEntity.kt
│   │   ├── FavoriteEntity.kt
│   │   ├── ChannelDao.kt
│   │   ├── FavoriteDao.kt
│   │   └── SubFeedDatabase.kt
│   ├── FavoriteRepo.kt             # 신규
│   └── SubscriptionRepo.kt         # SharedPreferences 제거, Dao 사용, importFromJson, 채널별 windowDays/maxCount 적용
├── di/
│   └── DatabaseModule.kt           # 신규 — Room + Dao provide
├── model/
│   └── SubscribedChannel.kt        # windowDays/maxCount 필드 + DEFAULT_* + rssUrlFromId
├── ui/
│   ├── FeedViewModel.kt            # FeedTab + favorites StateFlow + toggleFavorite
│   ├── FeedScreen.kt               # 상단 TabRow + ⭐ 버튼 + 즐겨찾기 탭 분기
│   ├── SettingsViewModel.kt        # importFromJson, channelCount StateFlow
│   ├── SettingsScreen.kt           # JSON import 버튼 + 채널 편집 진입점
│   └── settings/                   # 신규 패키지
│       ├── ChannelEditScreen.kt
│       └── ChannelEditViewModel.kt
└── MainActivity.kt                 # settings/channels 라우트 등록

scripts/
└── takeout-xml-to-json.py          # 신규 — Takeout subscriptions.xml → SubFeed channels.json

docs/
├── channel-filters-favorites-2026-05-06.md  # 신규 design doc (APPROVED)
└── youtube-subscriptions-import-2026-05-05.md  # JSON 스키마 + converter 사용법으로 갱신
```

### 핵심 설계 포인트

- **`SubscribedChannel.url` 필드는 호환성 위해 유지하지만 JSON import에는 받지 않음**. 모든 RSS URL은 `rssUrlFromId(id)` 헬퍼로 복원.
- **`fetchTodayVideos`가 채널별 cutoff 적용**: `today.minusDays((windowDays-1).coerceAtLeast(0))` 부터 today까지. 그 후 `take(maxCount)`. RSS 자체 15개 한도 위에서 동작.
- **JSON import는 `channelDao.deleteAll()` → `insertAll`**. favorites 테이블은 손대지 않음.
- **즐겨찾기 토글은 FavoriteRepo 단일 진입점** — `exists` 체크 후 insert/delete 분기. UI는 `observeFavoriteIds()` Flow를 구독해 ⭐ 상태 자동 갱신.
- **Room migration 없음** — 이번이 첫 도입이라 version=1, fallback 정책 안 씀. 다음 schema 변경 시 migration 작성 예정.

### Converter 사용 예시

```bash
# Takeout subscriptions.xml → SubFeed channels.json
python scripts/takeout-xml-to-json.py subscriptions.xml -o channels.json

# default windowDays/maxCount 명시
python scripts/takeout-xml-to-json.py subscriptions.xml \
    --window-days 3 --max-count 10 -o channels.json
```

CSV/OPML/JSON Takeout 변형들은 docs/youtube-subscriptions-import-2026-05-05.md 의 B.3 섹션에 별도 Python 스니펫.

### 빌드/단말 검증

- `./gradlew assembleDebug` — BUILD SUCCESSFUL (1m 38s, KSP Room generation 포함)
- 폴드(R3CT70FY0ZP) 설치 — `Performing Streamed Install / Success`
- 실측 항목(이후 단말에서 확인): JSON import / TabRow 전환 / ⭐ 토글 / 즐겨찾기 탭 표시 / 채널 편집 다이얼로그

### 알려진 follow-up

- 즐겨찾기 Firestore 동기화 (별도 PR)
- Shorts 제외 필터 (별도 PR — InnerTube 호출 비용 검토 필요)
- Room schema 변화 시 migration 작성

---

## 관련 문서 (이 시점 기준 문서 맵)

| 문서 | 용도 |
|---|---|
| `docs/design.md` | SubFeed 전체 design doc — 첫 라운드(MVP), Status: APPROVED |
| `docs/player-screen-renewal-2026-05-05.md` | 두 번째 design doc — PlayerScreen 전면 리뉴얼, Status: APPROVED |
| `docs/channel-filters-favorites-2026-05-06.md` | 세 번째 design doc — 채널 필터 + 즐겨찾기 + Room DB, Status: APPROVED |
| `docs/development-log.md` (이 문서) | 시간순 구현 / 디버깅 / 단말 검증 로그 |
| `docs/firebase-setup-2026-05-05.md` | Firebase Console 셋업 step-by-step (재현 가이드) |
| `docs/youtube-subscriptions-import-2026-05-05.md` | Google Takeout → JSON converter + import 가이드 |

---

## 향후 정리 항목 (요약)

- **Step 9 — MediaSessionService에 Player 이전**: 백그라운드 재생 옵션 토글이 실제 동작하도록. 현재는 prefs만 갱신, service 자체는 빈 골격.
- **GoogleSignIn → Credential Manager**: Android 권장 API로 마이그레이션. `play-services-auth` 21.2의 GoogleSignIn은 deprecated 경고 5건 출력 중. 동작은 OK.
- **detached scope 잔여 cancel**: `Application` scope 또는 별도 Hilt-injected `ApplicationScope`로 이전. 현재 첫 commit은 성공하나 가끔 후속 commit이 cancel되는 race 잔존.
- **자막/화질/PIP/폴드↔탭 cross-device 실측**: 코드 경로 있음. 실 단말 검증 미완.
- **`google-services.json` 보호**: 현재 `.gitignore`로 충분. CI가 생기면 GitHub Secrets로 주입 필요.
- **`debug.keystore` 통일**: 다중 PC 빌드 시 같은 keystore 사용 또는 각 PC의 SHA-1을 Firebase 콘솔에 누적 등록. 현재는 Windows PC의 keystore만 등록되어 있음.

