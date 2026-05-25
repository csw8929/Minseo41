package com.minseo41.subfeed.data.chapter

import com.minseo41.subfeed.data.Chapter
import javax.inject.Inject
import javax.inject.Singleton

// 영상 description 텍스트에서 챕터 timestamp 추출.
// 라인이 `[?(HH:)?MM:SS]? <title>` 형태로 시작하고 3개 이상 연속, 단조 증가일 때만 valid.
// NewPipe 와 무관 — 순수 정규식 파서.
@Singleton
class ChapterParser @Inject constructor() {

    fun parse(description: String): List<Chapter> {
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
}
