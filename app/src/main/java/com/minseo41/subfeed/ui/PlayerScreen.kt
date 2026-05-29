package com.minseo41.subfeed.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.minseo41.subfeed.service.SubFeedMediaSessionService
import com.minseo41.subfeed.ui.player.CaptionMenu
import com.minseo41.subfeed.ui.player.ChapterMenu
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
    // chunk 단계 403 (Shorts 등 YouTube PoToken 일시 거부) 자동 재시도 — 같은 stream URL 로
    // 1초 대기 후 setMediaItem + prepare 재실행. 영상별 최대 2회.
    var playbackRetryCount by remember { mutableStateOf(0) }
    var retryTriggerAt by remember { mutableStateOf(0L) }
    var lastHandledRetryAt by remember { mutableStateOf(0L) }
    LaunchedEffect(videoId) {
        playbackRetryCount = 0
        retryTriggerAt = 0L
        lastHandledRetryAt = 0L
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var captionMenuOpen by remember { mutableStateOf(false) }
    var chapterMenuOpen by remember { mutableStateOf(false) }
    // seek throttle — 드래그 중 매 픽셀마다 seekTo 호출하면 HLS chunk fetch 비용이 큼.
    // 50ms 간격으로만 실제 seek, 그 사이 변경은 슬라이더 thumb 위치만 업데이트.
    var lastSeekAtMs by remember { mutableStateOf(0L) }
    // 회전 잠금이 켜진 시점의 실제 화면 orientation 을 기억. SCREEN_ORIENTATION_LOCKED 는
    // 호출 시점의 현재 방향을 그대로 잠그는데, 풀스크린(=강제 LANDSCAPE) 해제 직후에 호출되면
    // 가로로 잠겨버린다. 사용자의 "잠근 그 시점 방향" 의도를 보존하려면 토글 순간 캡처해 둬야 함.
    // 초기값은 mount 시점의 실제 orientation — prefs 에 잠금이 ON 인 채로 들어와도 정확히 복구.
    var lockedOrientation by remember {
        val initial = activity?.resources?.configuration?.orientation
        val orientation = if (initial == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        mutableStateOf(orientation)
    }

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
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onBack()
                if (playbackState == Player.STATE_READY && playbackRetryCount > 0 && lastHandledRetryAt > 0L) {
                    android.util.Log.w(
                        "SubFeedPlayer",
                        "retry-403 SUCCESS after $playbackRetryCount attempts for videoId=$videoId",
                    )
                    lastHandledRetryAt = 0L
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val info = viewModel.uiState.value.streamInfo
                val urlHead = info?.streamUrl?.take(120)?.replace("\n", " ") ?: "<none>"
                val isHls = info?.streamUrl?.startsWith("hls:") == true
                val httpStatus = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: -1
                val httpUrl = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.dataSpec?.uri?.toString()
                android.util.Log.e(
                    "SubFeedPlayer",
                    "onPlayerError code=${error.errorCode}(${error.errorCodeName}) msg=${error.message} " +
                        "videoId=$videoId isHls=$isHls streamHead=$urlHead " +
                        "httpStatus=$httpStatus httpUrl=$httpUrl " +
                        "cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message?.take(200)}",
                    error,
                )
                val retryable403 = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && httpStatus == 403
                if (retryable403 && playbackRetryCount < 2) {
                    playbackRetryCount++
                    android.util.Log.w(
                        "SubFeedPlayer",
                        "retry-403 scheduling #$playbackRetryCount in 1s for videoId=$videoId (httpStatus=403)",
                    )
                    retryTriggerAt = System.currentTimeMillis()
                    return
                }
                if (retryable403) {
                    android.util.Log.w(
                        "SubFeedPlayer",
                        "retry-403 exhausted after $playbackRetryCount attempts — surfacing error for videoId=$videoId",
                    )
                }
                viewModel.setPlaybackError(friendlyPlaybackMessage(error))
                runCatching { controller.stop() }
                runCatching { controller.clearMediaItems() }
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val heights = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group ->
                        (0 until group.length).map { group.getTrackFormat(it).height }
                    }
                    .filter { it > 0 }
                viewModel.updateAvailableQualities(heights)
                tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .forEach { group ->
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val f = group.getTrackFormat(i)
                                val bitrate = if (f.bitrate != androidx.media3.common.Format.NO_VALUE) f.bitrate else f.averageBitrate
                                android.util.Log.d(
                                    "SubFeedPlayer",
                                    "selected video track height=${f.height} bitrate=${bitrate} codecs=${f.codecs}",
                                )
                            }
                        }
                    }
                // 새 영상 진입 시 사용자의 화질 선택을 새 트랙에도 재적용
                applyQualitySelection(controller, viewModel.uiState.value.selectedMaxHeight)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                android.util.Log.d(
                    "SubFeedPlayer",
                    "videoSize ${videoSize.width}x${videoSize.height}",
                )
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

    // streamInfo 변경 시에만 MediaItem 재구성. 자막은 Compose 오버레이에서 직접 렌더링하므로
    // captionSrtUri 같은 키가 사라져 자막 toggle 시 영상이 검정으로 깜빡이지 않는다.
    // 추가로 retryTriggerAt 변경 시에도 발동 — 403 chunk 에러 자동 재시도 path.
    LaunchedEffect(mediaController, uiState.streamInfo, retryTriggerAt) {
        val controller = mediaController ?: return@LaunchedEffect
        val info = uiState.streamInfo ?: return@LaunchedEffect
        val isRetryRun = retryTriggerAt > 0L && retryTriggerAt != lastHandledRetryAt
        var retryFromPositionMs = 0L
        if (isRetryRun) {
            // delay 전에 현재 위치를 캡처 — delay 후엔 clearMediaItems로 position이 초기화됨.
            retryFromPositionMs = controller.currentPosition
            android.util.Log.w(
                "SubFeedPlayer",
                "retry-403 waiting 1s before reprepare — count=$playbackRetryCount videoId=$videoId posMs=$retryFromPositionMs",
            )
            delay(1000L)
            lastHandledRetryAt = retryTriggerAt
            // 강제로 isNewVideo path 진입 → stop+clear+setMediaItem+prepare 풀스택 재실행.
            lastPreparedVideoId = null
            android.util.Log.w("SubFeedPlayer", "retry-403 executing reprepare for videoId=$videoId")
        }
        val isNewVideo = videoId != lastPreparedVideoId
        // 새 영상 진입 시: controller.currentPosition은 이전 영상 위치라 무시. saved 위치만 사용.
        // 재시도 시: delay 전에 캡처한 위치에서 재개 (resumePositionMs는 초기 로드 시 값이라 부정확).
        val savedPos = when {
            isRetryRun && retryFromPositionMs > 0L -> retryFromPositionMs
            isNewVideo -> uiState.resumePositionMs
            else -> controller.currentPosition.takeIf { it > 0L } ?: uiState.resumePositionMs
        }
        val raw = info.streamUrl
        val metadata = MediaMetadata.Builder()
            .setTitle(uiState.videoTitle.takeIf { it.isNotEmpty() })
            .setArtist(uiState.channelName.takeIf { it.isNotEmpty() })
            .setArtworkUri(uiState.thumbnailUrl.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) })
            .build()
        // mediaId에 videoId를 박아 service가 deep-link PendingIntent 갱신에 사용.
        val builder = when {
            raw.startsWith("hls:") -> MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(raw.removePrefix("hls:"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(metadata)
            raw.startsWith("dash:") -> MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(raw.removePrefix("dash:"))
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setMediaMetadata(metadata)
            else -> MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(raw)
                .setMediaMetadata(metadata)
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

    // 화질 선택 변경 시 TrackSelectionParameters 갱신.
    // setMaxVideoSize 는 "상한선" 이라 ABR 이 대역폭에 따라 더 낮은 화질을 선택 가능 → "선택=실제" 가 안 맞음.
    // TrackSelectionOverride 로 그 height 의 track 을 명시적으로 잠궈 ABR 우회.
    LaunchedEffect(mediaController, uiState.selectedMaxHeight) {
        val controller = mediaController ?: return@LaunchedEffect
        applyQualitySelection(controller, uiState.selectedMaxHeight)
    }

    // 컨트롤 자동 숨김 — 5초 후 재생 중일 때만
    LaunchedEffect(controlsVisible, isPlayingState) {
        if (controlsVisible && isPlayingState) {
            delay(5_000L)
            controlsVisible = false
        }
    }

    // 전체화면 토글 + 회전 잠금 토글 시 orientation + system bars
    DisposableEffect(uiState.isFullscreen, uiState.orientationLocked, lockedOrientation) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val window = act.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (uiState.isFullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // 잠금 켜진 시점에 캡처해 둔 lockedOrientation 을 사용 — 풀스크린 해제 후
            // 가로/세로 어느 쪽이든 사용자가 의도한 방향으로 복구.
            act.requestedOrientation = if (uiState.orientationLocked) {
                lockedOrientation
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            // fullscreen 시 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE로 설정된 상태가 남아있으면
            // show() 후에도 자동으로 다시 숨어버린다. BEHAVIOR_DEFAULT로 리셋해야 영구 표시.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            // enableEdgeToEdge()로 status bar가 투명해지므로 검정 배경 위에서
            // 아이콘이 보이도록 흰색 아이콘(light=false)으로 전환.
            controller.isAppearanceLightStatusBars = false
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = true  // 앱 기본 테마(라이트) 복원
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // PIP 모드 변경 추적
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) {
        val isInPip = activity?.isInPictureInPictureMode == true
        viewModel.setInPipMode(isInPip)
        if (isInPip) controlsVisible = false
    }

    // 시스템 back key — 풀스크린이면 풀스크린 해제, 그 외엔 일시정지 + 리스트로.
    BackHandler {
        if (uiState.isFullscreen) {
            viewModel.setFullscreen(false)
        } else {
            mediaController?.let { ctrl ->
                viewModel.savePositionNow(ctrl.currentPosition)
                runCatching { ctrl.pause() }
            }
            isExiting = true
            onBack()
        }
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
            uiState.error != null -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    uiState.error!!,
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
                val context = LocalContext.current
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                        )
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("브라우저로 열기")
                }
                OutlinedButton(
                    onClick = {
                        isExiting = true
                        runCatching { mediaController?.stop() }
                        onBack()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("목록으로")
                }
            }
            else -> AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        subtitleView?.visibility = android.view.View.GONE
                    }
                },
                update = { view ->
                    view.player = mediaController
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 자막 오버레이 — ExoPlayer 의 SubtitleView 대신 Compose 에서 직접 렌더링.
        // currentPositionMs (200ms 폴링) 로 active cue 찾아 Text 표시. toggle/언어 변경이
        // MediaItem 재준비 없이 즉시 반영되고, 영상 검정 깜빡임도 사라짐.
        val activeCue = if (uiState.captionCues.isEmpty()) null
            else uiState.captionCues.firstOrNull {
                currentPositionMs in it.startMs..it.endMs
            }
        if (activeCue != null && !uiState.isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 96.dp, start = 24.dp, end = 24.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = activeCue.text,
                    color = Color.White,
                    fontSize = (16f * uiState.selectedCaptionScale).sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 비전체화면 모드에서 컨트롤이 숨겨졌을 때도 좌상단에 제목 노출.
        // 컨트롤이 보일 때는 PlayerTopBar 가 같은 제목을 표시하므로 중복 회피.
        if (!uiState.isFullscreen && !uiState.isInPipMode && !controlsVisible && uiState.videoTitle.isNotEmpty()) {
            Text(
                text = uiState.videoTitle,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 280.dp)
                    .background(
                        Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // 더블탭 핫존 — 에러 상태에선 비활성 (메시지/버튼 클릭을 가리지 않게)
        val controller = mediaController
        if (controller != null && !uiState.isInPipMode && uiState.error == null) {
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

        // 컨트롤 오버레이 — visible할 때만, 에러 상태에선 가림
        if (controller != null && controlsVisible && !uiState.isInPipMode && uiState.error == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = false })
                    },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PlayerTopBar(
                        title = uiState.videoTitle.ifEmpty { "재생 중" },
                        qualityLabel = when {
                            uiState.selectedMaxHeight > 0 -> "${uiState.selectedMaxHeight}p"
                            uiState.currentVideoHeight > 0 -> "자동 ${uiState.currentVideoHeight}p"
                            else -> "자동"
                        },
                        qualityEnabled = true,
                        captionEnabled = uiState.selectedCaptionLanguage != null,
                        orientationLocked = uiState.orientationLocked,
                        chaptersAvailable = (uiState.streamInfo?.chapters?.isNotEmpty() == true),
                        onBackClick = {
                            viewModel.savePositionNow(controller.currentPosition)
                            if (uiState.isFullscreen) {
                                viewModel.setFullscreen(false)
                            } else {
                                isExiting = true
                                runCatching { controller.pause() }
                                onBack()
                            }
                        },
                        onQualityClick = { qualityMenuOpen = true },
                        onCaptionClick = { captionMenuOpen = true },
                        onChapterClick = { chapterMenuOpen = true },
                        onPipClick = { tryEnterPip(activity) },
                        onToggleOrientationLock = {
                            // OFF→ON 진입 직전에 현재 orientation 을 동기 캡처.
                            // LaunchedEffect 로 캡처하면 DisposableEffect 가 먼저 적용돼 race 발생
                            // (가로에서 잠금 → 잠깐 세로 → 다시 가로 깜빡임).
                            if (!uiState.orientationLocked && !uiState.isFullscreen) {
                                val o = activity?.resources?.configuration?.orientation
                                lockedOrientation = if (o == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                }
                            }
                            viewModel.toggleOrientationLocked()
                        },
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

        if (chapterMenuOpen) {
            val chapters = uiState.streamInfo?.chapters.orEmpty()
            ChapterMenu(
                chapters = chapters,
                currentPositionMs = currentPositionMs,
                onSelect = { ch ->
                    mediaController?.seekTo(ch.timeMs)
                    chapterMenuOpen = false
                },
                onDismiss = { chapterMenuOpen = false },
            )
        }
    }
}

// 화질 선택 적용. 0 = 자동 (ABR), 그 외 = 해당 height 의 track 을 override 로 강제.
// `setMaxVideoSize` 는 상한선 + ABR 이라 대역폭 부족 시 더 낮은 트랙으로 떨어져 "선택 = 실제" 가
// 불일치하던 문제를 해소.
private fun applyQualitySelection(controller: MediaController, selectedMaxHeight: Int) {
    val builder = controller.trackSelectionParameters.buildUpon()
    if (selectedMaxHeight <= 0) {
        // 자동: override / size constraint 모두 제거 → ABR 동작
        builder.clearVideoSizeConstraints()
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        controller.trackSelectionParameters = builder.build()
        return
    }
    // 특정 화질: 해당 height 의 track 을 명시적으로 잠금
    val tracks = controller.currentTracks
    val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
    if (videoGroup == null) {
        // 트랙 정보가 아직 없으면 (loading 중) override 못 만듦. onTracksChanged 에서 재시도.
        return
    }
    val targetIndex = (0 until videoGroup.length).firstOrNull { i ->
        videoGroup.getTrackFormat(i).height == selectedMaxHeight
    }
    if (targetIndex != null) {
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        builder.addOverride(
            TrackSelectionOverride(videoGroup.mediaTrackGroup, listOf(targetIndex))
        )
        // override 가 size constraint 보다 우선이지만, size constraint 가 남아있으면 혼동되니 제거.
        builder.clearVideoSizeConstraints()
        controller.trackSelectionParameters = builder.build()
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

// ExoPlayer PlaybackException → 사용자 친화 메시지.
// 404/parse 류 — YouTube 가 외부 클라이언트의 stream URL 을 막은 영상 (PoToken 미보유 영상에 자주 발생).
// 네트워크 류 — 단말 네트워크 회복 후 재시도 안내.
// 그 외 — errorCodeName 그대로 노출하여 추후 진단 단서 제공.
private fun friendlyPlaybackMessage(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
        "이 영상은 재생할 수 없습니다.\n(YouTube 외부 재생 차단)"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
        "네트워크 오류입니다."
    else ->
        "재생 오류 (${error.errorCodeName})"
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
