package com.minseo41.subfeed.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlayerTopBar(
    title: String,
    qualityLabel: String,
    qualityEnabled: Boolean,
    captionEnabled: Boolean,
    orientationLocked: Boolean,
    onBackClick: () -> Unit,
    onQualityClick: () -> Unit,
    onCaptionClick: () -> Unit,
    onPipClick: () -> Unit,
    onToggleOrientationLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                )
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackClick, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "뒤로",
                tint = Color.White,
            )
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        QualityPill(
            label = qualityLabel,
            enabled = qualityEnabled,
            onClick = onQualityClick,
        )
        IconButton(onClick = onCaptionClick, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (captionEnabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                contentDescription = "자막",
                tint = Color.White,
            )
        }
        IconButton(onClick = onToggleOrientationLock, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (orientationLocked) Icons.Default.ScreenLockRotation else Icons.Default.ScreenRotation,
                contentDescription = if (orientationLocked) "회전 잠금 해제" else "회전 잠금",
                tint = Color.White,
            )
        }
        IconButton(onClick = onPipClick, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.PictureInPicture,
                contentDescription = "PIP",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun QualityPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = tint, fontSize = 13.sp)
    }
}
