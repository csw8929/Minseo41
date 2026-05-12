# InnerTube `browse` Fallback 복구 가이드

> 작성: 2026-05-12
> 컨텍스트: 사용자 요청으로 채널 피드 fetch 의 InnerTube fallback 경로를 제거함. RSS 만 시도하도록 단순화. 이 문서만 보고 그대로 되돌릴 수 있도록 기록.

---

## 1. 무엇이 제거되었나

- **대상 파일**: `app/src/main/java/com/minseo41/subfeed/data/NewPipeVideoExtractor.kt`
- 제거된 것:
  - `INNERTUBE_WEB_KEY` 상수
  - `VIDEOS_TAB_PARAMS` 상수 ("Videos" 탭 magic params)
  - `getChannelFeed()` 의 RSS 실패 시 InnerTube `browse` API 호출 분기
  - `parseInnerTubeChannelVideos()` 함수
  - `parseRelativeEnglishTime()` 함수 (`"3 hours ago"` 같은 영문 상대시각 → epoch ms)
  - `android.util.Log` import (InnerTube fallback 진입 로그용으로만 쓰였음)

- 유지된 것:
  - `getStreamInfo()` 의 InnerTube **player** API 호출 (iOS / ANDROID_VR / TVHTML5) — 이건 채널 피드와 무관, 스트림 URL 추출용이므로 그대로 둠.
  - `OkHttpDownloader.post()` — `getStreamInfo()` 가 쓰므로 유지.

---

## 2. 복구 절차 (요약)

NewPipeVideoExtractor.kt 한 파일만 수정하면 됨. 다음 3단계.

### Step 1 — import 추가

파일 상단의 import 블록에 다음을 추가:

```kotlin
import android.util.Log
```

### Step 2 — 상수 추가

`@Singleton` 어노테이션 바로 위(헤더 주석 다음)에 다음 두 상수를 정의:

```kotlin
private const val INNERTUBE_WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
// "Videos" tab 의 magic params (uploaded videos 만 노출, shorts/live 거의 제외). yt-dlp 도 같은 값 사용.
private const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
```

### Step 3 — `getChannelFeed()` 교체 + 파서 2개 추가

`getChannelFeed()` 전체를 다음으로 교체:

```kotlin
override suspend fun getChannelFeed(channelUrl: String): List<VideoItem> =
    withContext(Dispatchers.IO) {
        val channelId = channelUrl.substringAfterLast("/").substringBefore("?")
        val rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        // 1차: RSS. YouTube가 RSS endpoint 를 봇 차단한 시점엔 HTML 응답 → 2차로.
        val first = OkHttpDownloader.get(rssUrl, OkHttpDownloader.RSS_HEADERS)
        if (first.startsWith("<?xml")) {
            return@withContext parseYoutubeRss(first)
        }
        // 2차: InnerTube `browse` API (videos tab) — fbf0156에서 검증된 방식.
        Log.d("SubFeedExtractor", "RSS blocked, falling back to InnerTube browse — channelId=$channelId")
        val raw = OkHttpDownloader.post(
            url = "https://www.youtube.com/youtubei/v1/browse?key=$INNERTUBE_WEB_KEY&prettyPrint=false",
            body = """{"browseId":"$channelId","params":"$VIDEOS_TAB_PARAMS","context":{"client":{"clientName":"WEB","clientVersion":"2.20250101.00.00","hl":"en","gl":"US"}}}""",
            headers = mapOf(
                "Content-Type" to "application/json",
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to "2.20250101.00.00",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
            ),
        )
        parseInnerTubeChannelVideos(raw)
    }
```

그리고 `parseYoutubeRss()` 함수 **바로 위**에 다음 두 private 함수를 삽입:

