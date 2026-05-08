package com.minseo41.subfeed.data

import com.minseo41.subfeed.data.db.ChannelDao
import com.minseo41.subfeed.data.db.ChannelEntity
import com.minseo41.subfeed.model.SubscribedChannel
import com.minseo41.subfeed.model.VideoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepo @Inject constructor(
    private val channelDao: ChannelDao,
    private val extractor: VideoExtractor,
) {

    fun observeChannels(): Flow<List<SubscribedChannel>> =
        channelDao.observeAll().map { entities -> entities.map { it.toModel() } }

    suspend fun loadChannels(): List<SubscribedChannel> =
        channelDao.getAll().map { it.toModel() }

    suspend fun channelCount(): Int = channelDao.count()

    suspend fun updateChannel(channel: SubscribedChannel) {
        val existing = channelDao.getAll().firstOrNull { it.id == channel.id } ?: return
        channelDao.update(
            existing.copy(
                name = channel.name,
                windowDays = channel.windowDays,
                maxCount = channel.maxCount,
            )
        )
    }

    suspend fun deleteChannel(id: String) {
        channelDao.deleteById(id)
    }

    // 채널 import — 기존 channels 모두 삭제하고 파일 내용으로 교체. favorites 테이블은 보존됨.
    // 파일 내용 첫 글자로 JSON / XML 자동 감지. XML 일 때는 windowDays / maxCount 가 default 값으로 들어감.
    suspend fun importFromStream(stream: InputStream): Int {
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = if (text.trimStart().startsWith("<")) parseTakeoutXml(text)
            else parseChannelJson(text)
        val entities = parsed.mapIndexed { index, c ->
            ChannelEntity(
                id = c.id,
                name = c.name,
                windowDays = c.windowDays ?: SubscribedChannel.DEFAULT_WINDOW_DAYS,
                maxCount = c.maxCount ?: SubscribedChannel.DEFAULT_MAX_COUNT,
                sortOrder = index,
            )
        }
        channelDao.deleteAll()
        channelDao.insertAll(entities)
        return entities.size
    }

    private fun parseChannelJson(text: String): List<ChannelJson> =
        Json { ignoreUnknownKeys = true }.decodeFromString(text)

    // YouTube Takeout subscriptions.xml (Atom + yt:channelId 네임스페이스) 파서.
    // <entry> 안의 <yt:channelId> 와 <title> 만 추출. windowDays / maxCount 는 비워서 default 값 적용.
    private fun parseTakeoutXml(text: String): List<ChannelJson> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(text))

        val channels = mutableListOf<ChannelJson>()
        val seen = mutableSetOf<String>()
        var inEntry = false
        var currentTag: String? = null
        var currentId: String? = null
        var currentName: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name
                    if (local == "entry") {
                        inEntry = true
                        currentId = null
                        currentName = null
                    }
                    currentTag = local
                }
                XmlPullParser.TEXT -> if (inEntry) {
                    val txt = parser.text?.trim().orEmpty()
                    if (txt.isNotEmpty()) {
                        when (currentTag) {
                            "channelId" -> currentId = txt
                            "title" -> if (currentName == null) currentName = txt
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "entry") {
                        val cid = currentId
                        if (cid != null && cid.isNotEmpty() && cid !in seen) {
                            seen.add(cid)
                            channels += ChannelJson(
                                id = cid,
                                name = currentName ?: cid,
                                windowDays = null,
                                maxCount = null,
                            )
                        }
                        inEntry = false
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }
        return channels
    }

    // 채널별 windowDays / maxCount 적용해 영상 가져오기.
    // - windowDays=N → today.minusDays(N-1) 부터 today까지 업로드된 영상만
    // - maxCount → 채널당 위 필터 후 최신순 N개까지
    suspend fun fetchTodayVideos(): List<VideoItem> = coroutineScope {
        val today = LocalDate.now(ZoneId.systemDefault())
        val channels = loadChannels()
        channels.map { channel ->
            async {
                runCatching {
                    val raw = extractor.getChannelFeed(SubscribedChannel.rssUrlFromId(channel.id))
                    val cutoff = today.minusDays((channel.windowDays - 1).coerceAtLeast(0).toLong())
                    raw
                        .filter { video ->
                            val uploadDate = Instant.ofEpochMilli(video.uploadedAt)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            !uploadDate.isBefore(cutoff) && !uploadDate.isAfter(today)
                        }
                        .sortedByDescending { it.uploadedAt }
                        .take(channel.maxCount)
                }.getOrDefault(emptyList())
            }
        }
            .awaitAll()
            .flatten()
            .sortedByDescending { it.uploadedAt }
    }

    @Serializable
    private data class ChannelJson(
        val id: String,
        val name: String,
        val windowDays: Int? = null,
        val maxCount: Int? = null,
    )
}

private fun ChannelEntity.toModel(): SubscribedChannel = SubscribedChannel(
    id = id,
    name = name,
    url = SubscribedChannel.rssUrlFromId(id),
    windowDays = windowDays,
    maxCount = maxCount,
)

private fun SubscribedChannel.toEntity(sortOrder: Int): ChannelEntity = ChannelEntity(
    id = id,
    name = name,
    windowDays = windowDays,
    maxCount = maxCount,
    sortOrder = sortOrder,
)
