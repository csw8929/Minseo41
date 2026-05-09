# 재생 불가 영상에 대한 친화 메시지 + 빠른 fail (2026-05-09)

## 배경

특정 YouTube 영상 (예: `qLX22WopMuY` — MOCAR 채널 "중국 스트리머...")
이 SubFeed 에서 재생되지 않고 ExoPlayer 의 raw 에러 텍스트가 잠깐 보였다
사라지는 증상.

### 원인 진단

1. InnerTube 4개 클라이언트 호출 결과
   - **IOS 21.02.3** — `playabilityStatus: OK` 받고 `hlsManifestUrl` 도 받음.
     **그러나** master manifest 안의 17개 video child playlist + 2개 audio
     child playlist 가 **전부 404**.
   - **ANDROID_VR 1.65.10 / 1.69** — `LOGIN_REQUIRED`
   - **TVHTML5** — `ERROR — YouTube 를 더 이상 지원하지 않습니다`
   - **MWEB / WEB** — `UNPLAYABLE — The page needs to be reloaded` (PoToken 미보유 검증 메시지)

   동일 코드로 다른 영상 (Rick Astley `dQw4w9WgXcQ`, "Me at the zoo"
   `jNQXAC9IVRw`) 은 master/child 모두 200. 즉 추출기/플레이어는 정상이고,
   YouTube 가 PoToken / visitorData 검증을 통과 못한 외부 클라이언트의 stream
   URL 을 영상 단위로 stricter 차단 중.

2. ExoPlayer 가 prepare 단계에서 master manifest 안의 모든 variant 를 차례로
   fallback 시도하면서 5–7초 소요. 사용자 입장에서 화면이 멈춘 듯 보임.

## 변경 내용

### 1. 사용자 친화 메시지 + "목록으로" 버튼

ExoPlayer raw 에러 대신 errorCode 기반으로 메시지 매핑.

`PlayerScreen.kt` — `friendlyPlaybackMessage(PlaybackException)`:
- `ERROR_CODE_IO_BAD_HTTP_STATUS` / `ERROR_CODE_PARSING_*` → "이 영상은 재생할 수 없습니다.\n(YouTube 외부 재생 차단)"
- `ERROR_CODE_IO_NETWORK_*` / `ERROR_CODE_IO_UNSPECIFIED` → "네트워크 오류입니다."
- 그 외 → "재생 오류 (errorCodeName)"

`PlayerViewModel.kt` — extractor 단계에서 모든 InnerTube 클라이언트가 fail 시
같은 메시지 표시 (`friendlyExtractorMessage`).

UI:
- 에러 발생 시 PlayerControls / DoubleTapSkipOverlay 가림 (메시지·버튼이
  컨트롤과 겹치지 않도록).
- 메시지 + "목록으로" 버튼 (Column).
- onPlayerError 시 controller.stop + clearMediaItems 으로 재시도 루프 차단.

### 2. 빠른 fail — `LoadErrorHandlingPolicy` customize

`SubFeedMediaSessionService.kt` — `ExoPlayer.Builder` 에 `DefaultMediaSourceFactory`
+ custom `LoadErrorHandlingPolicy` 주입.

```kotlin
private fun isPermanentClientError(ex: Throwable?): Boolean =
    ex is HttpDataSource.InvalidResponseCodeException &&
        ex.responseCode in setOf(401, 403, 404)

override fun getRetryDelayMsFor(...) =
    if (isPermanentClientError(...)) C.TIME_UNSET else super...

override fun getFallbackSelectionFor(...) =
    if (isPermanentClientError(...)) null else super...
```

#### 정책 선택 근거

| 응답 | 정책 | 이유 |
|---|---|---|
| 401 / 403 / 404 | retry / fallback 모두 끔, 즉시 fail | googlevideo URL 의 IP/expire/PoToken 검증 실패. 같은 manifest 의 다른 variant 도 같은 검증으로 동일 실패 → fallback 가치 없음 |
| 429 (rate limit) | default 정책 유지 | 잠깐 backoff 후 retry 가 정답. 막으면 일시적 throttle 에 죽음 |
| 5xx | default 정책 유지 | 서버 일시 장애 → 다른 CDN 노드 fallback 가치 있음 |
| 네트워크 timeout / IO | default 정책 유지 | 일시적 hiccup |

### 3. NewPipeVideoExtractor — 모든 클라이언트 fail 시 메시지

`var lastError: Throwable = IllegalStateException("InnerTube 모든 클라이언트 실패 — YouTube 가 PoToken 없는 외부 클라이언트를 차단한 영상")`

ViewModel onFailure 에서 친화 메시지로 변환됨.

## 효과

- **재생 불가 영상**: `prepare → onPlayerError` 가 5.3초 → 0.5초 미만으로 단축.
  사용자 체감 1~2초 안에 메시지 노출.
- **정상 영상**: 일시적 segment 오류·5xx·429 에 대한 retry/fallback 정책은
  그대로라 영향 없음.
- UI 가 컨트롤과 겹치지 않고 메시지 + "목록으로" 단일 흐름.

## 진단 방법 (재현 / 추후 디버깅용)

PC 에서 직접 InnerTube 호출해서 영상별로 막혔는지 확인:

```bash
# IOS 클라이언트로 hlsManifestUrl 받기
curl -sSk --ssl-no-revoke -X POST \
  'https://www.youtube.com/youtubei/v1/player?key=AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc&prettyPrint=false' \
  -H 'Content-Type: application/json' \
  -H 'User-Agent: com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)' \
  -H 'X-YouTube-Client-Name: 5' \
  -H 'X-YouTube-Client-Version: 21.02.3' \
  --data-raw '{"videoId":"<VIDEO_ID>","context":{"client":{"clientName":"IOS","clientVersion":"21.02.3","deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22E252","hl":"ko","gl":"KR"}}}' \
  -o resp.json -w 'HTTP %{http_code}\n'

# master manifest fetch 후 child playlist URL 한 개의 status 확인
HLS=$(python -c "import json; print(json.load(open('resp.json'))['streamingData']['hlsManifestUrl'])")
curl -sSk --ssl-no-revoke "$HLS" -o master.m3u8 -w 'master HTTP %{http_code}\n'
curl -sSk --ssl-no-revoke -o /dev/null "$(grep '^https' master.m3u8 | head -1)" -w 'child HTTP %{http_code}\n'
```

`master HTTP 200 / child HTTP 404` 면 본 문서의 케이스.

## 영향 받는 파일

- `app/src/main/java/com/minseo41/subfeed/service/SubFeedMediaSessionService.kt` — `LoadErrorHandlingPolicy` 추가
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerViewModel.kt` — `setPlaybackError`, `friendlyExtractorMessage`
- `app/src/main/java/com/minseo41/subfeed/ui/PlayerScreen.kt` — `friendlyPlaybackMessage`, 에러 UI Column + "목록으로" 버튼, 컨트롤 가림
- `app/src/main/java/com/minseo41/subfeed/data/NewPipeVideoExtractor.kt` — fallback 종료 시 메시지

## 한계 / 후속

- PoToken 통합으로 차단된 영상도 재생하려면 BgUtils 같은 JS 엔진 임베드가
  필요. 작업량 큼.
- 추후 클라이언트 list 갱신 (예: 새 ANDROID_VR 빌드, IOS_MUSIC) 으로
  부분 회피 가능하나 단기 미봉책.
