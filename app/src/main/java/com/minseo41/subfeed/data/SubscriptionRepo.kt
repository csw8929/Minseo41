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
import java.io.InputStream
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

    // JSON import — 기존 channels 모두 삭제하고 JSON 내용으로 교체. favorites 테이블은 보존됨.
    suspend fun importFromJson(stream: InputStream): Int {
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<List<ChannelJson>>(text)
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
