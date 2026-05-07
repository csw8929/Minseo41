package com.minseo41.subfeed.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_positions")
data class WatchPositionEntity(
    @PrimaryKey val videoId: String,
    val positionMs: Long,
    val updatedAt: Long,
)
