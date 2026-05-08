package com.minseo41.subfeed.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.minseo41.subfeed.MainActivity

@UnstableApi
class SubFeedMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // 4xx HTTP 응답엔 retry 없이 즉시 fail. YouTube 가 막은 영상의 child playlist 가 404 일 때
        // default policy 의 retry+backoff 로 5~7초 걸리던 것을 1~2초 안에 끝나도록.
        // 401/403/404 는 fallback 해도 같은 manifest 안의 다른 variant 도 동일 검증으로 똑같이 실패 →
        // retry/fallback 으로 시간 까먹지 말고 즉시 fail. (YouTube 가 막은 영상의 모든 variant 가 404 인
        // 케이스에서 prepare 가 5초+ 걸리던 것을 1초 안에 끝내기 위함.)
        // 429 / 5xx / network 류는 default 정책 유지 — 일시적 장애에 retry/fallback 가치 있음.
        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            private fun isPermanentClientError(ex: Throwable?): Boolean =
                ex is HttpDataSource.InvalidResponseCodeException &&
                    ex.responseCode in setOf(401, 403, 404)

            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (isPermanentClientError(loadErrorInfo.exception)) C.TIME_UNSET
                else super.getRetryDelayMsFor(loadErrorInfo)

            override fun getFallbackSelectionFor(
                fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
            ): LoadErrorHandlingPolicy.FallbackSelection? =
                if (isPermanentClientError(loadErrorInfo.exception)) null
                else super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 화면 꺼짐 + 백그라운드에서도 stream이 끊기지 않도록 wake/wifi lock 유지.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 알림 탭 시 PlayerScreen으로 deep-link 가도록, 현재 video의 mediaId 변경되면
        // sessionActivity 갱신.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshSessionActivity(mediaItem?.mediaId)
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(buildSessionActivity(null))
            .build()
    }

    private fun refreshSessionActivity(videoId: String?) {
        mediaSession?.setSessionActivity(buildSessionActivity(videoId))
    }

    private fun buildSessionActivity(videoId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            // singleTop launchMode와 결합 — MainActivity가 이미 떠있으면 onNewIntent로 들어감.
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!videoId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_VIDEO_ID, videoId)
            }
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val session = mediaSession ?: return
        // 사용자가 task를 swipe로 제거했고, 재생 중이 아니면 service 종료.
        // 재생 중이면 그대로 유지 — 백그라운드 재생 시나리오.
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
