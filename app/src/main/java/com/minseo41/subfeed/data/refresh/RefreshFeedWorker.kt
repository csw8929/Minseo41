package com.minseo41.subfeed.data.refresh

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.minseo41.subfeed.data.VideoExtractor
import com.minseo41.subfeed.data.db.ChannelDao
import com.minseo41.subfeed.data.db.ChannelEntity
import com.minseo41.subfeed.data.db.VideoDao
import com.minseo41.subfeed.data.db.VideoEntity
import com.minseo41.subfeed.model.SubscribedChannel
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import java.time.LocalDate
import java.time.ZoneId

@HiltWorker
class RefreshFeedWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val channelDao: ChannelDao,
    private val videoDao: VideoDao,
    private val extractor: VideoExtractor,
    private val refreshPrefs: RefreshPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val channels = channelDao.getAll()
        if (channels.isEmpty()) {
            Log.d(TAG, "no channels — skip")
            return@coroutineScope Result.success()
        }
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.systemDefault())

        val results = channels.map { channel ->
            async { refreshChannel(channel, now, today) }
        }.awaitAll()

        val successCount = results.count { it }
        refreshPrefs.saveLog(RefreshLogEntry(now, channels.size, successCount))
        Log.d(TAG, "refresh done: $successCount/${channels.size} channels")

        Result.success(workDataOf("ch" to channels.size, "ok" to successCount))
    }

    private suspend fun refreshChannel(
        channel: ChannelEntity,
        nowMs: Long,
        today: LocalDate,
    ): Boolean {
        return runCatching {
            val raw = extractor.getChannelFeed(SubscribedChannel.rssUrlFromId(channel.id))
            val cutoffDate = today.minusDays((channel.windowDays - 1).coerceAtLeast(0).toLong())
            val cutoffMs = cutoffDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val filtered = raw
                .filter { it.uploadedAt >= cutoffMs }
                .sortedByDescending { it.uploadedAt }
                .take(channel.maxCount)

            val entities = filtered.map { v ->
                VideoEntity(
                    videoId = v.id,
                    channelId = channel.id,
                    channelName = channel.name,
                    title = v.title,
                    thumbnailUrl = v.thumbnailUrl,
                    durationSeconds = v.durationSeconds,
                    uploadedAt = v.uploadedAt,
                    isUnread = true,
                    firstSeenAtMs = nowMs,
                )
            }

            videoDao.insertIgnoreAll(entities)
            videoDao.pruneChannel(channel.id, cutoffMs, channel.maxCount)
            channelDao.markFetchSuccess(channel.id, nowMs)
        }.onFailure { e ->
            Log.w(TAG, "refresh failed for channel=${channel.id} (${channel.name})", e)
            channelDao.markFetchFailure(channel.id, e.message ?: e::class.java.simpleName)
        }.isSuccess
    }

    companion object {
        private const val TAG = "SubFeedRefreshWorker"
    }
}
