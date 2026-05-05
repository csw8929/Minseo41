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
) {
    val isDownloaded: Boolean get() = localFilePath != null
}
