package com.minseo41.subfeed.data.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// 범용 OkHttp helper. RSS 파싱, PoToken BotGuard 호출, 자막 fetch 등 NewPipe 와 무관한 모든
// 직접 HTTP 호출 경로에서 사용. NewPipe 자체는 SubFeedDownloader 가 별도로 어댑팅한다.
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

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
    ): String {
        val requestBody = body.toRequestBody(contentType.toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply { headers.forEach { (k, v) -> if (v.isNotEmpty() && !k.equals("Content-Type", ignoreCase = true)) addHeader(k, v) } }
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw IOException("Empty body: $url")
        }
    }
}
