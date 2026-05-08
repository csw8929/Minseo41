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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.minseo41.subfeed.service.SubFeedMediaSessionService
import com.minseo41.subfeed.ui.player.CaptionMenu
import com.minseo41.subfeed.ui.player.DoubleTapSkipOverlay
import com.minseo41.subfeed.ui.player.PlayerBottomBar
import com.minseo41.subfeed.ui.player.PlayerControls
import com.minseo41.subfeed.ui.player.PlayerTopBar
import com.minseo41.subfeed.ui.player.QualityMenu
import com.minseo41.subfeed.ui.player.QualityOption
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val playerPrefs = remember(context) {
        context.getSharedPreferences(PlayerPrefs.NAME, Context.MODE_PRIVATE)
    }

    // Service에 살아있는 ExoPlayer에 MediaController로 binding.
    // 화면 꺼짐 / 백그라운드에서도 service가 foreground로 유지되어 audio가 끊기지 않음.
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    LaunchedEffect(videoId) { viewModel.loadVideo(videoId) }
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
            val ctrl = mediaController
            mediaController = null
            future.cancel(true)
            // pause + release는 다음 main thread tick으로 미뤄 popBackStack 애니메이션을 막지 않게 함.
            if (ctrl != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { ctrl.pause() }
                    runCatching { ctrl.release() }
                }
            }
        }
    }

    var isPlayingState by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    // setMediaItem 직후 controller.currentPosition이 잠깐 0으로 reset되는 사이 polling이
    // 그 0을 읽어 UI에 깜빡임이 발생. 이 시각 이전엔 polling을 무시하고 UI를 보존.
    var skipPositionPollUntilMs by remember { mutableStateOf(0L) }
    // back 누른 직후 PlayerView surface 가 잠깐 더 살아있어 영상이 보이는 깜빡임 방지.
    // true 이면 PlayerView 대신 검정 박스만 표시 → popBackStack 후 화면 전환.
    var isExiting by remember { mutableStateOf(false) }
    // 새 영상 진입 vs 같은 영상 내 자막 변경 구분 — 새 영상 진입 시 controller.currentPosition은
    // 이전 영상의 위치라 신뢰하면 안 됨.
    var lastPreparedVideoId by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var captionMenuOpen by remember { mutableStateOf(false) }
    // seek throttle — 드래그 중 매 픽셀마다 seekTo 호출하면 HLS chunk fetch 비용이 큼.
    // 50ms 간격으로만 실제 seek, 그 사이 변경은 슬라이더 thumb 위치만 업데이트.
    var lastSeekAtMs by remember { mutableStateOf(0L) }

    DisposableEffect(mediaController) {
        val controller = mediaController ?: return@DisposableEffect onDispose { }
        // controller 연결 시 초기 상태 sync.
        // 단, 서비스가 아직 이전 영상의 MediaItem 을 들고 있는 동안엔 그 position 을 UI 에 흘리면
        // 새 영상 진입 직후 잠시 이전 영상의 seekbar 위치가 보인다. mediaId 일치할 때만 sync.
        if (controller.currentMediaItem?.mediaId == videoId) {
            isPlayingState = controller.isPlaying
            currentPositionMs = controller.currentPosition
            val d = controller.duration
            if (d > 0L) durationMs = d
        } else {
            isPlayingState = false
            currentPositionMs = 0L
            durationMs = 0L
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingState = isPlaying
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("SubFeedPlayer", "onPlayerError ${error.errorCodeName}: ${error.message}", error)
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

    // 위치 polling — controller 활성 시에만, mediaId 일치할 때만 (이전 영상 position 누설 방지).
    // duration 은 게이트 밖에서 갱신하되, 0/unknown 으로 덮어쓰지 않는다. clearMediaItems→setMediaItem
    // reprepare 사이 controller.duration 이 잠깐 0 으로 떨어져서 썸이 11분→0→11분 점프하는 깜빡임 방지.
    LaunchedEffect(mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        while (true) {
            delay(200L)
            if (controller.currentMediaItem?.mediaId == videoId) {
                val d = controller.duration
                if (d > 0L) durationMs = d
                if (!isSeeking && System.currentTimeMillis() >= skipPositionPollUntilMs) {
                    currentPositionMs = controller.currentPosition
                }
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
        val isNewVideo = videoId != lastPreparedVideoId
        // 새 영상 진입 시: controller.currentPosition은 이전 영상 위치라 무시. saved 위치만 사용.
        // 같은 영상 안에서 자막 변경 시: controller.currentPosition (현재 재생 위치) 보존.
        val savedPos = if (isNewVideo) {
            uiState.resumePositionMs
        } else {
            controller.currentPosition.takeIf { it > 0L } ?: uiState.resumePositionMs
        }
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
        // setMediaItem 후 seekTo 패턴은 seekbar가 0으로 갔다가 saved로 점프하는 깜빡임을 만든다.
        // startPositionMs를 직접 넘겨 처음부터 그 위치에서 prepare하도록 한다.
        val startPos = savedPos.coerceAtLeast(0L)
        // 새 영상 진입 시: 이전 PlayerScreen이 남긴 paused state + cached source 명시적 clear.
        // clearMediaItems 가 없으면 ExoPlayer가 같은 mediaId의 이전 manifest cache를 재사용해
        // expired HLS chunk URL을 fetch하다 HTTP 403 으로 죽는 케이스가 있다.
        if (isNewVideo) {
            runCatching { controller.stop() }
            runCatching { controller.clearMediaItems() }
        }
        controller.setMediaItem(builder.build(), startPos)
        // prepare 호출 전에 playWhenReady=true를 set해서 STATE_READY 진입 즉시 재생 시작.
        controller.playWhenReady = true
        controller.prepare()
        currentPositionMs = startPos
        skipPositionPollUntilMs = System.currentTimeMillis() + 1_200L
        lastPreparedVideoId = videoId
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

    // 전체화면 토글 + 회전 잠금 토글 시 orientation + system bars
    DisposableEffect(uiState.isFullscreen, uiState.orientationLocked) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val window = act.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (uiState.isFullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // SCREEN_ORIENTATION_LOCKED 는 호출 시점의 현재 orientation 을 잠근다.
            // 풀스크린 해제 직후엔 아직 landscape 라 LOCKED 를 쓰면 가로 그대로 잠긴다.
            // 잠금 의도는 작은 플레이어 UI(세로) 를 유지하는 것이므로 PORTRAIT 으로 명시.
            act.requestedOrientation = if (uiState.orientationLocked) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
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
            isExiting -> Unit  // back 직후: PlayerView 그리지 않고 검정 박스만 표시
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
                    view.subtitleView?.setFractionalTextSize(
                        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * uiState.selectedCaptionScale
                    )
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
                        orientationLocked = uiState.orientationLocked,
                        onBackClick = {
                            viewModel.savePositionNow(controller.currentPosition)
                            when {
                                uiState.isFullscreen -> viewModel.setFullscreen(false)
                                playerPrefs.getString(
                                    PlayerPrefs.KEY_BACK_ACTION,
                                    PlayerPrefs.BACK_ACTION_STOP,
                                ) == PlayerPrefs.BACK_ACTION_PIP &&
                                    tryEnterPip(activity) -> Unit
                                else -> {
                                    isExiting = true
                                    runCatching { controller.pause() }
                                    onBack()
                                }
                            }
                        },
                        onQualityClick = { qualityMenuOpen = true },
                        onCaptionClick = { captionMenuOpen = true },
                        onPipClick = { tryEnterPip(activity) },
                        onToggleOrientationLock = { viewModel.toggleOrientationLocked() },
                    )
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
                        // live scrub + 50ms throttle.
                        // 드래그 시작은 isSeeking 전이로 감지 — Slider.onValueChange 가 매 픽셀 호출되므로
                        // start callback 분리는 throttle 리셋을 매 tick 유발해 throttle 자체를 무력화시킨다.
                        onSeek = { ms ->
                            currentPositionMs = ms
                            if (!isSeeking) {
                                isSeeking = true
                                lastSeekAtMs = 0L
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastSeekAtMs >= 50L) {
                                lastSeekAtMs = now
                                controller.seekTo(ms)
                            }
                        },
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
                selectedScale = uiState.selectedCaptionScale,
                onSelect = {
                    viewModel.selectCaption(it)
                    captionMenuOpen = false
                },
                onSelectScale = { scale ->
                    viewModel.selectCaptionScale(scale)
                    if (uiState.selectedCaptionLanguage == null) {
                        val tracks = uiState.streamInfo?.captionTracks.orEmpty()
                        val pick = tracks.firstOrNull { it.languageCode.startsWith("ko") }
                            ?: tracks.firstOrNull { it.languageCode.startsWith("en") }
                            ?: tracks.firstOrNull()
                        pick?.let { viewModel.selectCaption(it) }
                    }
                    captionMenuOpen = false
                },
                onDismiss = { captionMenuOpen = false },
            )
        }
    }
}

private fun tryEnterPip(activity: ComponentActivity?): Boolean {
    if (activity == null) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    return runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
