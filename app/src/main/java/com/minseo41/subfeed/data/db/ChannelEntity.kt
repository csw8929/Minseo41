package com.minseo41.subfeed.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val windowDays: Int,
    val maxCount: Int,
    val sortOrder: Int,
    val lastFetchAtMs: Long? = null,
    val lastFetchOk: Boolean? = null,
    val lastFetchError: String? = null,
)
