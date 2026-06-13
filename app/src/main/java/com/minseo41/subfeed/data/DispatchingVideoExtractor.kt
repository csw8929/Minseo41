package com.minseo41.subfeed.data

import android.content.Context
import android.util.Log
import com.minseo41.subfeed.data.nas.NasStreamFetcher
import com.minseo41.subfeed.model.VideoItem
import com.minseo41.subfeed.ui.PlayerPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

// VideoExtractor 진입점 — 채널 피드는 항상 NewPipe(RSS) 경로, 스트림 추출만 설정에 따라 분기.
//
// NAS 추출 프록시(개발자 옵션)가 켜져 있고 base URL 이 있으면 NAS 로 먼저 시도하고,
// 실패(타임아웃/HTTP 오류/파싱 실패) 시 기존 NewPipe 추출로 폴백한다. NewPipe 도 실패하면
// PlayerViewModel 의 기존 에러/YouTube 앱 폴백 경로가 받는다. 재생당 1요청, 재시도 없음.
@Singleton
class DispatchingVideoExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val newPipe: NewPipeVideoExtractor,
    private val nasFetcher: NasStreamFetcher,
) : VideoExtractor {

    override suspend fun getChannelFeed(channelUrl: String): List<VideoItem> =
        newPipe.getChannelFeed(channelUrl)

    override suspend fun getStreamInfo(videoId: String): StreamInfo {
        val prefs = context.getSharedPreferences(PlayerPrefs.NAME, Context.MODE_PRIVATE)
        val nasEnabled = prefs.getBoolean(PlayerPrefs.KEY_NAS_EXTRACTOR_ENABLED, false)
        val baseUrl = prefs.getString(PlayerPrefs.KEY_NAS_BASE_URL, "").orEmpty()
        if (nasEnabled && baseUrl.isNotBlank()) {
            val secret = prefs.getString(PlayerPrefs.KEY_NAS_SECRET, "").orEmpty()
            val nasResult = try {
                nasFetcher.fetch(baseUrl, secret, videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "NAS 추출 실패 videoId=$videoId → NewPipe 폴백", e)
                null
            }
            if (nasResult != null) return nasResult
        }
        return newPipe.getStreamInfo(videoId)
    }

    companion object {
        private const val TAG = "SubFeedDispatchExtractor"
    }
}
