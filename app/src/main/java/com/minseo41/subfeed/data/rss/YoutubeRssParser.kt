package com.minseo41.subfeed.data.rss

import com.minseo41.subfeed.data.http.OkHttpDownloader
import com.minseo41.subfeed.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

// YouTube 채널 RSS 피드 fetch + Atom 파싱.
// NewPipe 와 무관 — YouTube RSS 는 공식 endpoint 라 매우 안정적이다.
@Singleton
class YoutubeRssParser @Inject constructor() {

    suspend fun fetch(channelUrl: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val channelId = channelUrl.substringAfterLast("/").substringBefore("?")
        val rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        val raw = OkHttpDownloader.get(rssUrl, RSS_HEADERS)
        if (!raw.startsWith("<?xml")) {
            throw IOException("RSS 응답이 XML 이 아님 — channelId=$channelId, head=${raw.take(120)}")
        }
        parseAtom(raw)
    }

    private fun parseAtom(xml: String): List<VideoItem> {
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
        // YouTube RSS 가 봇/EU consent 페이지로 redirect 되는 걸 막기 위한 헤더 set.
        private val RSS_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
            "Accept" to "application/atom+xml, application/xml, text/xml, */*;q=0.1",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
            "Cookie" to "CONSENT=YES+cb",
        )
    }
}
