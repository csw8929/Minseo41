package com.minseo41.subfeed.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY sortOrder ASC")
    suspend fun getAll(): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM channels")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Query(
        """
        UPDATE channels
        SET lastFetchAtMs = :atMs, lastFetchOk = 1, lastFetchError = NULL
        WHERE id = :id
        """
    )
    suspend fun markFetchSuccess(id: String, atMs: Long)

    @Query(
        """
        UPDATE channels
        SET lastFetchOk = 0, lastFetchError = :err
        WHERE id = :id
        """
    )
    suspend fun markFetchFailure(id: String, err: String?)

    @Query("SELECT MAX(lastFetchAtMs) FROM channels")
    fun observeLastFetchAtMs(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM channels WHERE lastFetchOk = 0")
    fun observeFailedFetchCount(): Flow<Int>
}
