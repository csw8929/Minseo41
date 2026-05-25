package com.minseo41.subfeed.data.newpipe

import android.util.Log
import com.minseo41.subfeed.data.CaptionTrack
import com.minseo41.subfeed.data.StreamInfo
import com.minseo41.subfeed.data.chapter.ChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import javax.inject.Inject
import javax.inject.Singleton

// NewPipe Extractor 를 사용해 YouTube 영상의 스트림 URL + 자막 + 챕터 를 추출.
// 우리 코드가 NewPipe API 를 직접 호출하는 유일한 클래스. NewPipe 버전 업데이트 시 영향
// 범위가 이 파일 + DashMpdBuilder + SubFeedDownloader + SubFeedPoTokenProvider 로 한정된다.
//
// PoTokenProvider 는 SubFeedApp.onCreate() 에서 YoutubeStreamExtractor.setPoTokenProvider() 로 주입.
// 의존 NewPipe API 목록은 같은 패키지의 README.md 참고.
@Singleton
class NewPipeStreamFetcher @Inject constructor(
    private val dashMpdBuilder: DashMpdBuilder,
    private val chapterParser: ChapterParser,
) {

    suspend fun fetch(videoId: String): StreamInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetch start videoId=$videoId")
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val service = ServiceList.YouTube
        val linkHandler = service.streamLHFactory.fromUrl(watchUrl)
        val extractor = service.getStreamExtractor(linkHandler) as YoutubeStreamExtractor

        // PoToken 발급 + InnerTube 요청 + base.js 파싱 + n-param/sig deobfuscate
        // 일반적으로 첫 호출은 5~10초 (WebView 워밍업 + base.js 다운로드), 이후는 ms 단위.
        val fetchStart = System.currentTimeMillis()
        runCatching { extractor.fetchPage() }
            .onFailure {
                val elapsed = System.currentTimeMillis() - fetchStart
                Log.e(TAG, "fetchPage failed videoId=$videoId after ${elapsed}ms type=${it.javaClass.simpleName} msg=${it.message}")
                throw it
            }
        val fetchElapsed = System.currentTimeMillis() - fetchStart
        Log.d(TAG, "fetchPage OK videoId=$videoId in ${fetchElapsed}ms")

        val durationSec = extractor.length
        val description = runCatching { extractor.description?.content.orEmpty() }.getOrDefault("")
        val chapters = chapterParser.parse(description)

        // Stream 가용성 진단용 카운트
        val dashUrl = runCatching { extractor.dashMpdUrl }.getOrNull().orEmpty()
        val hlsUrl = runCatching { extractor.hlsUrl }.getOrNull().orEmpty()
        val videoOnlyRaw = runCatching { extractor.videoOnlyStreams }.getOrDefault(emptyList())
        val audioStreamsRaw = runCatching { extractor.audioStreams }.getOrDefault(emptyList())
        val videoStreamsRaw = runCatching { extractor.videoStreams }.getOrDefault(emptyList())
        Log.d(
            TAG,
            "streams videoId=$videoId durationSec=$durationSec " +
                "dashUrl=${dashUrl.isNotEmpty()} hlsUrl=${hlsUrl.isNotEmpty()} " +
                "videoOnly=${videoOnlyRaw.size} audio=${audioStreamsRaw.size} " +
                "videoMuxed=${videoStreamsRaw.size} chapters=${chapters.size}",
        )

        // 1순위: DASH manifest URL — n-deobfuscated chunk URL 포함, ExoPlayer 가 직접 fetch.
        if (dashUrl.isNotEmpty()) {
            Log.d(TAG, "fetch OK videoId=$videoId type=DASH durationSec=$durationSec urlHead=${dashUrl.take(80)}")
            return@withContext StreamInfo(
                streamUrl = "dash:$dashUrl",
                captionTracks = collectCaptions(extractor),
                durationSeconds = durationSec.toLong(),
                chapters = chapters,
            )
        }

        // 2순위: HLS manifest (라이브/일부 영상)
        if (hlsUrl.isNotEmpty()) {
            Log.d(TAG, "fetch OK videoId=$videoId type=HLS (DASH not available) durationSec=$durationSec urlHead=${hlsUrl.take(80)}")
            return@withContext StreamInfo(
                streamUrl = "hls:$hlsUrl",
                captionTracks = collectCaptions(extractor),
                durationSeconds = durationSec.toLong(),
                chapters = chapters,
            )
        }

        // 3순위: videoOnlyStreams + audioStreams 로 inline DASH 빌드 (고화질 1080p+)
        // videoStreams (muxed) 는 보통 360~720p 한계라 마지막 fallback 으로 밀어둠.
        val videoOnly = videoOnlyRaw.filter { it.content.isNotEmpty() && it.itagItem != null }
        val audioStreams = audioStreamsRaw.filter { it.content.isNotEmpty() && it.itagItem != null }
        if (videoOnly.size != videoOnlyRaw.size || audioStreams.size != audioStreamsRaw.size) {
            Log.d(
                TAG,
                "filtered streams videoId=$videoId " +
                    "videoOnly=${videoOnly.size}/${videoOnlyRaw.size} " +
                    "audio=${audioStreams.size}/${audioStreamsRaw.size} (dropped empty content or null itag)",
            )
        }
        if (videoOnly.isNotEmpty() && audioStreams.isNotEmpty()) {
            val mpdB64 = dashMpdBuilder.build(videoOnly, audioStreams, durationSec.toLong())
            if (mpdB64 != null) {
                Log.d(TAG, "fetch OK videoId=$videoId type=DASH-INLINE video=${videoOnly.size} audio=${audioStreams.size} durationSec=$durationSec mpdB64Len=${mpdB64.length}")
                return@withContext StreamInfo(
                    streamUrl = "dash:data:application/dash+xml;base64,$mpdB64",
                    captionTracks = collectCaptions(extractor),
                    durationSeconds = durationSec.toLong(),
                    chapters = chapters,
                )
            } else {
                Log.w(TAG, "DASH-INLINE 빌드 실패 videoId=$videoId — DashMpdBuilder가 null 반환 (filtering rejected all tracks). MUXED 로 fallback")
            }
        } else {
            Log.w(TAG, "DASH-INLINE skip videoId=$videoId — videoOnly=${videoOnly.size} audio=${audioStreams.size}, MUXED 로 fallback")
        }

        // 4순위: 최고 해상도 video stream (muxed, 보통 360~720p) — last resort
        val videoStream = videoStreamsRaw.maxByOrNull { it.height }
        if (videoStream != null && videoStream.content.isNotEmpty()) {
            Log.w(TAG, "fetch DEGRADED videoId=$videoId type=MUXED ${videoStream.height}p urlHead=${videoStream.content.take(80)}")
            return@withContext StreamInfo(
                streamUrl = videoStream.content,
                captionTracks = collectCaptions(extractor),
                durationSeconds = durationSec.toLong(),
                chapters = chapters,
            )
        }

        Log.e(TAG, "fetch FAIL videoId=$videoId — DASH/HLS/INLINE-DASH/MUXED 모두 비어있음. " +
            "dashUrl=${dashUrl.isNotEmpty()} hlsUrl=${hlsUrl.isNotEmpty()} " +
            "videoOnly=${videoOnlyRaw.size} audio=${audioStreamsRaw.size} videoMuxed=${videoStreamsRaw.size}")
        error("재생 가능한 stream URL 없음 — DASH/HLS/INLINE-DASH/MUXED 모두 비어 있음")
    }

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

    companion object {
        private const val TAG = "SubFeedStream"
    }
}
