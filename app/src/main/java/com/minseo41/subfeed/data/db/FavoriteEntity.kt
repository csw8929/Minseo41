package com.minseo41.subfeed.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String?,
    val thumbnailUrl: String,
    val uploadedAt: Long,
    val addedAt: Long,
)
