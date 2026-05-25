package com.minseo41.subfeed.data.newpipe

import android.util.Base64
import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import javax.inject.Inject
import javax.inject.Singleton

// NewPipe 가 추출한 videoOnly + audio stream 들을 받아 inline DASH MPD 를 만들고 base64 반환.
// NewPipe 가 URL 의 n-param/sig 를 이미 deobfuscate 했으므로 그대로 BaseURL 에 박으면 됨.
// 각 video 트랙을 별도 Representation 으로 노출 → ExoPlayer 의 ABR + 우리 QualityMenu 가 선택 가능.
// 오디오는 언어별 AdaptationSet 분리 (한국어 더빙 등 지원).
//
// NewPipe API 사용: AudioStream, VideoStream, ItagItem 의 속성들.
// 시그니처 변경 영향 → 이 파일만 보면 됨.
@Singleton
class DashMpdBuilder @Inject constructor() {

    fun build(
        videoStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        durationSec: Long,
    ): String? {
        // 1080p 이하만 (현재 codec/decoder 호환성 안전). 정렬은 height 오름차순.
        val videos = videoStreams
            .filter { it.height in 1..1080 && hasRanges(it.itagItem!!) }
            .sortedBy { it.height }
        if (videos.isEmpty()) {
            val heightSummary = videoStreams.map { it.height }.sorted()
            val noRangesCount = videoStreams.count { it.itagItem != null && !hasRanges(it.itagItem!!) }
            Log.w(TAG, "build → null: 필터 후 video 0개. 원본=${videoStreams.size} heights=$heightSummary noRanges=$noRangesCount (1080p 이하 + initRange/indexRange 필요)")
            return null
        }

        // 언어별로 best bitrate 1개씩만. audioTrackId/audioLocale 없으면 단일 트랙.
        data class BestAudio(val stream: AudioStream, val bitrate: Int)
        val audioByLang = linkedMapOf<String?, BestAudio>()
        var audioNoItag = 0
        var audioNoRanges = 0
        for (a in audioStreams) {
            if (a.itagItem == null) { audioNoItag++; continue }
            if (!hasRanges(a.itagItem!!)) { audioNoRanges++; continue }
            val lang = a.audioLocale?.language ?: a.audioTrackId?.split(".")?.firstOrNull { it.length in 2..3 && it.all { c -> c.isLetter() } }
            val bw = a.averageBitrate.takeIf { it > 0 } ?: (a.itagItem!!.avgBitrate.takeIf { it > 0 } ?: 128000)
            val prev = audioByLang[lang]
            if (prev == null || bw > prev.bitrate) audioByLang[lang] = BestAudio(a, bw)
        }
        if (audioByLang.isEmpty()) {
            Log.w(TAG, "build → null: 필터 후 audio 0개. 원본=${audioStreams.size} noItag=$audioNoItag noRanges=$audioNoRanges")
            return null
        }

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
        val heights = videos.map { it.height }
        val langs = audioByLang.keys.map { it ?: "<unknown>" }
        Log.d(TAG, "build OK: video=${videos.size} heights=$heights audio=${audioByLang.size} langs=$langs mpdLen=${mpd.length}")
        return Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun hasRanges(item: ItagItem): Boolean =
        item.getInitEnd() > 0 && item.getIndexEnd() > 0

    private companion object {
        private const val TAG = "SubFeedNpDash"
    }
}
