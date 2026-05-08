package com.minseo41.subfeed.data

import android.util.Log
import com.minseo41.subfeed.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

// VideoExtractor 구현체.
// - 채널 피드: YouTube RSS — fallback: InnerTube `browse` API (videos tab)
// - 스트림 URL + 자막: YouTube InnerTube API (iOS/ANDROID_VR/TVHTML5 우선)
private const val INNERTUBE_WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
// "Videos" tab 의 magic params (uploaded videos 만 노출, shorts/live 거의 제외). yt-dlp 도 같은 값 사용.
private const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

@Singleton
class NewPipeVideoExtractor @Inject constructor() : VideoExtractor {

    override suspend fun getChannelFeed(channelUrl: String): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val channelId = channelUrl.substringAfterLast("/").substringBefore("?")
            val rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
            // 1차: RSS. YouTube가 RSS endpoint 를 봇 차단한 시점엔 HTML 응답 → 2차로.
            val first = OkHttpDownloader.get(rssUrl, OkHttpDownloader.RSS_HEADERS)
            if (first.startsWith("<?xml")) {
                return@withContext parseYoutubeRss(first)
            }
            // 2차: InnerTube `browse` API (videos tab) — 어제 fbf0156에서 검증된 방식.
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

    override suspend fun getStreamInfo(videoId: String): StreamInfo =
        withContext(Dispatchers.IO) {
            // YouTube InnerTube API — 클라이언트 타입별 API 키가 다름, 순서대로 시도
            val iosKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
            val androidKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"

            val attempts = listOf(
                // 1순위: iOS — HLS manifest 기본 반환, 별도 API 키, PoToken 불필요
                Triple(
                    "https://www.youtube.com/youtubei/v1/player?key=$iosKey&prettyPrint=false",
                    """{"videoId":"$videoId","context":{"client":{"clientName":"IOS","clientVersion":"21.02.3","deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22E252","hl":"ko","gl":"KR"}}}""",
                    mapOf(
                        "User-Agent" to "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                        "X-YouTube-Client-Name" to "5",
                        "X-YouTube-Client-Version" to "21.02.3",
                    )
                ),
                // 2순위: ANDROID_VR (Oculus Quest 1.65.10) — PoToken 불필요
                Triple(
                    "https://www.youtube.com/youtubei/v1/player?key=$androidKey&prettyPrint=false",
                    """{"videoId":"$videoId","context":{"client":{"clientName":"ANDROID_VR","clientVersion":"1.65.10","deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L","platform":"MOBILE","hl":"ko","gl":"KR"}}}""",
                    mapOf(
                        "User-Agent" to "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                        "X-YouTube-Client-Name" to "28",
                        "X-YouTube-Client-Version" to "1.65.10",
                    )
                ),
                // 3순위: TVHTML5 embedded — clientScreen:EMBED 필수
                Triple(
                    "https://www.youtube.com/youtubei/v1/player?key=$androidKey&prettyPrint=false",
                    """{"videoId":"$videoId","context":{"client":{"clientName":"TVHTML5_SIMPLY_EMBEDDED_PLAYER","clientVersion":"2.0","clientScreen":"EMBED","hl":"ko","gl":"KR"},"thirdParty":{"embedUrl":"https://www.youtube.com/"}}}""",
                    mapOf(
                        "User-Agent" to "Mozilla/5.0 (SMART-TV; Linux) AppleWebKit/537.36",
                        "X-YouTube-Client-Name" to "85",
                        "X-YouTube-Client-Version" to "2.0",
                        "Origin" to "https://www.youtube.com",
                        "Referer" to "https://www.youtube.com/",
                    )
                ),
            )

            var lastError: Throwable = IllegalStateException("모든 클라이언트 실패")
            for ((url, body, extraHeaders) in attempts) {
                runCatching {
                    val raw = OkHttpDownloader.post(
                        url = url,
                        body = body,
                        headers = extraHeaders + mapOf("Content-Type" to "application/json"),
                    )

                    val json = JSONObject(raw)
                    val streaming = json.optJSONObject("streamingData")
                        ?: error("streamingData 없음: ${raw.take(300)}")

                    val captionTracks = parseCaptionTracks(json.optJSONObject("captions"))

                    // 1순위: HLS manifest — video+audio 모두 포함
                    val hls = streaming.optString("hlsManifestUrl", "")
                    if (hls.isNotEmpty()) return@withContext StreamInfo("hls:$hls", captionTracks)

                    // 2순위: 최고화질 muxed 스트림 (formats — 보통 최대 720p)
                    val formats = streaming.optJSONArray("formats")
                    if (formats != null) {
                        var bestUrl = ""; var bestHeight = 0
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            val u = f.optString("url", "")
                            val h = f.optInt("height", 0)
                            if (u.isNotEmpty() && h > bestHeight) { bestUrl = u; bestHeight = h }
                        }
                        if (bestUrl.isNotEmpty()) return@withContext StreamInfo(bestUrl, captionTracks)
                    }

                    error("스트림 URL 없음 (streamingData 있으나 재생 불가)")
                }.onFailure { lastError = it }
            }
            throw lastError
        }

    private fun parseCaptionTracks(captions: JSONObject?): List<CaptionTrack> {
        if (captions == null) return emptyList()
        val renderer = captions.optJSONObject("playerCaptionsTracklistRenderer") ?: return emptyList()
        val tracks: JSONArray = renderer.optJSONArray("captionTracks") ?: return emptyList()
        val result = mutableListOf<CaptionTrack>()
        for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            val baseUrl = t.optString("baseUrl", "")
            if (baseUrl.isEmpty()) continue
            val languageCode = t.optString("languageCode", "")
            val nameObj = t.optJSONObject("name")
            val displayName = nameObj?.optString("simpleText")
                ?: nameObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                ?: languageCode
            val isAuto = t.optString("kind", "") == "asr"
            result.add(
                CaptionTrack(
                    languageCode = languageCode,
                    displayName = if (isAuto) "$displayName (자동)" else displayName,
                    baseUrl = baseUrl,
                    isAutoGenerated = isAuto,
                )
            )
        }
        return result
    }

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

    // YouTube Atom RSS 파싱 (channel_id 기반 피드, 채널당 최신 15개) — 1차 path
    private fun parseYoutubeRss(xml: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var videoId = ""
        var title = ""
        var channelName = ""
        var published = ""
        var thumbnailUrl = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "videoId" -> videoId = parser.nextText()
                    "title" -> if (videoId.isNotEmpty() && title.isEmpty()) title = parser.nextText()
                    "name" -> if (channelName.isEmpty()) channelName = parser.nextText()
                    "published" -> published = parser.nextText()
                    "thumbnail" -> {
                        val url = parser.getAttributeValue(null, "url") ?: ""
                        if (url.isNotEmpty()) thumbnailUrl = url
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry" && videoId.isNotEmpty()) {
                    val uploadedAt = runCatching {
                        OffsetDateTime.parse(published).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                    items.add(
                        VideoItem(
                            id = videoId,
                            title = title,
                            channelName = channelName,
                            thumbnailUrl = thumbnailUrl.ifEmpty {
                                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                            },
                            durationSeconds = 0L,
                            uploadedAt = uploadedAt,
                        )
                    )
                    videoId = ""; title = ""; published = ""; thumbnailUrl = ""
                }
            }
            event = parser.next()
        }
        return items
    }
}

