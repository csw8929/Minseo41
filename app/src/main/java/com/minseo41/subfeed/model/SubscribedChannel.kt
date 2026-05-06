package com.minseo41.subfeed.model

data class SubscribedChannel(
    val id: String,
    val name: String,
    val url: String,
    val windowDays: Int = DEFAULT_WINDOW_DAYS,
    val maxCount: Int = DEFAULT_MAX_COUNT,
) {
    companion object {
        const val DEFAULT_WINDOW_DAYS = 1
        const val DEFAULT_MAX_COUNT = 15

        fun rssUrlFromId(id: String): String =
            "https://www.youtube.com/channel/$id"
    }
}
