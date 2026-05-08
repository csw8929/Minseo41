package com.minseo41.subfeed

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.minseo41.subfeed.ui.FeedScreen
import com.minseo41.subfeed.ui.PlayerScreen
import com.minseo41.subfeed.ui.SettingsScreen
import com.minseo41.subfeed.ui.settings.ChannelEditScreen
import com.minseo41.subfeed.ui.theme.SubFeedTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // 시스템 미디어 알림 → MainActivity 진입 시 video deep-link 전달용.
    // singleTop launchMode + onNewIntent로 받아서 NavController가 player/{videoId} 로 이동.
    private val pendingDeepLinkVideoId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeDeepLink(intent)
        setContent {
            SubFeedTheme {
                val navController = rememberNavController()
                val deepLinkVideoId by pendingDeepLinkVideoId.collectAsState()

                // Player는 NavHost destination이 아니라 overlay layer로 띄운다.
                // back 시 Feed가 이미 baseline에 그려져 있어 destination 전환 dispose 없이 즉시 사라짐.
                var playerVideoId by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(deepLinkVideoId) {
                    val id = deepLinkVideoId ?: return@LaunchedEffect
                    playerVideoId = id
                    pendingDeepLinkVideoId.value = null
                }

                Box(Modifier.fillMaxSize()) {
                    NavHost(navController = navController, startDestination = "feed") {
                        composable("feed") {
                            FeedScreen(
                                onVideoClick = { videoId -> playerVideoId = videoId },
                                onSettingsClick = { navController.navigate("settings") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onChannelEdit = { navController.navigate("settings/channels") },
                            )
                        }
                        composable("settings/channels") {
                            ChannelEditScreen(onBack = { navController.popBackStack() })
                        }
                    }

                    val activeVideoId = playerVideoId
                    if (activeVideoId != null) {
                        // 시스템 back 키 → overlay 닫음 (NavHost 라우트 변경 없음)
                        BackHandler { playerVideoId = null }
                        PlayerScreen(
                            videoId = activeVideoId,
                            onBack = { playerVideoId = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    private fun consumeDeepLink(intent: Intent?) {
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID) ?: return
        if (videoId.isBlank()) return
        pendingDeepLinkVideoId.value = videoId
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
    }
}
