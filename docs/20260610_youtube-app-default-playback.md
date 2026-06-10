# YouTube 앱 기본 재생 전환 (v1.3.0.0)

## 배경

NewPipe Extractor 기반 인앱 플레이어가 YouTube 정책 변경으로 주기적으로 동작 불가 상태가 됨. 재생 정보 추출 실패 → fallback 반복 → 결국 영상 재생 불가 상황이 자주 발생. 광고가 있어도 안정적 시청이 더 낫다는 판단 하에 기본 재생 경로를 YouTube 앱 위임으로 전환함.

## 설계 원칙

- **피드(구독 목록)는 유지**: YouTube RSS feed는 NewPipe 무관한 공식 endpoint → 피드 수집 경로 무변경
- **두 방향 도어**: 설정 토글로 인앱 플레이어 복귀 가능. 인앱 경로(ExoPlayer + NewPipe) 코드 무변경
- **재생 방식은 설정에서 1회 지정**: 영상마다 묻지 않음

## 구현 내용

### 1. 재생 모드 토글 (PlayerPrefs)

`PlayerPrefs`에 상수 추가:
- `KEY_PLAYBACK_MODE = "playbackMode"`
- `PLAYBACK_MODE_YOUTUBE = "youtube"` (기본값)
- `PLAYBACK_MODE_INAPP = "inapp"`

### 2. 설정 화면 UI

`SettingsScreen` 재생 기본값 섹션 상단에 `PlaybackModeRow` 추가. `FilterChip` 두 개 (YouTube 앱 / 인앱 플레이어). `SettingsViewModel`에 `playbackMode` 상태 + `setPlaybackMode()` 추가.

### 3. 피드 영상 탭 분기 (FeedScreen)

영상 탭 시 SharedPreferences에서 모드를 즉시 읽어 분기:

- **YouTube 앱 모드**: `ACTION_VIEW + youtube.com/watch?v={id}` Intent 발사. YouTube 앱이 없으면 브라우저로 폴백 (Android 시스템 처리). `ActivityNotFoundException` 시 Toast 표시. 동시에 `viewModel.recordExternalLaunch(videoId)` 호출.
- **인앱 플레이어 모드**: 기존 `playerVideoId` 설정 경로 유지.

### 4. 외부 실행 기록 (SyncRepo + FeedViewModel)

`SyncRepo.recordExternalLaunchDetached(videoId)`:
- Firestore `users/{uid}/positions/{videoId}`에 `{launchedAt: Timestamp}` **merge** 기록
- `positionMs`는 절대 건드리지 않음 (기존 인앱 시청 데이터 보존)
- uid=null(미로그인) 시 skip

`FeedViewModel.recordExternalLaunch(videoId)`:
- `videoDao.markRead(videoId)` 로컬 청점 처리
- `syncRepo.recordExternalLaunchDetached(videoId)` 호출

### 5. 기기 간 시청 상태 동기화 (FeedViewModel.bulkSyncRemoteWatched)

수동 갱신(`refreshNow()`) 완료 후 실행:
- 현재 피드의 영상 ID 목록을 Firestore `positions` 컬렉션에 `whereIn(FieldPath.documentId(), chunk_of_30)` 질의
- `launchedAt` 또는 `positionMs > 0`인 항목 → 로컬 `videoDao.markRead()` 적용
- 이로써 다른 기기에서 시청한 영상의 파란 점(미시청 표시)이 갱신 후 사라짐

## 확인 리스트

1. 피드에서 영상 탭 → YouTube 앱이 해당 영상으로 열리는지
2. 열린 영상의 파란 점(미시청 표시)이 피드에서 즉시 사라지는지
3. 다른 기기에서 수동 갱신 후 같은 영상의 파란 점이 사라지는지
4. 기존에 인앱으로 시청 중이던 영상(positionMs 있음)을 외부 실행해도 Firestore positionMs 유지되는지
5. 설정 → 인앱 플레이어로 변경 → 영상 탭 → PlayerScreen이 정상 동작하는지 (진행 바 포함)
6. YouTube 앱 비활성화 후 영상 탭 → 브라우저가 열리는지 (ActivityNotFoundException toast 또는 브라우저 폴백)

## 주의사항

- `SyncRepo.savePosition`은 아직 non-merge `set()` — 인앱 재생 시 Firestore에서 `launchedAt` 필드가 제거될 수 있으나 무해함 (`positionMs`로 시청 여부 판단 가능). `&t=` 딥링크 기능 추가 시 merge 전환 필요.
- 외부 실행 후 앱으로 돌아왔을 때 빨간 진행 바(watchFraction)는 나타나지 않음 — `watchFraction`은 로컬 `positionMs` 기반이므로 외부 재생분은 반영 불가.

## 버전

1.2.1.0 → 1.3.0.0 (minor bump: 기능 추가)
