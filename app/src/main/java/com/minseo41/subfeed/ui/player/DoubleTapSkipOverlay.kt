package com.minseo41.subfeed.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun DoubleTapSkipOverlay(
    onSingleTap: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var leftRipple by remember { mutableStateOf(false) }
    var rightRipple by remember { mutableStateOf(false) }

    LaunchedEffect(leftRipple) {
        if (leftRipple) {
            delay(600)
            leftRipple = false
        }
    }
    LaunchedEffect(rightRipple) {
        if (rightRipple) {
            delay(600)
            rightRipple = false
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSingleTap() },
                        onDoubleTap = {
                            leftRipple = true
                            onSkipBack()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            RippleHint(visible = leftRipple, label = "-10초", icon = true, forward = false)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSingleTap() },
                        onDoubleTap = {
                            rightRipple = true
                            onSkipForward()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            RippleHint(visible = rightRipple, label = "+10초", icon = true, forward = true)
        }
    }
}

@Composable
private fun RippleHint(visible: Boolean, label: String, icon: Boolean, forward: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .padding(16.dp),
        ) {
            if (icon) {
                Icon(
                    imageVector = if (forward) Icons.Default.Forward10 else Icons.Default.Replay10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(text = label, color = Color.White, fontSize = 14.sp)
        }
    }
}
