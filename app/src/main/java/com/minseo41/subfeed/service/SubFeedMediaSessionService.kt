package com.minseo41.subfeed.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.minseo41.subfeed.MainActivity

class SubFeedMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
