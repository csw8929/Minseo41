package com.minseo41.subfeed.model

data class VideoItem(
    val id: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val uploadedAt: Long, // epoch millis
    val streamUrl: String? = null,
    val localFilePath: String? = null,
    val isUnread: Boolean = false,
    val watchPositionMs: Long? = null,
) {
    val isDownloaded: Boolean get() = localFilePath != null
    val watchFraction: Float?
        get() = if (watchPositionMs != null && durationSeconds > 0)
            (watchPositionMs / 1000f / durationSeconds).coerceIn(0f, 1f) else null
}
