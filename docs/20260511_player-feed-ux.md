# 플레이어 / 피드 UX 개선

작업일: 2026-05-11
브랜치: `feat/player-feed-ux-2026-05`

## 개요

이번 변경은 크게 두 영역으로 나뉜다:

1. **피드/구독갱신 UX** — 새로고침 버튼 회전 애니메이션, 갱신 결과 메시지, 갱신 로그 보기, "안함" 갱신 간격 옵션
2. **플레이어 UX + 시청 위치 동기화 수정** — Media Session 메타데이터, 비풀스크린 status bar 아이콘 가시성, 영상 끝 자동 복귀, 끝까지 본 영상의 재시청 처리 + 충돌 해소 정책 변경

## 1. 피드 / 구독갱신 UX

### 1.1 새로고침 버튼 회전 애니메이션 (`FeedScreen`, `FeedViewModel`)

- `FeedViewModel.isRefreshing: StateFlow<Boolean>` 추가. `refreshNow()` 호출 시 true로 세팅, WorkManager의 manual work 완료를 관찰하여 false로 복구 (최대 8초 타임아웃).
- `FeedScreen`은 `rememberInfiniteTransition` + `animateFloat`로 0→360 회전을 계속 계산하되, `isRefreshing=true`일 때만 `Modifier.rotate()`로 아이콘에 적용.

### 1.2 갱신 결과 메시지

- `RefreshFeedWorker`가 작업 완료 시 `workDataOf("ch" to channels.size, "ok" to successCount)`로 결과를 반환.
- `RefreshScheduler.observeManualWork()`로 manual work의 `WorkInfo` 흐름을 노출.
- `FeedViewModel`이 완료 결과를 `refreshMessage: StateFlow<String?>`로 노출. `FeedScreen`은 `SnackbarHost`로 표시 ("갱신 완료: N/M개 채널 성공").
- `SettingsViewModel.refreshNow()`도 동일한 흐름으로 `uiState.message`를 갱신.

### 1.3 갱신 로그 보기 (`RefreshPrefs`, `SettingsScreen`)

- `RefreshLogEntry(timestampMs, channelCount, successCount)` 데이터 클래스 도입.
- `RefreshPrefs`가 SharedPreferences에 JSON 배열로 최근 20개 로그를 보관. `saveLog()`/`getLogs()` API.
- `RefreshFeedWorker`가 매 실행 종료 시 `refreshPrefs.saveLog(...)` 호출.
- `SettingsScreen` "구독 갱신" 섹션에 "지금 새로고침" 옆 "로그보기" 버튼. 다이얼로그에 `MM/dd HH:mm | N/M개 채널` 형식으로 표시.

### 1.4 "안함" 갱신 간격 옵션

- `RefreshPrefs.INTERVAL_OPTIONS = listOf(0, 1, 3, 6, 12)` — 0이 "안함".
- `RefreshScheduler.schedulePeriodic()`이 `intervalHours == 0`일 때 `workManager.cancelUniqueWork(PERIODIC_WORK_NAME)`으로 주기 작업 취소.
- `SettingsScreen`은 `hours == 0`이면 라벨 "안함" 표시.

## 2. 플레이어 UX

### 2.1 Media Session 메타데이터 (`PlayerScreen`, `PlayerViewModel`, `VideoDao`)

- `VideoDao.getById(videoId): VideoEntity?` 추가.
- `PlayerUiState`에 `videoTitle`, `channelName`, `thumbnailUrl` 필드 추가. `loadVideo()`에서 DB 조회 후 채움.
- `PlayerScreen`에서 `MediaItem` 빌드 시 `MediaMetadata.Builder()`로 title/artist(채널명)/artworkUri(썸네일)를 설정.
- 결과: 시스템 미디어 알림과 잠금화면 컨트롤에 영상 제목, 채널명, 썸네일이 노출됨.

### 2.2 비풀스크린 status bar 아이콘 가시성 (`PlayerScreen`)

