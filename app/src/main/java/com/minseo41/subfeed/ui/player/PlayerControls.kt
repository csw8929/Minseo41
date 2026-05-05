package com.minseo41.subfeed.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onRewindClick: () -> Unit,
    onForwardClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ControlSlot {
            IconButton(onClick = onRewindClick, modifier = Modifier.size(80.dp)) {
                Icon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = "10초 뒤로",
                    tint = Color.White,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        ControlSlot {
            IconButton(onClick = onPlayPauseClick, modifier = Modifier.size(96.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "일시정지" else "재생",
                    tint = Color.White,
                    modifier = Modifier.size(88.dp),
                )
            }
        }
        ControlSlot {
            IconButton(onClick = onForwardClick, modifier = Modifier.size(80.dp)) {
                Icon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = "10초 앞으로",
                    tint = Color.White,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlSlot(content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) { content() }
}
