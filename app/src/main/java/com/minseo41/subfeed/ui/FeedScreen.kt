package com.minseo41.subfeed.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.minseo41.subfeed.model.VideoItem
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FavoriteYellow = Color(0xFFFFD700)
private val UnreadBlue = Color(0xFF4FC3F7)
private val ChannelGreen = Color(0xFF81C784)
private val ProgressRed = Color(0xFFFF0000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onVideoClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val refreshMessage by viewModel.refreshMessage.collectAsState()
    val infiniteTransition = rememberInfiniteTransition(label = "refresh")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "spinAngle",
    )
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(refreshMessage) {
        val msg = refreshMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearRefreshMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .height(40.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "SubFeed",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                        if (selectedTab == FeedTab.Today) {
                            IconButton(
                                onClick = { viewModel.refreshNow() },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "새로고침",
                                    modifier = Modifier.rotate(if (isRefreshing) spinAngle else 0f),
                                )
                            }
                        }
                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "설정")
                        }
                    }
                }
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == FeedTab.Today,
                        onClick = { viewModel.selectTab(FeedTab.Today) },
                        text = { Text("구독영상") },
                    )
                    Tab(
                        selected = selectedTab == FeedTab.Favorites,
                        onClick = { viewModel.selectTab(FeedTab.Favorites) },
                        text = { Text("즐겨찾기") },
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                FeedTab.Today -> TodayContent(
                    state = uiState,
                    favoriteIds = favoriteIds,
                    onVideoClick = onVideoClick,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onRetry = { viewModel.refreshNow() },
                )
                FeedTab.Favorites -> FavoritesContent(
                    favorites = favorites,
                    onVideoClick = onVideoClick,
                    onToggleFavorite = viewModel::toggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun TodayContent(
    state: FeedUiState,
    favoriteIds: Set<String>,
    onVideoClick: (String) -> Unit,
    onToggleFavorite: (VideoItem) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is FeedUiState.Loading -> Box(Modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        is FeedUiState.Empty -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("새로고침") }
        }
        is FeedUiState.Success -> LazyColumn {
            items(state.videos, key = { it.id }) { video ->
                VideoRow(
                    video = video,
                    isFavorite = video.id in favoriteIds,
                    onClick = { onVideoClick(video.id) },
                    onToggleFavorite = { onToggleFavorite(video) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FavoritesContent(
    favorites: List<VideoItem>,
    onVideoClick: (String) -> Unit,
    onToggleFavorite: (VideoItem) -> Unit,
) {
    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            Text(
                "즐겨찾기에 추가된 영상이 없습니다.\n영상 우측의 ☆ 을 눌러 추가하세요.",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn {
            items(favorites, key = { it.id }) { video ->
                VideoRow(
                    video = video,
                    isFavorite = true,
                    onClick = { onVideoClick(video.id) },
                    onToggleFavorite = { onToggleFavorite(video) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun VideoRow(
    video: VideoItem,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp)) {
            if (video.isUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(UnreadBlue)
                        .align(Alignment.Center),
                )
            }
        }
        Column(
            modifier = Modifier.width(120.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
            ) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                video.watchFraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                        color = ProgressRed,
                        trackColor = Color(0x66000000),
                    )
                }
            }
            if (video.uploadedAt > 0L) {
                Text(
                    text = formatUploadedAt(video.uploadedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = UnreadBlue,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = video.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = ChannelGreen,
            )
            if (video.durationSeconds > 0L) {
                Text(
                    text = formatDuration(video.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (isFavorite) "즐겨찾기 해제" else "즐겨찾기 추가",
                tint = if (isFavorite) FavoriteYellow else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val d = Duration.ofSeconds(seconds)
    val h = d.toHours()
    val m = d.toMinutesPart()
    val s = d.toSecondsPart()
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val UploadedAtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd HH:mm")

private fun formatUploadedAt(epochMillis: Long): String {
    val dt = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(epochMillis),
        ZoneId.systemDefault(),
    )
    return dt.format(UploadedAtFormatter)
}
