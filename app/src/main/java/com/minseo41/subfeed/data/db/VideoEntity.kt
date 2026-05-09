package com.minseo41.subfeed.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "videos",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("channelId"),
        Index("uploadedAt"),
    ],
)
data class VideoEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val uploadedAt: Long,
    val isUnread: Boolean,
    val firstSeenAtMs: Long,
)
