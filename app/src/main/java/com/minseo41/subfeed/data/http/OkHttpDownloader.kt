package com.minseo41.subfeed.data.http

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// 범용 OkHttp helper. RSS 파싱, 자막 fetch 등 NewPipe 와 무관한 직접 HTTP 호출 경로에서 사용.
// NewPipe 자체는 SubFeedDownloader 가 별도로 어댑팅한다.
object OkHttpDownloader {

    private val client = OkHttpClient.Builder().followRedirects(true).build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> if (v.isNotEmpty()) addHeader(k, v) } }
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw IOException("Empty body: $url")
        }
    }
}
