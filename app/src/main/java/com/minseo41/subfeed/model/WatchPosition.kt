package com.minseo41.subfeed.model

import com.google.firebase.Timestamp

data class WatchPosition(
    val videoId: String = "",
    val positionMs: Long = 0L,
    val updatedAt: Timestamp = Timestamp.now(),
)
