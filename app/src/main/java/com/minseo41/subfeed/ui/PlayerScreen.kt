package com.minseo41.subfeed.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.minseo41.subfeed.service.SubFeedMediaSessionService
import com.minseo41.subfeed.ui.player.CaptionMenu
import com.minseo41.subfeed.ui.player.DoubleTapSkipOverlay
import com.minseo41.subfeed.ui.player.PlayerBottomBar
import com.minseo41.subfeed.ui.player.PlayerControls
import com.minseo41.subfeed.ui.player.PlayerTopBar
import com.minseo41.subfeed.ui.player.QualityMenu
import com.minseo41.subfeed.ui.player.QualityOption
import com.minseo41.subfeed.ui.player.ResumeBanner
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }

    LaunchedEffect(videoId) { viewModel.loadVideo(videoId) }

    // Service에 살아있는 ExoPlayer에 MediaController로 binding.
    // 화면 꺼짐 / 백그라운드에서도 service가 foreground로 유지되어 audio가 끊기지 않음.
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SubFeedMediaSessionService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            { runCatching { mediaController = future.get() } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            // 화면 떠나도 service player는 유지(백그라운드 재생). controller만 disconnect.
            mediaController?.release()
            mediaController = null
            future.cancel(true)
        }
    }

    var isPlayingState by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var captionMenuOpen by remember { mutableStateOf(false) }
    // seek throttle — 드래그 중 매 픽셀마다 seekTo 호출하면 HLS chunk fetch 비용이 큼.
    // 50ms 간격으로만 실제 seek, 그 사이 변경은 슬라이더 thumb 위치만 업데이트.
    var lastSeekAtMs by remember { mutableStateOf(0L) }

    DisposableEffect(mediaController) {
        val controller = mediaController ?: return@DisposableEffect onDispose { }
        // controller 연결 시 초기 상태 sync
        isPlayingState = controller.isPlaying
        currentPositionMs = controller.currentPosition
        durationMs = controller.duration.coerceAtLeast(0L)

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingState = isPlaying
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val heights = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group ->
                        (0 until group.length).map { group.getTrackFormat(it).height }
                    }
                    .filter { it > 0 }
                viewModel.updateAvailableQualities(heights)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                viewModel.updateCurrentVideoHeight(videoSize.height)
            }
        }
        controller.addListener(listener)
        onDispose {
            // 떠나기 전 시청 위치 저장 — controller 가 살아있을 때 마지막 position 읽기
            viewModel.savePositionNow(controller.currentPosition)
            controller.removeListener(listener)
        }
    }

    // 위치 polling — controller 활성 시에만
    LaunchedEffect(mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        while (true) {
            delay(500L)
            if (!isSeeking) {
                currentPositionMs = controller.currentPosition
                durationMs = controller.duration.coerceAtLeast(0L)
            }
        }
    }
    LaunchedEffect(mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        while (true) {
            delay(30_000L)
            if (controller.isPlaying) {
                viewModel.onPositionChanged(controller.currentPosition)
            }
        }
    }

    // streamInfo / captionSrtUri 변경 시 MediaItem 재구성
    LaunchedEffect(mediaController, uiState.streamInfo, uiState.captionSrtUri) {
        val controller = mediaController ?: return@LaunchedEffect
        val info = uiState.streamInfo ?: return@LaunchedEffect
        val savedPos = controller.currentPosition.takeIf { it > 0L } ?: uiState.resumePositionMs
        val raw = info.streamUrl
        // mediaId에 videoId를 박아 service가 deep-link PendingIntent 갱신에 사용.
        val builder = when {
            raw.startsWith("hls:") -> MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(raw.removePrefix("hls:"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
            raw.startsWith("dash:") -> MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(raw.removePrefix("dash:"))
                .setMimeType(MimeTypes.APPLICATION_MPD)
            else -> MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(raw)
        }
        uiState.captionSrtUri?.let { uri ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(uiState.selectedCaptionLanguage ?: "und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        controller.setMediaItem(builder.build())
        controller.prepare()
        if (savedPos > 0L) controller.seekTo(savedPos)
        controller.playWhenReady = true
    }

    // 화질 선택 변경 시 TrackSelectionParameters 갱신
    LaunchedEffect(mediaController, uiState.selectedMaxHeight) {
        val controller = mediaController ?: return@LaunchedEffect
        val params: TrackSelectionParameters =
            if (uiState.selectedMaxHeight <= 0) {
                controller.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .build()
            } else {
                controller.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, uiState.selectedMaxHeight)
                    .build()
            }
        controller.trackSelectionParameters = params
    }

    // 컨트롤 자동 숨김 — 5초 후 재생 중일 때만
    LaunchedEffect(controlsVisible, isPlayingState) {
        if (controlsVisible && isPlayingState) {
            delay(5_000L)
            controlsVisible = false
        }
    }

    // 전체화면 토글 시 orientation + system bars
    DisposableEffect(uiState.isFullscreen) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val window = act.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (uiState.isFullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // PIP 모드 변경 추적
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) {
        val isInPip = activity?.isInPictureInPictureMode == true
        viewModel.setInPipMode(isInPip)
        if (isInPip) controlsVisible = false
    }

    BackHandler(enabled = uiState.isFullscreen) {
        viewModel.setFullscreen(false)
    }

    // 영상 재생 중일 때만 화면 꺼짐 방지 (일시정지/이탈 시 자동 해제).
    DisposableEffect(activity, isPlayingState) {
        val act = activity ?: return@DisposableEffect onDispose { }
        if (isPlayingState) {
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (uiState.isFullscreen) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.systemBars)
            ),
    ) {
        when {
            uiState.isLoading || mediaController == null ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            uiState.error != null -> Text(
                uiState.error!!,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            else -> AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                    }
                },
                update = { view ->
                    view.player = mediaController
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 더블탭 핫존 — 컨트롤이 가려질 때만 동작
        val controller = mediaController
        if (controller != null && !uiState.isInPipMode) {
            DoubleTapSkipOverlay(
                onSingleTap = { controlsVisible = !controlsVisible },
                onSkipBack = {
                    val target = (controller.currentPosition - 10_000L).coerceAtLeast(0L)
                    controller.seekTo(target)
                },
                onSkipForward = {
                    val target = (controller.currentPosition + 10_000L)
                        .coerceAtMost(controller.duration.coerceAtLeast(0L))
                    controller.seekTo(target)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 컨트롤 오버레이 — visible할 때만
        if (controller != null && controlsVisible && !uiState.isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = false })
                    },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PlayerTopBar(
                        title = "재생 중",
                        qualityLabel = when {
                            uiState.selectedMaxHeight > 0 -> "${uiState.selectedMaxHeight}p"
                            uiState.currentVideoHeight > 0 -> "자동 ${uiState.currentVideoHeight}p"
                            else -> "자동"
                        },
                        qualityEnabled = uiState.isHlsStream,
                        captionEnabled = uiState.selectedCaptionLanguage != null,
                        backgroundPlaybackEnabled = uiState.backgroundPlaybackEnabled,
                        onBackClick = {
                            viewModel.savePositionNow(controller.currentPosition)
                            if (uiState.isFullscreen) {
                                viewModel.setFullscreen(false)
                            } else {
                                onBack()
                            }
                        },
                        onQualityClick = { qualityMenuOpen = true },
                        onCaptionClick = { captionMenuOpen = true },
                        onPipClick = { tryEnterPip(activity) },
                        onToggleBackgroundPlayback = { viewModel.toggleBackgroundPlayback() },
                    )
                    if (uiState.showResumeBanner && uiState.resumePositionMs > 0L) {
                        ResumeBanner(
                            resumePositionMs = uiState.resumePositionMs,
                            onRestartFromBeginning = {
                                controller.seekTo(0L)
                                viewModel.restartFromBeginning()
                            },
                            onDismiss = { viewModel.dismissResumeBanner() },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PlayerControls(
                            isPlaying = isPlayingState,
                            onPlayPauseClick = {
                                controller.playWhenReady = !controller.playWhenReady
                            },
                            onRewindClick = {
                                val t = (controller.currentPosition - 10_000L).coerceAtLeast(0L)
                                controller.seekTo(t)
                            },
                            onForwardClick = {
                                val t = (controller.currentPosition + 10_000L)
                                    .coerceAtMost(controller.duration.coerceAtLeast(0L))
                                controller.seekTo(t)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    PlayerBottomBar(
                        currentPositionMs = currentPositionMs,
                        durationMs = durationMs,
                        isFullscreen = uiState.isFullscreen,
                        // live scrub + 50ms throttle — leading edge에서 즉시 seek, 이후 50ms 간격으로만.
                        onSeek = { ms ->
                            currentPositionMs = ms
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastSeekAtMs >= 50L) {
                                lastSeekAtMs = now
                                controller.seekTo(ms)
                            }
                        },
                        onSeekStart = { isSeeking = true; lastSeekAtMs = 0L },
                        onSeekFinished = {
                            // throttle로 빠진 마지막 위치 보정 — 항상 최종 currentPositionMs로 한 번 더 seek.
                            controller.seekTo(currentPositionMs)
                            isSeeking = false
                        },
                        onToggleFullscreen = { viewModel.setFullscreen(!uiState.isFullscreen) },
                    )
                }
            }
        }

        if (qualityMenuOpen) {
            QualityMenu(
                options = uiState.availableQualityHeights.map { h ->
                    val label = when {
                        h == 0 && uiState.currentVideoHeight > 0 ->
                            "자동 (현재 ${uiState.currentVideoHeight}p)"
                        h == 0 -> "자동"
                        else -> "${h}p"
                    }
                    QualityOption(label, h)
                },
                selectedMaxHeight = uiState.selectedMaxHeight,
                onSelect = {
                    viewModel.selectQuality(it.maxHeight)
                    qualityMenuOpen = false
                },
                onDismiss = { qualityMenuOpen = false },
            )
        }

        if (captionMenuOpen) {
            CaptionMenu(
                tracks = uiState.streamInfo?.captionTracks.orEmpty(),
                selectedLanguageCode = uiState.selectedCaptionLanguage,
                onSelect = {
                    viewModel.selectCaption(it)
                    captionMenuOpen = false
                },
                onDismiss = { captionMenuOpen = false },
            )
        }
    }
}

private fun tryEnterPip(activity: ComponentActivity?) {
    if (activity == null) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    runCatching { activity.enterPictureInPictureMode(params) }
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