```kotlin
// InnerTube `browse` (videos tab) 응답에서 영상 목록 추출.
// 응답 구조: contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer.content.richGridRenderer.contents[].richItemRenderer.content.videoRenderer
private fun parseInnerTubeChannelVideos(raw: String): List<VideoItem> {
    val data = JSONObject(raw)
    val tabs = data.optJSONObject("contents")
        ?.optJSONObject("twoColumnBrowseResultsRenderer")
        ?.optJSONArray("tabs") ?: return emptyList()

    var grid: JSONArray? = null
    for (i in 0 until tabs.length()) {
        val tabRenderer = tabs.getJSONObject(i).optJSONObject("tabRenderer") ?: continue
        if (tabRenderer.optBoolean("selected")) {
            grid = tabRenderer.optJSONObject("content")
                ?.optJSONObject("richGridRenderer")
                ?.optJSONArray("contents")
            break
        }
    }
    grid ?: return emptyList()

    val items = mutableListOf<VideoItem>()
    val now = System.currentTimeMillis()
    for (i in 0 until grid.length()) {
        val rich = grid.optJSONObject(i)?.optJSONObject("richItemRenderer") ?: continue
        val v = rich.optJSONObject("content")?.optJSONObject("videoRenderer") ?: continue
        val videoId = v.optString("videoId", "")
        if (videoId.isEmpty()) continue
        val title = v.optJSONObject("title")?.let { titleObj ->
            titleObj.optString("simpleText").ifEmpty {
                titleObj.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
            }
        } ?: ""
        val channelName = v.optJSONObject("ownerText")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text") ?: ""
        val publishedText = v.optJSONObject("publishedTimeText")?.optString("simpleText") ?: ""
        val uploadedAt = parseRelativeEnglishTime(publishedText, now)
        val thumb = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { ts ->
            if (ts.length() == 0) "" else ts.getJSONObject(ts.length() - 1).optString("url", "")
        } ?: ""
        items.add(
            VideoItem(
                id = videoId,
                title = title,
                channelName = channelName,
                thumbnailUrl = thumb.ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                durationSeconds = 0L,
                uploadedAt = uploadedAt,
            )
        )
    }
    return items
}

// "1 day ago", "3 hours ago", "7h ago", "1d ago", "2w ago" 등 영문 상대시각 → 절대 epoch ms.
// YouTube가 풀 표기와 축약 표기를 섞어 응답하므로 둘 다 처리.
// 라이브/예정 영상엔 publishedTimeText 가 없거나 매치 실패 → 0 반환 → cutoff 필터에서 제외됨.
private fun parseRelativeEnglishTime(text: String, now: Long): Long {
    if (text.isBlank()) return 0L
    val match = Regex("""(\d+)\s*([a-z]+)\s+ago""", RegexOption.IGNORE_CASE)
        .find(text) ?: return 0L
    val n = match.groupValues[1].toLong()
    val unit = match.groupValues[2].lowercase()
    val deltaMs = when (unit) {
        "s", "sec", "secs", "second", "seconds" -> n * 1_000L
        "m", "min", "mins", "minute", "minutes" -> n * 60_000L
        "h", "hr", "hrs", "hour", "hours" -> n * 3_600_000L
        "d", "day", "days" -> n * 86_400_000L
        "w", "wk", "wks", "week", "weeks" -> n * 7L * 86_400_000L
        "mo", "mos", "month", "months" -> n * 30L * 86_400_000L
        "y", "yr", "yrs", "year", "years" -> n * 365L * 86_400_000L
        else -> 0L
    }
    return now - deltaMs
}
```

### Step 4 — RSS 실패 시 throw 제거

복구 후에는 RSS 가 XML 이 아니면 그대로 fallback 으로 흘러야 하므로, `getChannelFeed()` 내부의 `throw IOException(...)` 분기는 **자동으로 사라짐** (Step 3 에서 통째로 교체했기 때문). 별도 처리 불필요.

### Step 5 — 헤더 주석 갱신

파일 상단 주석을 다음으로:

```kotlin
// VideoExtractor 구현체.
// - 채널 피드: YouTube RSS — fallback: InnerTube `browse` API (videos tab)
// - 스트림 URL + 자막: YouTube InnerTube API (iOS/ANDROID_VR/TVHTML5 우선)
```

---

## 3. 검증 절차

1. `./gradlew assembleDebug` — 빌드 통과 확인.
2. 단말 설치 후 Settings → 지금 새로고침.
3. logcat 으로 fallback 진입 확인:
   ```bash
   adb logcat -v time -s SubFeedExtractor SubFeedRefreshWorker
   ```
   - 정상: `RSS blocked, falling back to InnerTube browse — channelId=UCxxx`
   - 또는 RSS 가 정상 동작 중이면 fallback 로그 없이 통과.

---

## 4. 복구 판단 기준 — 언제 다시 넣을지

다음 조건이 모두 충족될 때:
- RSS endpoint (`youtube.com/feeds/videos.xml?channel_id=...`) 가 **수일 이상 지속적으로** HTTP 404 또는 HTML 응답으로 답함.
- Settings → 로그보기 에서 `0/N개 채널` 갱신이 반복적으로 기록됨.
- 직접 curl 로도 RSS 가 비-XML 응답을 주는 게 확인됨:
  ```bash
  curl -A "Mozilla/5.0" -I "https://www.youtube.com/feeds/videos.xml?channel_id=UC_x5XG1OV2P6uZZ5FSM9Ttw"
  ```

자세한 RSS outage 분석 + 다른 대안(A/B/C 접근법)은 `docs/youtube-rss-outage-analysis-2026-05-08.md` 참고.

---

## 5. 참고 — 검증된 InnerTube 응답 구조

`browse` API 응답 JSON 의 핵심 경로 (2026-05 기준):

```
data.contents
  └─ twoColumnBrowseResultsRenderer
       └─ tabs[]                          // "Home" / "Videos" / "Shorts" / "Live" / "Playlists" / "Community" / "About"
            └─ tabRenderer
                 ├─ selected: Boolean     // VIDEOS_TAB_PARAMS 보내면 "Videos" 탭이 selected=true
                 └─ content
                      └─ richGridRenderer
                           └─ contents[]
                                └─ richItemRenderer
                                     └─ content
                                          └─ videoRenderer
                                               ├─ videoId
                                               ├─ title.simpleText | title.runs[0].text
                                               ├─ ownerText.runs[0].text
                                               ├─ publishedTimeText.simpleText   // "3 hours ago"
                                               └─ thumbnail.thumbnails[]         // 마지막이 최고 해상도
```

`continuationItemRenderer` (페이지네이션) 은 무시 — 첫 페이지 (~30개) 만으로 충분.