- `MainActivity.enableEdgeToEdge()`로 status bar가 투명해져서, 검정 배경 위에서 어두운 아이콘이 보이지 않던 문제.
- 비풀스크린 진입 시:
  - `controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT` — 풀스크린에서 설정된 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`가 남아 `show()` 후 다시 자동 숨는 현상 차단.
  - `controller.isAppearanceLightStatusBars = false` — 흰색 아이콘으로 전환.
- `onDispose`에서 `isAppearanceLightStatusBars = true`로 복원.

### 2.3 영상 끝 자동 복귀 (`PlayerScreen`)

- `Player.Listener.onPlaybackStateChanged(playbackState)`에서 `playbackState == Player.STATE_ENDED` 시 `onBack()` 호출.

## 3. 시청 위치 동기화 수정 (`SyncRepo`, `PlayerViewModel`)

### 3.1 문제 정의

기존 `SyncRepo.savePosition()`은 "더 큰 positionMs가 이긴다" 충돌 해소를 적용했고, `getPosition()`은 Firestore 값으로 로컬 DB를 무조건 덮어썼다. 이 둘이 결합해 다음 증상 발생:

1. 영상을 끝까지 본 뒤 다시 열어 재시청 도중 back을 누르면, 피드 진행률은 일시적으로 갱신되나(로컬 기반)
2. 같은 영상을 다시 열면 `getPosition`이 Firestore의 묵은 "끝 위치" 값으로 로컬을 덮어써서 재시청 위치가 사라지고
3. near-end 판정에 다시 걸려 0부터 재생됨.

증상은 **signed-in 상태에서만** 발생.

### 3.2 끝에서 5초 이내면 처음부터 재생

`PlayerViewModel.loadVideo()`에서 `savedPosition`이 재생시간의 마지막 5초 안이면 `resumePosition = 0`으로 보정. 저장된 위치 자체는 그대로 보존되어 피드의 progress bar에 활용됨.

```kotlin
val nearEnd = video != null && video.durationSeconds > 0L &&
    savedPosition >= video.durationSeconds * 1000L - 5_000L
val resumePosition = if (nearEnd) 0L else savedPosition
```

### 3.3 충돌 해소 정책 변경: "최신 updatedAt이 이긴다"

`SyncRepo`를 다음과 같이 수정:

- **`savePosition`**: 로컬은 이 기기의 가장 최근 의도이므로 항상 덮어씀. Firestore는 `now > remote.updatedAt`일 때만 쓰기.
- **`getPosition`**: 로컬과 Firestore의 `updatedAt`을 비교해 더 최신 쪽을 반환. 더 최신이 Firestore일 때만 로컬 캐시 갱신.
- **`syncLocalToFirestoreOnSignIn`**: 동일하게 `updatedAt` 기반 비교.

이 변경으로:
- 재시청 중 저장한 작은 positionMs도 양방향(로컬 ↔ Firestore) 정상 반영됨.
- 다음 세션에서도 재시청 위치가 보존됨.

### 3.4 `savePositionNow` / `onPositionChanged`의 0 가드

`PlayerViewModel`에서 `positionMs <= 0L`이면 저장을 skip. 이유: 끝까지 본 영상을 열고 아무것도 안 보고 즉시 닫는 시나리오에서 0이 저장되어 "끝까지 봤다" 표식이 무의미하게 지워지는 것을 방지.

### 3.5 트레이드오프

- 이번 정책 변경의 비용: 사용자가 재시청을 시작해 새 위치가 저장되는 순간 Firestore의 "끝까지 봤다" 표식(피드 100% bar)이 새 위치 표시로 대체됨. 사용자와 합의된 trade-off (의도된 동작).

## 변경된 파일

| 파일 | 변경 내용 |
|---|---|
| `data/SyncRepo.kt` | "최신 updatedAt 이긴다" 충돌 해소로 변경, `resetLocalPosition` 제거 |
| `data/db/VideoDao.kt` | `getById` 추가 |
| `data/refresh/RefreshFeedWorker.kt` | 결과 카운트 + outputData 반환, 로그 저장 |
| `data/refresh/RefreshPrefs.kt` | `RefreshLogEntry` + 로그 저장/조회, INTERVAL_OPTIONS에 0 추가 |
| `data/refresh/RefreshScheduler.kt` | `observeManualWork()`, 0일 때 cancel |
| `ui/FeedScreen.kt` | 회전 애니메이션, Snackbar |
| `ui/FeedViewModel.kt` | `isRefreshing`, `refreshMessage` |
| `ui/PlayerScreen.kt` | MediaMetadata, status bar 가시성, STATE_ENDED → onBack |
| `ui/PlayerViewModel.kt` | 비디오 메타데이터 로딩, near-end 처리, 0 가드 |
| `ui/SettingsScreen.kt` | 로그보기 다이얼로그, "안함" 라벨 |
| `ui/SettingsViewModel.kt` | 갱신 결과 메시지, refreshLogs |

## 테스트 단말

- 탭 (R54Y1003KXN), 플립 (R3CX705W62D)에서 빌드 확인
