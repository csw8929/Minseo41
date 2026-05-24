package com.minseo41.subfeed.data.newpipe

import android.util.Log
import com.minseo41.subfeed.data.potoken.PoTokenProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider as NpPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult as NpPoTokenResult

// NewPipeExtractor 의 PoTokenProvider 구현 — 우리 WebView 기반 PoTokenProvider 에 위임.
// NewPipe 가 InnerTube 요청 (WEB / WEB_EMBEDDED / ANDROID / IOS) 전에 호출하면, 우리 PoTokenWebView 가
// BotGuard 돌려서 visitorData + playerPot + streamingPot 을 반환.
//
// NewPipe 의 reference impl (PoTokenProviderImpl.kt) 은 ANDROID/IOS 는 null 반환하는데, 우리는 같은
// visitor 세션 PoToken 을 ANDROID/IOS 에도 제공해 더 많은 영상에서 동작하게 한다.
@Singleton
class SubFeedPoTokenProvider @Inject constructor(
    private val ourProvider: PoTokenProvider,
) : NpPoTokenProvider {

    override fun getWebClientPoToken(videoId: String): NpPoTokenResult? = obtainSafe(videoId)

    override fun getWebEmbedClientPoToken(videoId: String): NpPoTokenResult? = obtainSafe(videoId)

    override fun getAndroidClientPoToken(videoId: String): NpPoTokenResult? = obtainSafe(videoId)

    override fun getIosClientPoToken(videoId: String): NpPoTokenResult? = obtainSafe(videoId)

    // NewPipe 가 동기 호출하므로 runBlocking 으로 우리 suspend API 를 바라보게 함.
    // PoTokenWebView 캐싱 덕에 첫 호출만 2~5초, 이후는 ms 단위.
    private fun obtainSafe(videoId: String): NpPoTokenResult? = runCatching {
        runBlocking {
            ourProvider.getWebClientPoToken(videoId)?.let {
                NpPoTokenResult(it.visitorData, it.playerRequestPoToken, it.streamingDataPoToken)
            }
        }
    }.onFailure { Log.w(TAG, "PoToken request failed for $videoId", it) }.getOrNull()

    companion object {
        private const val TAG = "SubFeedNpPoToken"
    }
}
