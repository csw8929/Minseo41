package com.minseo41.subfeed

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

                LaunchedEffect(deepLinkVideoId) {
                    val id = deepLinkVideoId ?: return@LaunchedEffect
                    navController.navigate("player/$id") {
                        launchSingleTop = true
                    }
                    pendingDeepLinkVideoId.value = null
                }

                NavHost(navController = navController, startDestination = "feed") {
                    composable("feed") {
                        FeedScreen(
                            onVideoClick = { videoId ->
                                navController.navigate("player/$videoId")
                            },
                            onSettingsClick = {
                                navController.navigate("settings")
                            }
                        )
                    }
                    composable("player/{videoId}") { back ->
                        val videoId = back.arguments?.getString("videoId") ?: return@composable
                        PlayerScreen(videoId = videoId, onBack = { navController.popBackStack() })
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
