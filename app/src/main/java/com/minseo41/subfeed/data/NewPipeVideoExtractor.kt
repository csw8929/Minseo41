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
// - 채널 피드: YouTube RSS
// - 스트림 URL + 자막: YouTube InnerTube API (iOS/ANDROID_VR/TVHTML5 우선)

@Singleton
class NewPipeVideoExtractor @Inject constructor() : VideoExtractor {

    override suspend fun getChannelFeed(channelUrl: String): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val channelId = channelUrl.substringAfterLast("/").substringBefore("?")
            val rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
            val raw = OkHttpDownloader.get(rssUrl, OkHttpDownloader.RSS_HEADERS)
            if (!raw.startsWith("<?xml")) {
                throw IOException("RSS 응답이 XML 이 아님 — channelId=$channelId, head=${raw.take(120)}")
            }
            parseYoutubeRss(raw)
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

            val clientNames = listOf("IOS", "ANDROID_VR", "TVHTML5_EMBED")
            var lastError: Throwable = IllegalStateException("InnerTube 모든 클라이언트 실패 — YouTube 가 PoToken 없는 외부 클라이언트를 차단한 영상")
            attempts.forEachIndexed { idx, (url, body, extraHeaders) ->
                val clientName = clientNames.getOrNull(idx) ?: "UNKNOWN"
                runCatching {
                    val raw = OkHttpDownloader.post(
                        url = url,
                        body = body,
                        headers = extraHeaders + mapOf("Content-Type" to "application/json"),
                    )

                    val json = JSONObject(raw)
                    val playabilityStatus = json.optJSONObject("playabilityStatus")
                    val status = playabilityStatus?.optString("status", "") ?: ""
                    val reason = playabilityStatus?.optString("reason", "") ?: ""
                    val streaming = json.optJSONObject("streamingData")
                        ?: error("streamingData 없음, playabilityStatus=$status reason=$reason raw_head=${raw.take(200)}")

                    val captionTracks = parseCaptionTracks(json.optJSONObject("captions"))
                    val videoDetails = json.optJSONObject("videoDetails")
                    val durationSec = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L
                    val description = videoDetails?.optString("shortDescription", "").orEmpty()
                    Log.d(
                        "SubFeedChapters",
                        "description: length=${description.length}, head=${description.take(200).replace("\n", "\\n")}",
                    )
                    val chapters = parseChapters(description)
                    Log.d("SubFeedChapters", "parsed chapters: count=${chapters.size}")

                    // 1순위: HLS manifest — video+audio 모두 포함
                    val hls = streaming.optString("hlsManifestUrl", "")
                    if (hls.isNotEmpty()) {
                        Log.d("SubFeedStream", "getStreamInfo OK videoId=$videoId client=$clientName type=HLS durationSec=$durationSec urlHead=${hls.take(80)}")
                        return@withContext StreamInfo("hls:$hls", captionTracks, durationSec, chapters)
                    }

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
                        if (bestUrl.isNotEmpty()) {
                            Log.d("SubFeedStream", "getStreamInfo OK videoId=$videoId client=$clientName type=MUXED ${bestHeight}p durationSec=$durationSec urlHead=${bestUrl.take(80)}")
                            return@withContext StreamInfo(bestUrl, captionTracks, durationSec, chapters)
                        }
                    }

                    error("스트림 URL 없음 (streamingData 있으나 재생 불가)")
                }.onFailure {
                    Log.w("SubFeedStream", "getStreamInfo FAIL videoId=$videoId client=$clientName cause=${it.javaClass.simpleName}: ${it.message?.take(200)}")
                    lastError = it
                }
            }
            Log.e("SubFeedStream", "getStreamInfo all-clients-failed videoId=$videoId lastError=${lastError.javaClass.simpleName}: ${lastError.message?.take(200)}")
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

    // description 텍스트에서 챕터 timestamp 추출.
    // 라인이 `[?(HH:)?MM:SS]? <title>` 형태로 시작하고 3개 이상 연속, 단조 증가일 때만 valid.
    // 단일 시각 표기가 본문 안에 흩어진 경우 (예: "그날 01:23 사건") 잘못 잡히지 않도록 보호.
    private fun parseChapters(description: String): List<Chapter> {
        if (description.isBlank()) {
            Log.d("SubFeedChapters", "parseChapters: description blank")
            return emptyList()
        }
        val pattern = Regex("""^\s*\[?(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\]?\s+(\S.*)$""")
        val result = mutableListOf<Chapter>()
        var prevMs = -1L
        var matchedLines = 0
        var totalLines = 0
        for (line in description.lineSequence()) {
            totalLines++
            val m = pattern.find(line) ?: continue
            matchedLines++
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val mm = m.groupValues[2].toIntOrNull() ?: continue
            val s = m.groupValues[3].toIntOrNull() ?: continue
            if (mm >= 60 || s >= 60) {
                Log.d("SubFeedChapters", "  rejected (mm/ss>=60): line=$line")
                continue
            }
            val title = m.groupValues[4].trim()
            if (title.isEmpty()) continue
            val totalMs = ((h * 3600 + mm * 60 + s).toLong()) * 1000L
            Log.d("SubFeedChapters", "  matched: ${totalMs}ms title=$title")
            if (totalMs <= prevMs) {
                Log.d("SubFeedChapters", "  monotonic broken at ${totalMs}ms (prev=$prevMs) → discard all")
                return emptyList()
            }
            prevMs = totalMs
            result.add(Chapter(totalMs, title))
        }
        Log.d("SubFeedChapters", "parseChapters: totalLines=$totalLines, matched=$matchedLines, result.size=${result.size}")
        return if (result.size >= 3) result else emptyList()
    }

    // YouTube Atom RSS 파싱 (channel_id 기반 피드, 채널당 최신 15개)
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
