package com.minseo41.subfeed.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WatchPositionDao {

    @Query("SELECT * FROM watch_positions WHERE videoId = :videoId")
    suspend fun get(videoId: String): WatchPositionEntity?

    @Query("SELECT * FROM watch_positions")
    suspend fun getAll(): List<WatchPositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: WatchPositionEntity)

    @Query("DELETE FROM watch_positions WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("SELECT COUNT(*) FROM watch_positions")
    suspend fun count(): Int

    @Query(
        """
        DELETE FROM watch_positions
        WHERE videoId IN (
            SELECT videoId FROM watch_positions
            ORDER BY updatedAt ASC
            LIMIT :deleteCount
        )
        """
    )
    suspend fun pruneOldest(deleteCount: Int)
}