// OkHttp 기반 GET/POST helper. (이전엔 NewPipe Extractor의 Downloader subclass였으나
// NewPipe 의존성이 protobuf 충돌을 일으켜 제거됨 — RSS / InnerTube 호출은 이 helper만 쓰면 충분.)
object OkHttpDownloader {
    // YouTube RSS가 봇/EU consent 페이지로 redirect되는 걸 막기 위한 헤더 set.
    // CONSENT=YES+cb 쿠키는 yt-dlp 등에서 사용하는 표준 EU 동의 우회.
    val RSS_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Accept" to "application/atom+xml, application/xml, text/xml, */*;q=0.1",
        "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
        "Cookie" to "CONSENT=YES+cb",
    )

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val client = OkHttpClient.Builder().followRedirects(true).build()
        val request = OkRequest.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> if (v.isNotEmpty()) addHeader(k, v) } }
            .build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: throw IOException("Empty body: $url")
    }

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val client = OkHttpClient.Builder().followRedirects(true).build()
        val requestBody = body.toRequestBody("application/json".toMediaType())
        val request = OkRequest.Builder()
            .url(url)
            .post(requestBody)
            .apply { headers.forEach { (k, v) -> if (v.isNotEmpty()) addHeader(k, v) } }
            .build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: throw IOException("Empty body: $url")
    }
}
