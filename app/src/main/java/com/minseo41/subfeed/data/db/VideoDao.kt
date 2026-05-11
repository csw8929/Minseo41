package com.minseo41.subfeed.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("SELECT * FROM videos ORDER BY uploadedAt DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE uploadedAt >= :minUploadedAt ORDER BY uploadedAt DESC")
    fun observeSince(minUploadedAt: Long): Flow<List<VideoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(videos: List<VideoEntity>): List<Long>

    @Query("SELECT * FROM videos WHERE videoId = :videoId LIMIT 1")
    suspend fun getById(videoId: String): VideoEntity?

    @Query("UPDATE videos SET isUnread = 0 WHERE videoId = :videoId")
    suspend fun markRead(videoId: String)

    @Query("UPDATE videos SET durationSeconds = :durationSeconds WHERE videoId = :videoId AND durationSeconds = 0")
    suspend fun updateDurationIfZero(videoId: String, durationSeconds: Long)

    @Query(
        """
        DELETE FROM videos
        WHERE channelId = :channelId
          AND (
            uploadedAt < :cutoffMs
            OR videoId NOT IN (
              SELECT videoId FROM videos
              WHERE channelId = :channelId
              ORDER BY uploadedAt DESC
              LIMIT :maxCount
            )
          )
        """
    )
    suspend fun pruneChannel(channelId: String, cutoffMs: Long, maxCount: Int)

    @Query("DELETE FROM videos")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun count(): Int
}
