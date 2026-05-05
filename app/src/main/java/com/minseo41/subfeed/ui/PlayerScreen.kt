package com.minseo41.subfeed.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also { p ->
            p.playWhenReady = true
        }
    }
    var isPlayingState by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var captionMenuOpen by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
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
        }
        exoPlayer.addListener(listener)
        onDispose {
            viewModel.savePositionNow(exoPlayer.currentPosition)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // 위치 업데이트 주기적 polling (UI Slider 동기화 + 30초 저장)
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(500L)
            if (!isSeeking) {
                currentPositionMs = exoPlayer.currentPosition
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            }
        }
    }
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(30_000L)
            if (exoPlayer.isPlaying) {
                viewModel.onPositionChanged(exoPlayer.currentPosition)
            }
        }
    }

    // streamInfo / captionSrtUri 변경 시 MediaItem 재구성
    LaunchedEffect(uiState.streamInfo, uiState.captionSrtUri) {
        val info = uiState.streamInfo ?: return@LaunchedEffect
        val savedPos = exoPlayer.currentPosition.takeIf { it > 0L } ?: uiState.resumePositionMs
        val raw = info.streamUrl
        val builder = when {
            raw.startsWith("hls:") -> MediaItem.Builder()
                .setUri(raw.removePrefix("hls:"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
            raw.startsWith("dash:") -> MediaItem.Builder()
                .setUri(raw.removePrefix("dash:"))
                .setMimeType(MimeTypes.APPLICATION_MPD)
            else -> MediaItem.Builder().setUri(raw)
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
        exoPlayer.setMediaItem(builder.build())
        exoPlayer.prepare()
        if (savedPos > 0L) exoPlayer.seekTo(savedPos)
        exoPlayer.playWhenReady = true
    }

    // 화질 선택 변경 시 TrackSelectionParameters 갱신
    LaunchedEffect(uiState.selectedMaxHeight) {
        val params: TrackSelectionParameters =
            if (uiState.selectedMaxHeight <= 0) {
                exoPlayer.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .build()
            } else {
                exoPlayer.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, uiState.selectedMaxHeight)
                    .build()
            }
        exoPlayer.trackSelectionParameters = params
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
            // 평소 모드는 단말 자동 회전 설정에 위임 (잠금 ON이면 portrait 유지, 풀려 있으면 단말 회전 따라감).
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // 화면 떠날 때 portrait + system bars 복귀
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // PIP 모드 변경 추적 — manifest configChanges로 PIP 진입/이탈 시 Configuration 새 인스턴스가 들어옴.
    // LocalConfiguration을 key로 잡아 LaunchedEffect가 다시 돌면서 Activity의 isInPictureInPictureMode 폴링.
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) {
        val isInPip = activity?.isInPictureInPictureMode == true
        viewModel.setInPipMode(isInPip)
        if (isInPip) controlsVisible = false
    }

    BackHandler(enabled = uiState.isFullscreen) {
        viewModel.setFullscreen(false)
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
            uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            uiState.error != null -> Text(
                uiState.error!!,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            else -> AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 더블탭 핫존 — 컨트롤이 가려질 때만 동작 (Compose Box stack에서 컨트롤 위에 두면 컨트롤이 우선)
        if (!uiState.isInPipMode) {
            DoubleTapSkipOverlay(
                onSingleTap = { controlsVisible = !controlsVisible },
                onSkipBack = {
                    val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                    exoPlayer.seekTo(target)
                },
                onSkipForward = {
                    val target = (exoPlayer.currentPosition + 10_000L)
                        .coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
                    exoPlayer.seekTo(target)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 컨트롤 오버레이 — visible할 때만
        if (controlsVisible && !uiState.isInPipMode) {
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
                        qualityLabel = if (uiState.selectedMaxHeight <= 0) "자동" else "${uiState.selectedMaxHeight}p",
                        qualityEnabled = uiState.isHlsStream,
                        captionEnabled = uiState.selectedCaptionLanguage != null,
                        backgroundPlaybackEnabled = uiState.backgroundPlaybackEnabled,
                        onBackClick = {
                            viewModel.savePositionNow(exoPlayer.currentPosition)
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
                                exoPlayer.seekTo(0L)
                                viewModel.restartFromBeginning()
                            },
                            onDismiss = { viewModel.dismissResumeBanner() },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PlayerControls(
                            isPlaying = isPlayingState,
                            onPlayPauseClick = {
                                exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                            },
                            onRewindClick = {
                                val t = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(t)
                            },
                            onForwardClick = {
                                val t = (exoPlayer.currentPosition + 10_000L)
                                    .coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
                                exoPlayer.seekTo(t)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    PlayerBottomBar(
                        currentPositionMs = currentPositionMs,
                        durationMs = durationMs,
                        isFullscreen = uiState.isFullscreen,
                        onSeek = { ms -> currentPositionMs = ms },
                        onSeekStart = { isSeeking = true },
                        onSeekFinished = {
                            exoPlayer.seekTo(currentPositionMs)
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
                    QualityOption(if (h == 0) "자동" else "${h}p", h)
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

// Compose의 LocalContext는 ContextWrapper일 때가 있어 직접 cast가 null로 떨어진다.
// baseContext를 거슬러 올라가며 ComponentActivity를 찾는다.
private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
