package com.minseo41.subfeed.data

import android.content.Context
import com.minseo41.subfeed.model.SubscribedChannel
import com.minseo41.subfeed.model.VideoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractor: VideoExtractor,
) {
    private val prefs = context.getSharedPreferences("subscriptions", Context.MODE_PRIVATE)

    fun saveChannels(channels: List<SubscribedChannel>) {
        val json = channels.joinToString("|") { "${it.id};;${it.name};;${it.url}" }
        prefs.edit().putString("channels", json).apply()
    }

    fun loadChannels(): List<SubscribedChannel> {
        val raw = prefs.getString("channels", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(";;")
            if (parts.size == 3) SubscribedChannel(parts[0], parts[1], parts[2]) else null
        }
    }

    // YouTube Takeout XML (subscriptions.xml) 파싱
    fun parseYoutubeTakeoutXml(stream: InputStream): List<SubscribedChannel> {
        val channels = mutableListOf<SubscribedChannel>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")
        var channelId = ""
        var channelName = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "yt:channelId" -> channelId = parser.nextText()
                    "title" -> if (channelId.isNotEmpty()) channelName = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                if (channelId.isNotEmpty()) {
                    channels.add(
                        SubscribedChannel(
                            id = channelId,
                            name = channelName,
                            url = "https://www.youtube.com/channel/$channelId",
                        )
                    )
                    channelId = ""
                    channelName = ""
                }
            }
            event = parser.next()
        }
        return channels
    }

    // 오늘 영상 가져오기 — 모든 채널 병렬 조회 (IO dispatcher)
    suspend fun fetchTodayVideos(): List<VideoItem> = coroutineScope {
        val today = LocalDate.now(ZoneId.systemDefault())
        val channels = loadChannels()
        channels.map { channel ->
            async {
                runCatching { extractor.getChannelFeed(channel.url) }
                    .getOrDefault(emptyList())
            }
        }
            .awaitAll()
            .flatten()
            .filter { video ->
                val uploadDate = Instant.ofEpochMilli(video.uploadedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                uploadDate == today
            }
            .sortedByDescending { it.uploadedAt }
    }
}
