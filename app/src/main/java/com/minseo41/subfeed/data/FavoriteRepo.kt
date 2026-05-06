package com.minseo41.subfeed.data

import com.minseo41.subfeed.data.db.FavoriteDao
import com.minseo41.subfeed.data.db.FavoriteEntity
import com.minseo41.subfeed.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepo @Inject constructor(
    private val favoriteDao: FavoriteDao,
) {

    fun observeFavorites(): Flow<List<VideoItem>> =
        favoriteDao.observeAll().map { list -> list.map { it.toVideoItem() } }

    fun observeFavoriteIds(): Flow<Set<String>> =
        favoriteDao.observeIds().map { it.toSet() }

    suspend fun toggle(video: VideoItem, channelId: String? = null) {
        if (favoriteDao.exists(video.id)) {
            favoriteDao.deleteByVideoId(video.id)
        } else {
            favoriteDao.insert(
                FavoriteEntity(
                    videoId = video.id,
                    title = video.title,
                    channelName = video.channelName,
                    channelId = channelId,
                    thumbnailUrl = video.thumbnailUrl,
                    uploadedAt = video.uploadedAt,
                    addedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun remove(videoId: String) {
        favoriteDao.deleteByVideoId(videoId)
    }
}

private fun FavoriteEntity.toVideoItem(): VideoItem = VideoItem(
    id = videoId,
    title = title,
    channelName = channelName,
    thumbnailUrl = thumbnailUrl,
    durationSeconds = 0L,
    uploadedAt = uploadedAt,
)
