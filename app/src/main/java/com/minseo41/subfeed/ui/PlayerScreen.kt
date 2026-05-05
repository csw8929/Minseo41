package com.minseo41.subfeed.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(videoId) { viewModel.loadVideo(videoId) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.savePositionNow(exoPlayer.currentPosition)
            exoPlayer.release()
        }
    }

    LaunchedEffect(uiState.streamUrl) {
        val url = uiState.streamUrl ?: return@LaunchedEffect
        val mediaItem = when {
            url.startsWith("dash:") -> MediaItem.Builder()
                .setUri(url.removePrefix("dash:"))
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .build()
            url.startsWith("hls:") -> MediaItem.Builder()
                .setUri(url.removePrefix("hls:"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            else -> MediaItem.fromUri(url)
        }
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (uiState.resumePositionMs > 0L) {
            exoPlayer.seekTo(uiState.resumePositionMs)
        }
        exoPlayer.playWhenReady = true
    }

    // 30초마다 위치 저장
    LaunchedEffect(exoPlayer) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            if (exoPlayer.isPlaying) {
                viewModel.onPositionChanged(exoPlayer.currentPosition)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("재생 중") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.savePositionNow(exoPlayer.currentPosition)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.error != null -> Text(
                    uiState.error!!,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> AndroidView(
                    factory = {
                        PlayerView(it).apply { player = exoPlayer }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
