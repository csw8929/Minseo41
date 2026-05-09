# 재생 불가 영상 메시지의 stale broadcast 깜빡임 수정 (2026-05-09)

## 증상

PR #8 (재생 불가 영상 친화 메시지) 머지 후 발견.

1. 재생 불가 영상 (예: `qLX22WopMuY`) 클릭 → "재생 없음" 메시지 화면.
2. "목록으로" 또는 자동 onBack → Feed 복귀.
3. 다른 (정상 재생 가능한) 영상 클릭 → 새 PlayerScreen 진입.
4. **새 PlayerScreen 에 한 frame 동안 이전 "재생 없음" 메시지가 잔영 → 사라짐**.

## 막다른 가설들 (5번 빗나감)

`PlayerViewModel` 이 Activity scope 라 영상 간 share 되면서 `_uiState.value` 가
이전 영상의 `error` 를 들고 있어, 새 PlayerScreen 의 `collectAsState` capture
시점에 stale value 가 한 frame 그려진다 — 라는 가정으로 다음 5가지 fix 시도.
모두 효과 없거나 부분 효과.

1. `onDispose` 에 `viewModel.resetState()` 추가 — 효과 없음
2. `remember(videoId) { loadVideo }` 를 `collectAsState` 위로 이동 — 부분 효과
3. `onBack` 핸들러 3 군데에 명시적 `viewModel.resetState()` — 효과 없음
4. `MainActivity.kt` 에 `key(activeVideoId) { PlayerScreen(...) }` wrap — 효과 없음
5. `hiltViewModel(key = videoId)` 로 영상별 ViewModel 인스턴스 분리 — 효과 없음

5가지 fix 모두 "Compose state holder 또는 ViewModel 의 stale value" 라는
같은 가정 위에 만들어진 것이라 root cause 를 짚지 못했다. **추측 기반 fix
의 한계** — Iron Law 3-strike 의 위반.

## 진짜 root cause (logcat 직접 분석)

화면 녹화 + logcat 동시 캡처 후 분석. 다음 시퀀스 발견.

```
09:14:23.605  loadVideo qLX22WopMuY              ← 영상 A (차단됨) 클릭
09:14:24.886  onPlayerError #1 (404)              ← 영상 A 의 manifest 404
09:14:26.938  savePosition videoId=""             ← 자동 onBack 의 resetState
09:14:29.141  onPlayerError #2 (404) ⚠️           ← 정체 불명
09:14:29.141  loadVideo hq0XHez2Jn8               ← 영상 B (정상) 시작
09:14:33.881  savePosition positionMs=31558       ← 영상 B 가 31초까지 정상 재생
```

`onPlayerError #2` 가 영상 B 가 정상 재생되는데도 **영상 A 의 onBack 직후**
에 발생. 즉 — `Service` 의 ExoPlayer 인스턴스가 영상 간 share 되는데,
영상 A cleanup 중 backlog 된 error broadcast 가 새 영상 B PlayerScreen 의
`Player.Listener.onPlayerError` 에 **late-broadcast** 되고 있었다.

```
영상 A onBack
  └ controller.stop() / clearMediaItems() — 비동기 dispatch
      └ Service 측 ExoPlayer 가 cleanup 중 backlog 된 error 를 listener 에 broadcast
          └ 이 시점에 listener 는 이미 영상 B PlayerScreen 의 것
              └ 영상 B 의 setPlaybackError() 호출 → uiState.error 박힘
                  └ "재생 없음" 메시지가 한 frame 그려짐
                      └ extractor success 가 error=null 로 reset → 정상 재생
```

이전 5가지 fix 가 모두 빗나간 이유 — Compose state / ViewModel 인스턴스
의 stale 이 아니라, **listener 자체에 들어온 broadcast 가 새 영상의 error
를 set 한 것**. 그 후 정상 재생되면서 사라지는 깜빡임.

## Fix — `streamInfo == null` 가드 (한 곳, 9 줄)

`extractor` 가 끝나 `streamInfo` 가 set 되기 전엔 PlayerScreen 이
`setMediaItem/prepare` 도 호출 안 한 상태. 그 시점의 onPlayerError 는
**구조적으로 stale** 이라 무시하면 됨. 그 후 진짜 prepare error 만 노출.

`PlayerViewModel.kt`:

```kotlin
fun setPlaybackError(message: String) {
    val current = _uiState.value
    if (current.streamInfo == null) {
        Log.d(TAG, "setPlaybackError before streamInfo set — ignored ($message)")
        return
    }
    _uiState.update { it.copy(error = message, isLoading = false) }
}
```

## 결과

- 깜빡임 사라짐.
- 정상 영상의 진짜 prepare error (네트워크 단절 등) 는 그대로 사용자에게 노출.
- 차단 영상의 메시지도 그대로 — `streamInfo` 가 set 된 후 onPlayerError 발생.

## 교훈

- **추측 기반 fix 는 5번 빗나간다**. logcat / 녹화 / 직접 evidence 없이
  코드만 읽고 만든 가설은 root cause 를 짚지 못할 가능성이 높음.
- Iron Law 의 3-strike 룰: 3번 fix 가 빗나가면 architectural 의심으로 가서,
  **evidence 부터 다시 모으는 것**이 정답이다.
- Compose state / ViewModel scope 같은 "내부 state 흐름" 가설보다, ExoPlayer
  같은 **외부 share resource 의 listener broadcast 시퀀스**를 의심해야 했다.

## 영향 받는 파일

- `app/src/main/java/com/minseo41/subfeed/ui/PlayerViewModel.kt` — `setPlaybackError` 에 9줄 가드 추가

## 관련

- PR #8 — 재생 불가 영상 친화 메시지 + 빠른 fail. 그때 도입한 `setPlaybackError`
  에 본 PR 의 가드가 추가됨.
- `docs/unplayable-video-message-2026-05-09.md` — 친화 메시지 도입 배경.
