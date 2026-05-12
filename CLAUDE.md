# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**SubFeed** — YouTube 구독 채널의 오늘 영상을 광고 없이 재생하고, 여러 Android 기기 간 시청 위치를 동기화하는 개인용 Android 앱.
패키지: `com.minseo41.subfeed` / min SDK 26 / target SDK 36 / Java 17 / Kotlin

## 빌드 및 설치

```bash
# 디버그 APK 빌드
./gradlew assembleDebug

# 릴리즈 APK 빌드
./gradlew assembleRelease

# 연결된 기기에 설치
./gradlew installDebug

# 린트
./gradlew lint

# 특정 기기에 설치 (adb -s 사용)
adb -s R54Y1003KXN install app/build/outputs/apk/debug/app-debug.apk
```

테스트 단말 serial은 상위 workspace CLAUDE.md 참고.

## 화면 캡처

### 폴드 (R3CT70FY0ZP) — 메인 디스플레이 캡처

폴드는 다중 디스플레이라 `screencap`에 display ID를 명시해야 펼친 메인 화면이 캡처됨. 안 그러면 커버 디스플레이가 잡힐 수 있음.

```bash
# 메인(펼친) 디스플레이 ID
adb -s R3CT70FY0ZP shell screencap -d 4630946213010294403 -p /sdcard/screen.png
adb -s R3CT70FY0ZP pull /sdcard/screen.png D:/workspace/png/screen.png
```

스크린샷은 항상 `D:/workspace/png/screen.png` 로 pull 해서 사용 (사용자 표준 경로). 파일을 보려면 Read 툴로 그 경로를 읽으면 이미지가 표시됨.

### 다른 단말 (단일 디스플레이) — `-d` 생략

```bash
adb -s <serial> shell screencap -p /sdcard/screen.png
adb -s <serial> pull /sdcard/screen.png D:/workspace/png/screen.png
```

## 아키텍처

MVVM + Hilt DI. 단일 Activity (`MainActivity`) + Compose Navigation (3개 route).

```
feed  →  player/{videoId}
feed  →  settings
```

### 레이어

| 레이어 | 파일 | 역할 |
|---|---|---|
| UI | `ui/FeedScreen.kt`, `PlayerScreen.kt`, `SettingsScreen.kt` | Compose 화면 |
| ViewModel | `ui/FeedViewModel.kt`, `PlayerViewModel.kt`, `SettingsViewModel.kt` | UiState(StateFlow) 관리 |
| Data | `data/SubscriptionRepo.kt` | 채널 목록(SharedPreferences) + 오늘 영상 병렬 조회 |
| Data | `data/SyncRepo.kt` | Firebase Firestore 시청 위치 읽기/쓰기 |
| Data | `data/VideoExtractor.kt` | 인터페이스 (구현체 교체 지점) |
| Data | `data/NewPipeVideoExtractor.kt` | VideoExtractor 구현체 — YouTube RSS 피드 파싱 + InnerTube API 스트림 URL 추출 |
| DI | `di/AppModule.kt` | Hilt `SingletonComponent` 바인딩 |
| Model | `model/` | `VideoItem`, `SubscribedChannel`, `WatchPosition` |

### 핵심 설계 결정

- **VideoExtractor 인터페이스** — NewPipe Extractor 버전이 바뀌거나 대체 시 `NewPipeVideoExtractor.kt`만 교체하면 됨. 다른 코드 변경 없음.
- **채널 피드**: YouTube RSS (`youtube.com/feeds/videos.xml?channel_id=…`) — API 변경에 영향 없음, 채널당 최신 15개.
- **스트림 URL**: YouTube InnerTube API — iOS → ANDROID_VR → TVHTML5 순서로 fallback. PoToken 불필요.
- **시청 위치 동기화**: Firestore `users/{uid}/positions/{videoId}` — 충돌 시 더 큰 `positionMs` 우선. `PlayerViewModel`에서 30초 debounce 후 저장, 화면 이탈 시 즉시 저장.
- **채널 목록**: SharedPreferences에 `|` 구분자로 직렬화 저장.

## 구독 채널 import 방법

1. Google Takeout → YouTube → `subscriptions.xml` 다운로드
2. 앱 Settings 화면에서 XML 파일 선택 → 파싱 후 중복 제외 추가
3. 또는 Settings에서 채널 URL 직접 입력

## 주요 의존성

- `NewPipeExtractor v0.26.1` (JitPack) — YouTube 스트림 추출 핵심. YouTube 정책 변경 시 깨질 수 있음. 업데이트 필요 시 `libs.versions.toml`의 `newpipe` 버전만 변경.
- `Media3 ExoPlayer 1.5.1` — 동영상 재생. HLS manifest URL (`hls:` 접두사)과 직접 muxed URL 두 가지 처리.
- `Firebase Firestore + Auth` — 기기 간 시청 위치 동기화. Google 로그인 필요.
- `Hilt 2.52` — DI.
- `Coil 2.7.0` — 썸네일 이미지 로딩.

## 알려진 제약

- NewPipe Extractor는 YouTube 업데이트 후 수일~수주 간 동작 불가 사례 있음 (핵심 위험 요소).
- `google-services.json` 파일이 필요하나 VCS에 없음 — Firebase 콘솔에서 별도 발급 필요.
- YouTube ToS 위반 가능성 인지하고 개인 사용 전제로 개발 중.

## TODO

- [ ] **챕터 디버그 로그 제거** — `data/NewPipeVideoExtractor.kt` 의 `Log.d("SubFeedChapters", …)` 호출들 (getStreamInfo 블록 + `parseChapters` 함수 내부). 챕터 기능 안정화 확인 후 삭제. (2026-05-12 추가)

---

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
