package com.minseo41.subfeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.minseo41.subfeed.ui.FeedScreen
import com.minseo41.subfeed.ui.PlayerScreen
import com.minseo41.subfeed.ui.SettingsScreen
import com.minseo41.subfeed.ui.theme.SubFeedTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SubFeedTheme {
                val navController = rememberNavController()
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
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
