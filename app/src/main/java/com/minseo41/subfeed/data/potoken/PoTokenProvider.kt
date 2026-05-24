package com.minseo41.subfeed.data.potoken

import android.content.Context
import android.util.Log
import com.minseo41.subfeed.data.OkHttpDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

// WEB 클라이언트 InnerTube + Streaming PoToken 공급자.
// 1) visitorData 발급 (1회): InnerTube /visitor_id
// 2) PoTokenWebView 초기화 (12h 정도 유효)
// 3) streamingDataPoToken = generatePoToken(visitorData)   — 영상 무관, 재사용
// 4) playerRequestPoToken = generatePoToken(videoId)        — 영상별
//
// integrityToken 만료 또는 generator 에러 시 자동 재생성 (1회).
@Singleton
class PoTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var generator: PoTokenGenerator? = null
    private var visitorData: String? = null
    private var streamingPot: String? = null

    suspend fun getWebClientPoToken(videoId: String): PoTokenResult? = try {
        getOrThrow(videoId = videoId, forceRecreate = false)
    } catch (e: BadWebViewException) {
        Log.e(TAG, "WebView impl broken — PoToken disabled", e)
        null
    } catch (e: Throwable) {
        Log.e(TAG, "PoToken fetch failed for $videoId", e)
        null
    }

    private suspend fun getOrThrow(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val snapshot: Snapshot = mutex.withLock {
            val shouldRecreate = generator == null || forceRecreate || generator!!.isExpired()
            if (shouldRecreate) {
                generator?.let { runCatching { it.close() } }
                visitorData = fetchVisitorData()
                generator = PoTokenWebView.create(context)
                // streaming PoToken 은 다른 PoToken 보다 먼저 정확히 1회 발급
                streamingPot = generator!!.generatePoToken(visitorData!!)
                Log.d(TAG, "PoToken init OK, visitorData(len=${visitorData!!.length}) streamingPot(len=${streamingPot!!.length})")
            }
            Snapshot(generator!!, visitorData!!, streamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            snapshot.generator.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (snapshot.recreated) throw t
            Log.w(TAG, "PoToken player mint failed, retry with fresh generator", t)
            return getOrThrow(videoId = videoId, forceRecreate = true)
        }
        return PoTokenResult(snapshot.visitorData, playerPot, snapshot.streamingPot)
    }

    private data class Snapshot(
        val generator: PoTokenGenerator,
        val visitorData: String,
        val streamingPot: String,
        val recreated: Boolean,
    )

    // InnerTube /visitor_id 호출 → visitorData 추출.
    // WEB context 로 호출해야 visitorData 가 반환됨.
    private fun fetchVisitorData(): String {
        val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"$WEB_CLIENT_VERSION","hl":"ko","gl":"KR"}}}"""
        val raw = OkHttpDownloader.post(
            url = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
            body = body,
            headers = mapOf(
                "User-Agent" to WEB_USER_AGENT,
                "Content-Type" to "application/json",
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to WEB_CLIENT_VERSION,
                "Origin" to "https://www.youtube.com",
            ),
        )
        val json = JSONObject(raw)
        val visitor = json.optJSONObject("responseContext")?.optString("visitorData", "").orEmpty()
        if (visitor.isEmpty()) throw PoTokenException("visitorData 없음 — raw=${raw.take(200)}")
        return visitor
    }

    companion object {
        private const val TAG = "SubFeedPoToken"
        const val WEB_CLIENT_VERSION = "2.20251208.07.00"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
