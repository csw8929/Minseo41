# PlayerScreen UI 안정화 + RSS 헤더 적용 + 자막 Compose 렌더 (2026-05-08)

오늘 작업한 fix 들을 정리. 작업 모두 main 이 아닌 별도 브랜치에 분리됨.

## 작업 브랜치

| 브랜치 | 범위 | PR |
|---|---|---|
| `feat/player-stabilize-2026-05` | PlayerScreen UI 안정화 (5건) | 미생성 |
| `fix/rss-headers-2026-05` | RSS 헤더 적용 + 자막 Compose 렌더 + fetcher 스크립트 | [#5](https://github.com/csw8929/Minseo41/pull/5) — 자막 Compose 렌더 추가 후 push 예정 |

`D:\workspace\CLAUDE.md` (워크스페이스 레벨, non-git) 에 폴드 캡처/녹화 recipe 추가. 모든 서브프로젝트에 자동 적용.

---

## 1. 시크바 throttle 무력화 (seek 끊김)

### 증상
영상 재생 중 시크바를 좌우로 드래그하면 매끄럽지 않고 끊기는 느낌. 50ms throttle 코드가 있는데도 효과가 없었음.

### Root cause
`PlayerBottomBar.kt` 의 `Slider.onValueChange` 가 매 픽셀 콜백마다 `onSeekStart()` 호출 → `PlayerScreen` 의 `onSeekStart = { isSeeking = true; lastSeekAtMs = 0L }` 가 매번 `lastSeekAtMs` 를 0 으로 리셋 → throttle 체크 `now - lastSeekAtMs >= 50L` 이 항상 true → **매 픽셀마다 controller.seekTo() 호출** → HLS chunk fetch 폭주.

### Fix
- `PlayerBottomBar` 에서 `onSeekStart` 파라미터 제거 (Slider 가 매 픽셀 호출하므로 의미 없음)
- `PlayerScreen` 의 `onSeek` 안에서 `!isSeeking` 전이로 드래그 시작 감지
- 드래그 시작 시점에만 `lastSeekAtMs = 0L` 리셋

```kotlin
onSeek = { ms ->
    currentPositionMs = ms
    if (!isSeeking) {
        isSeeking = true
        lastSeekAtMs = 0L
    }
    val now = SystemClock.uptimeMillis()
    if (now - lastSeekAtMs >= 50L) {
        lastSeekAtMs = now
        controller.seekTo(ms)
    }
}
```

### 파일
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerScreen.kt`
- `app/src/main/java/com/minseo41/subfeed/ui/player/PlayerBottomBar.kt`

---

## 2. 풀스크린 해제 후 가로 잠김 + 회전 잠금 토글 race

### 증상 (1차)
풀스크린 → 해제 시 `orientationLocked = true` 면 가로로 잠긴 채 풀림. 회전 잠금 토글이 다음 영상 진입 시 settings 값으로 되돌아감.

### Root cause (1차)
- `SCREEN_ORIENTATION_LOCKED` 시맨틱: 호출 시점의 현재 orientation 을 그대로 잠금. 풀스크린 해제 직후 활동은 아직 landscape 라 가로로 잠김.
- `PlayerViewModel.toggleOrientationLocked()` 가 `_uiState` 만 변경하고 SharedPreferences 미반영.

### Fix (1차)
- 풀스크린 해제 시 PORTRAIT 으로 명시 (1차 시도, 2차에서 수정됨)
- `toggleOrientationLocked()` 에서 prefs 갱신 추가

### 증상 (2차 — 1차 수정 직후 사용자 보고)
"가로에서 잠금 토글하면 잠깐 세로로 갔다가 다시 가로로 돌아와서 잠금"

### Root cause (2차)
PORTRAIT 하드코딩이 "현재 상태에서 잠금" 의도를 무시. `lockedOrientation` 캡처 변수로 일반화하면서 `LaunchedEffect` 비동기 캡처 → `DisposableEffect` 가 commit phase 에서 default(PORTRAIT) 로 먼저 적용 → 이후 `LaunchedEffect` 가 LANDSCAPE 캡처해 다시 적용 → 가로→세로→가로 깜빡임 race.

### Fix (2차, 최종)
- `LaunchedEffect` 제거
- `PlayerTopBar` 토글 콜백 안에서 `viewModel.toggleOrientationLocked()` 호출 직전에 동기적으로 `lockedOrientation` 캡처 → `DisposableEffect` 가 정확한 값으로 한 번에 적용
- 초기 `lockedOrientation` 도 mount 시점의 실제 orientation 으로 set

```kotlin
onToggleOrientationLock = {
    if (!uiState.orientationLocked && !uiState.isFullscreen) {
        val o = activity?.resources?.configuration?.orientation
        lockedOrientation = if (o == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    viewModel.toggleOrientationLocked()
}
```

### 파일
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerScreen.kt`
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerViewModel.kt`

---

## 3. A → back → B 진입 시 A 의 seekbar 위치 1~3초 노출

### 증상
A 영상 50% 위치에서 back → B 영상 진입 시, B 의 영상은 로딩되는데 그 사이 1~3초간 seekbar 가 A 의 50% 위치를 그대로 표시.

### Root cause
PlayerScreen 의 `DisposableEffect(mediaController)` 와 polling `LaunchedEffect` 가 **mediaId 일치 여부 체크 없이** controller 상태를 그대로 UI 에 복사. 서비스의 ExoPlayer 는 새 PlayerScreen 이 setMediaItem 호출 전까지 A 의 MediaItem 을 들고 있음.

### Fix
두 곳 모두 `controller.currentMediaItem?.mediaId == videoId` 게이트 추가. 불일치 시 0L 유지.

### 파일
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerScreen.kt:120, :156`

---

## 4. seekbar 썸 11분 → 0 → 11분 점프 (재진입 시 깜빡임)

### 증상
11분에서 멈췄던 영상 다시 진입 시: 왼쪽 시간 라벨은 11:00 표시, seekbar 썸은 0 위치, 약 200~400ms 후 11분 위치로 점프.

### 검증
폴드 메인 디스플레이 녹화 (`adb shell screenrecord --display-id 4630946213010294403`) → ffmpeg frame 추출 (`fps=5,scale=720:-1`) → `z_006.jpg` 와 `z_007.jpg` 에서 정확히 그 상태 캡처:
- 왼쪽: 12:14 (currentPositionMs)
- 오른쪽: 0:00 (durationMs=0)
- 썸: 좌측 박힘
- `z_008.jpg` 부터 정상 (12:14 / 23:17, 썸 12:14 위치)

### Root cause
- `setMediaItem(B, startPos)` 후 controller 가 manifest 재파싱하는 ~200~400ms 동안 `controller.duration` 이 잠깐 0 으로 떨어짐
- 폴링 LaunchedEffect 가 그 0 을 그대로 `durationMs` 에 덮어씀
- `safePosition = currentPositionMs.coerceIn(0L, max(0, 0)) = 0` → 썸 좌측 박힘
- duration 재파싱 끝나면 23:17 로 채워지고 썸이 정확한 위치로 점프

### Fix
- `DisposableEffect(mediaController)` 와 polling `LaunchedEffect` 둘 다 duration 갱신을 `if (d > 0L)` 가드로 보호. 0/unknown 으로 덮어쓰지 않고 마지막 알려진 값 유지.
- 폴링 게이트 분리: position 은 `skipPositionPollUntilMs` 안에 두지만 duration 은 게이트 밖에서 항상 갱신.
- 폴링 간격 500ms → 200ms 로 단축.

### 파일
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerScreen.kt:120, :156`

---

## 5. RSS 헤더 실제 호출에 전달 + 데스크톱 Chrome UA

### 증상
YouTube RSS 가 시간대/IP/지역 따라 막힐 때 fallback 헤더 set 이 정의돼 있는데도 효과 없음.

### Root cause
`OkHttpDownloader.DESKTOP_HEADERS` 가 정의만 되어 있고 `getChannelFeed` 의 RSS GET 호출 (`OkHttpDownloader.get(rssUrl)`) 에 **전달되지 않고 있었음**. 실질 버그.

### Fix
- `DESKTOP_HEADERS` → `RSS_HEADERS` 리네임 (의미 명확화)
- `getChannelFeed` 의 RSS GET 호출에 `RSS_HEADERS` 전달
- UA 를 데스크톱 Chrome 으로 변경 — 기존 Android Mobile UA 보다 통과율 높음 (검증된 값)
- `Accept` / `Accept-Language` 도 yt-dlp 식 표준값으로 정렬 (`*/*;q=0.1` 등)

```kotlin
val RSS_HEADERS: Map<String, String> = mapOf(
    "User-Agent" to
        "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
    "Accept" to "application/atom+xml, application/xml, text/xml, */*;q=0.1",
    "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
    "Cookie" to "CONSENT=YES+cb",
)
```

### 부록: scripts/fetch-youtube-rss.py
RSS 우회 헤더 검증용 standalone Python CLI. 표준 라이브러리만 사용 (`urllib.request`, `requests` 의존성 없음). 단일 채널 또는 channels.json 일괄 fetch 지원.

```bash
# 단일 채널
python scripts/fetch-youtube-rss.py UCxxxxxxxxxxxxxxxxxxxxxx -o feed.xml

# JSON 일괄
python scripts/fetch-youtube-rss.py --json channels.json --out-dir feeds/
```

### 파일
- `app/src/main/java/com/minseo41/subfeed/data/NewPipeVideoExtractor.kt`
- `scripts/fetch-youtube-rss.py` (신규)

---

## 6. 자막 toggle 시 영상 검정 깜빡임 (500ms 지연)

### 증상
자막 켜고 끌 때 약 500ms 지연 + 영상이 검정으로 변했다가 다시 나타남. 캐시되어도 동일.

### Root cause
`PlayerViewModel.selectCaption` 이 `captionSrtUri` state 를 변경 → `PlayerScreen` 의 `LaunchedEffect(mediaController, streamInfo, captionSrtUri)` 트리거 → **MediaItem 전체를 다시 setMediaItem + prepare** → manifest 재파싱 + buffer flush + PlayerView surface 잠깐 비움 → 영상 검정.

캐시여도 ExoPlayer 재준비 비용은 동일.

### Fix
자막을 ExoPlayer 의 `MediaItem.SubtitleConfiguration` 이 아니라 **Compose 에서 직접 렌더링**:

1. `TimedTextToSrt` 에 `Cue(startMs, endMs, text)` 데이터 클래스 + `convertToCues(xml)` 메서드 추가. 기존 SRT 변환 경로 유지.
2. `PlayerViewModel`:
   - `captionSrtUri: Uri?` → `captionCues: List<Cue>` 로 교체
   - `captionUriCache` → `captionCuesCache` (Map<String, List<Cue>>)
   - `selectCaption` 이 SRT 파일을 디스크에 쓰지 않고 List<Cue> 만 메모리 캐시
3. `PlayerScreen`:
   - `LaunchedEffect` 키에서 `captionSrtUri` 제거 → MediaItem 재준비 제거
   - `MediaItem.Builder` 에서 `setSubtitleConfigurations` 호출 제거
   - `PlayerView.subtitleView` 를 GONE 처리 (ExoPlayer 의 자막 렌더 비활성화)
   - 새 Compose 오버레이: `currentPositionMs` (200ms 폴링) 로 active cue 찾아 Text 표시

```kotlin
val activeCue = if (uiState.captionCues.isEmpty()) null
    else uiState.captionCues.firstOrNull {
        currentPositionMs in it.startMs..it.endMs
    }
if (activeCue != null && !uiState.isInPipMode) {
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), Alignment.BottomCenter) {
        Text(
            activeCue.text,
            color = Color.White,
            fontSize = (16f * uiState.selectedCaptionScale).sp,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
```

### 효과
- toggle: state flag 만 변경 → **즉시 반영**
- 영상 검정 깜빡임: setMediaItem 호출 사라져서 **사라짐**
- 첫 로드: SRT 파일 디스크 쓰기 생략 → **첫 로드도 약간 빨라짐**
- 폰트 크기: `selectedCaptionScale` 그대로 적용 (Compose Text fontSize 곱셈)

### 파일
- `app/src/main/java/com/minseo41/subfeed/data/TimedTextToSrt.kt`
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerViewModel.kt`
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerScreen.kt`

---

## 7. 워크스페이스 CLAUDE.md 에 폴드 캡처/녹화 recipe

폴드는 다중 디스플레이라 `screencap` / `screenrecord` 둘 다 display ID 명시 필수 (`-d 4630946213010294403` / `--display-id 4630946213010294403`). 이전엔 Minseo41/CLAUDE.md 에만 있었는데, 워크스페이스 레벨로 옮겨 다른 서브프로젝트도 동일 recipe 사용 가능.

녹화 시 Git Bash 의 경로 변환 회피를 위해 `MSYS_NO_PATHCONV=1` 명시. 중단은 `adb shell pkill -SIGINT screenrecord`.

```bash
adb -s R3CT70FY0ZP shell screencap -d 4630946213010294403 -p /sdcard/screen.png
MSYS_NO_PATHCONV=1 adb -s R3CT70FY0ZP shell 'screenrecord --display-id 4630946213010294403 --bit-rate 8000000 --time-limit 180 /sdcard/rec.mp4'
```

### 파일
- `D:\workspace\CLAUDE.md` (non-git, 워크스페이스 레벨)

---

## 검증 방법

1. **시크바 드래그**: 영상 재생 중 좌우 드래그. 끊김 없이 부드럽게.
2. **풀스크린 회전**: 풀스크린 진입 → 해제. 회전 잠금 ON 시 잠금 시점의 orientation 으로 복구.
3. **회전 잠금 토글**: 가로에서 토글 → 가로 유지 (깜빡임 없음). 세로에서 토글 → 세로 유지.
4. **A→B 진입**: A 50% 시청 → back → B 선택. B 로딩 중 A 의 위치 보이지 않음.
5. **재진입 썸 점프**: 11분 멈췄던 영상 재진입. 썸이 처음부터 11분 위치, 0 으로 점프하지 않음.
6. **RSS 헤더**: `python scripts/fetch-youtube-rss.py UC...` 로 200 OK 응답 확인.
7. **자막 toggle**: 자막 켜고 끄기. 영상 검정 깜빡임 없음. 즉시 반영.

녹화 검증: `D:/workspace/png/rec.mp4` 로 pull → ffmpeg frame 추출 → 핵심 프레임 시각 분석.

---

## Open items / Follow-up

- 자막 위치/스타일 미세 조정 (현재 bottom 96dp, black 60% alpha 배경): 사용자 피드백 받아서 추가 조정 가능.
- captionCues 가 길어지면 (수천 개) 매 프레임 linear scan 하는데, 일반 YouTube 영상 (수백 개) 에서는 무시 가능. 필요 시 binary search 로 최적화.
- 자막 다중 줄 (newline) 처리: 현재 한 줄만 가정. 줄바꿈이 필요한 자막은 추후 확인.
