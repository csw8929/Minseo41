package com.minseo41.subfeed.data.newpipe

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

// NewPipeExtractor 가 모든 HTTP 호출을 위임할 Downloader 구현.
// OkHttp 싱글턴 client 위에 thin wrapper. RSS/InnerTube/base.js fetch 등 모두 이걸 통해 나감.
object SubFeedDownloader : Downloader() {

    private val client = OkHttpClient.Builder().followRedirects(true).build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val data = request.dataToSend()

        val builder = OkRequest.Builder().url(url)
        val contentTypeHeader = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()
        val bodyMediaType = (contentTypeHeader ?: "application/json").toMediaTypeOrNull()

        if (data != null) {
            // toRequestBody 가 mediaType 을 처리하므로 Content-Type 헤더는 별도 addHeader 하지 않음 (중복 회피)
            builder.method(httpMethod, data.toRequestBody(bodyMediaType))
        } else {
            builder.method(httpMethod, null)
        }

        for ((key, values) in headers) {
            if (key.equals("Content-Type", ignoreCase = true)) continue
            for (v in values) builder.addHeader(key, v)
        }

        // URL path summary 만 로그 (signature/n-param 등 sensitive query 제외)
        val urlSummary = summarizeUrl(url)
        val bodySize = data?.size ?: 0
        val t0 = System.currentTimeMillis()
        try {
            return client.newCall(builder.build()).execute().use { rsp ->
                val responseBody = rsp.body?.string().orEmpty()
                val responseHeaders: Map<String, List<String>> = rsp.headers.toMultimap()
                val elapsed = System.currentTimeMillis() - t0
                val redirected = rsp.request.url.toString() != url
                val redirectedTag = if (redirected) " redirected=Y" else ""
                if (rsp.code in 200..299) {
                    Log.d(TAG, "$httpMethod $urlSummary → ${rsp.code} body=${responseBody.length}B ${elapsed}ms reqBody=${bodySize}B$redirectedTag")
                } else {
                    Log.w(TAG, "$httpMethod $urlSummary → ${rsp.code} ${rsp.message} body=${responseBody.length}B ${elapsed}ms$redirectedTag")
                }
                Response(
                    rsp.code,
                    rsp.message,
                    responseHeaders,
                    responseBody,
                    rsp.request.url.toString(),
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            Log.e(TAG, "$httpMethod $urlSummary → ${e.javaClass.simpleName} after ${elapsed}ms: ${e.message}")
            throw e
        }
    }

    // URL → "host/path[truncated]" — query string은 제거 (sig/n-param 노출 방지)
    private fun summarizeUrl(url: String): String {
        val noQuery = url.substringBefore('?')
        val pathLen = noQuery.length
        return if (pathLen > 90) noQuery.take(90) + "…" else noQuery
    }

    private const val TAG = "SubFeedNpDownload"
}
