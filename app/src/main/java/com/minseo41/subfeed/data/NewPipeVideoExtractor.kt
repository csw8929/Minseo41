package com.minseo41.subfeed.data

import android.util.Base64
import android.util.Log
import com.minseo41.subfeed.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

// VideoExtractor 구현체.
// - 채널 피드: YouTube RSS 직접 파싱 (NewPipe 보다 가볍고 안정적)
// - 스트림 URL + 자막 + 챕터: NewPipeExtractor (PoToken/n-param/sig 모두 처리)
//   PoTokenProvider 는 SubFeedApp.onCreate() 에서 YoutubeStreamExtractor.setPoTokenProvider() 로 주입됨.
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
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val service = ServiceList.YouTube
            val linkHandler = service.streamLHFactory.fromUrl(watchUrl)
            val extractor = service.getStreamExtractor(linkHandler) as YoutubeStreamExtractor

            // PoToken 발급 + InnerTube 요청 + base.js 파싱 + n-param/sig deobfuscate
            // 일반적으로 첫 호출은 5~10초 (WebView 워밍업 + base.js 다운로드), 이후는 ms 단위.
            extractor.fetchPage()

            val durationSec = extractor.length
            val description = runCatching { extractor.description?.content.orEmpty() }.getOrDefault("")
            val chapters = parseChapters(description)

            // 1순위: DASH manifest URL — n-deobfuscated chunk URL 포함, ExoPlayer 가 직접 fetch.
            val dashUrl = runCatching { extractor.dashMpdUrl }.getOrNull().orEmpty()
            if (dashUrl.isNotEmpty()) {
                Log.d(TAG, "getStreamInfo OK videoId=$videoId type=DASH durationSec=$durationSec urlHead=${dashUrl.take(80)}")
                return@withContext StreamInfo(
                    streamUrl = "dash:$dashUrl",
                    captionTracks = collectCaptions(extractor),
                    durationSeconds = durationSec.toLong(),
                    chapters = chapters,
                )
            }

            // 2순위: HLS manifest (라이브/일부 영상)
            val hlsUrl = runCatching { extractor.hlsUrl }.getOrNull().orEmpty()
            if (hlsUrl.isNotEmpty()) {
                Log.d(TAG, "getStreamInfo OK videoId=$videoId type=HLS durationSec=$durationSec urlHead=${hlsUrl.take(80)}")
                return@withContext StreamInfo(
                    streamUrl = "hls:$hlsUrl",
                    captionTracks = collectCaptions(extractor),
                    durationSeconds = durationSec.toLong(),
                    chapters = chapters,
                )
            }

            // 3순위: videoOnlyStreams + audioStreams 로 inline DASH 빌드 (고화질 1080p+)
            // videoStreams (muxed) 는 보통 360~720p 한계라 마지막 fallback 으로 밀어둠.
            val videoOnly = runCatching { extractor.videoOnlyStreams }.getOrDefault(emptyList())
                .filter { it.content.isNotEmpty() && it.itagItem != null }
            val audioStreams = runCatching { extractor.audioStreams }.getOrDefault(emptyList())
                .filter { it.content.isNotEmpty() && it.itagItem != null }
            if (videoOnly.isNotEmpty() && audioStreams.isNotEmpty()) {
                val mpdB64 = buildDashFromNewPipeStreams(videoOnly, audioStreams, durationSec.toLong())
                if (mpdB64 != null) {
                    Log.d(TAG, "getStreamInfo OK videoId=$videoId type=DASH-INLINE video=${videoOnly.size} audio=${audioStreams.size} durationSec=$durationSec")
                    return@withContext StreamInfo(
                        streamUrl = "dash:data:application/dash+xml;base64,$mpdB64",
                        captionTracks = collectCaptions(extractor),
                        durationSeconds = durationSec.toLong(),
                        chapters = chapters,
                    )
                }
            }

            // 4순위: 최고 해상도 video stream (muxed, 보통 360~720p) — last resort
            val videoStream = runCatching { extractor.videoStreams }.getOrDefault(emptyList())
                .maxByOrNull { it.height }
            if (videoStream != null && videoStream.content.isNotEmpty()) {
                Log.d(TAG, "getStreamInfo OK videoId=$videoId type=MUXED ${videoStream.height}p urlHead=${videoStream.content.take(80)}")
                return@withContext StreamInfo(
                    streamUrl = videoStream.content,
                    captionTracks = collectCaptions(extractor),
                    durationSeconds = durationSec.toLong(),
                    chapters = chapters,
                )
            }

            error("재생 가능한 stream URL 없음 — DASH/HLS/INLINE-DASH/MUXED 모두 비어 있음")
        }

    // videoOnly + audio streams 들을 받아 inline DASH MPD 를 만들고 base64 반환.
    // NewPipe 가 URL 의 n-param/sig 를 이미 deobfuscate 했으므로 그대로 BaseURL 에 박으면 됨.
    // 각 video 트랙을 별도 Representation 으로 노출 → ExoPlayer 의 ABR + 우리 QualityMenu 가 선택 가능.
    // 오디오는 언어별 AdaptationSet 분리 (한국어 더빙 등 지원).
    private fun buildDashFromNewPipeStreams(
        videoStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        durationSec: Long,
    ): String? {
        // 1080p 이하만 (현재 codec/decoder 호환성 안전). 정렬은 height 오름차순.
        val videos = videoStreams
            .filter { it.height in 1..1080 && hasRanges(it.itagItem!!) }
            .sortedBy { it.height }
        if (videos.isEmpty()) return null

        // 언어별로 best bitrate 1개씩만. audioTrackId/audioLocale 없으면 단일 트랙.
        data class BestAudio(val stream: AudioStream, val bitrate: Int)
        val audioByLang = linkedMapOf<String?, BestAudio>()
        for (a in audioStreams) {
            if (a.itagItem == null || !hasRanges(a.itagItem!!)) continue
            val lang = a.audioLocale?.language ?: a.audioTrackId?.split(".")?.firstOrNull { it.length in 2..3 && it.all { c -> c.isLetter() } }
            val bw = a.averageBitrate.takeIf { it > 0 } ?: (a.itagItem!!.avgBitrate.takeIf { it > 0 } ?: 128000)
            val prev = audioByLang[lang]
            if (prev == null || bw > prev.bitrate) audioByLang[lang] = BestAudio(a, bw)
        }
        if (audioByLang.isEmpty()) return null

        fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun segBase(initStart: Int, initEnd: Int, idxStart: Int, idxEnd: Int): String =
            """<SegmentBase indexRange="$idxStart-$idxEnd"><Initialization range="$initStart-$initEnd"/></SegmentBase>"""

        val dur = if (durationSec > 0) "PT${durationSec}S" else "PT0S"
        val mpd = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" minBufferTime="PT1.5S" type="static" """)
            append("""mediaPresentationDuration="$dur" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">""")
            append("""<Period duration="$dur">""")

            // 비디오 AdaptationSet — 같은 codec/mimeType 가정 (대부분 avc1/mp4 또는 vp9/webm)
            append("""<AdaptationSet id="1" contentType="video" mimeType="${videos[0].format?.mimeType.orEmpty().xmlEscape()}">""")
            videos.forEachIndexed { i, v ->
                val item = v.itagItem!!
                val bandwidth = item.bitrate.takeIf { it > 0 } ?: item.avgBitrate.takeIf { it > 0 } ?: 1_000_000
                append("""<Representation id="v$i" codecs="${v.codec.orEmpty().xmlEscape()}" """)
                append("""width="${v.width}" height="${v.height}" """)
                append("""bandwidth="$bandwidth" """)
                if (v.fps > 0) append("""frameRate="${v.fps}" """)
                append(">")
                append("<BaseURL>${v.content.xmlEscape()}</BaseURL>")
                append(segBase(item.getInitStart(), item.getInitEnd(), item.getIndexStart(), item.getIndexEnd()))
                append("</Representation>")
            }
            append("</AdaptationSet>")

            // 오디오 — 언어별 AdaptationSet
            audioByLang.entries.forEachIndexed { idx, (lang, best) ->
                val a = best.stream
                val item = a.itagItem!!
                val langAttr = if (lang != null) """ lang="${lang.xmlEscape()}"""" else ""
                val sampleRate = item.sampleRate.takeIf { it > 0 } ?: 44100
                append("""<AdaptationSet id="${2 + idx}" contentType="audio" mimeType="${a.format?.mimeType.orEmpty().xmlEscape()}"$langAttr>""")
                append("""<Representation id="a$idx" codecs="${a.codec.orEmpty().xmlEscape()}" """)
                append("""bandwidth="${best.bitrate}" audioSamplingRate="$sampleRate">""")
                append("<BaseURL>${a.content.xmlEscape()}</BaseURL>")
                append(segBase(item.getInitStart(), item.getInitEnd(), item.getIndexStart(), item.getIndexEnd()))
                append("</Representation></AdaptationSet>")
            }
            append("</Period></MPD>")
        }
        return Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun hasRanges(item: org.schabi.newpipe.extractor.services.youtube.ItagItem): Boolean =
        item.getInitEnd() > 0 && item.getIndexEnd() > 0

    private fun collectCaptions(extractor: YoutubeStreamExtractor): List<CaptionTrack> {
        val subtitles = runCatching { extractor.subtitlesDefault }.getOrDefault(emptyList())
        return subtitles.map { sub ->
            val lang = sub.languageTag.orEmpty()
            CaptionTrack(
                languageCode = lang,
                displayName = lang,
                baseUrl = sub.content,
                isAutoGenerated = sub.isAutoGenerated,
            )
        }
    }

    // description 텍스트에서 챕터 timestamp 추출.
    // 라인이 `[?(HH:)?MM:SS]? <title>` 형태로 시작하고 3개 이상 연속, 단조 증가일 때만 valid.
    private fun parseChapters(description: String): List<Chapter> {
        if (description.isBlank()) return emptyList()
        val pattern = Regex("""^\s*\[?(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\]?\s+(\S.*)$""")
        val result = mutableListOf<Chapter>()
        var prevMs = -1L
        for (line in description.lineSequence()) {
            val m = pattern.find(line) ?: continue
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val mm = m.groupValues[2].toIntOrNull() ?: continue
            val s = m.groupValues[3].toIntOrNull() ?: continue
            if (mm >= 60 || s >= 60) continue
            val title = m.groupValues[4].trim()
            if (title.isEmpty()) continue
            val totalMs = ((h * 3600 + mm * 60 + s).toLong()) * 1000L
            if (totalMs < prevMs) return emptyList()
            if (totalMs == prevMs) continue
            prevMs = totalMs
            result.add(Chapter(totalMs, title))
        }
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

    companion object {
        private const val TAG = "SubFeedStream"
    }
}

// OkHttp 기반 GET/POST helper. NewPipeExtractor 가 들어왔지만 RSS 직접 파싱 + PoToken WebView 의
// BotGuard 서비스 호출 path 에서 계속 사용한다.
object OkHttpDownloader {
    // YouTube RSS가 봇/EU consent 페이지로 redirect되는 걸 막기 위한 헤더 set.
    val RSS_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Accept" to "application/atom+xml, application/xml, text/xml, */*;q=0.1",
        "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
        "Cookie" to "CONSENT=YES+cb",
    )

    private val client = OkHttpClient.Builder().followRedirects(true).build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = OkRequest.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> if (v.isNotEmpty()) addHeader(k, v) } }
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw IOException("Empty body: $url")
        }
    }

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
    ): String {
        val requestBody = body.toRequestBody(contentType.toMediaType())
        val request = OkRequest.Builder()
            .url(url)
            .post(requestBody)
            .apply { headers.forEach { (k, v) -> if (v.isNotEmpty() && !k.equals("Content-Type", ignoreCase = true)) addHeader(k, v) } }
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw IOException("Empty body: $url")
        }
    }
}
