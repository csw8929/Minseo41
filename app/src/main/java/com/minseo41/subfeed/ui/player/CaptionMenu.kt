package com.minseo41.subfeed.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minseo41.subfeed.data.CaptionTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionMenu(
    tracks: List<CaptionTrack>,
    selectedLanguageCode: String?,
    onSelect: (CaptionTrack?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = Dp.Unspecified,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.ClosedCaption, contentDescription = null)
                Text(
                    text = "자막 선택",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            CaptionRow(
                label = "끄기",
                selected = selectedLanguageCode == null,
                onClick = { onSelect(null) },
            )
            tracks.forEach { track ->
                CaptionRow(
                    label = track.displayName,
                    selected = track.languageCode == selectedLanguageCode,
                    onClick = { onSelect(track) },
                )
            }
            if (tracks.isEmpty()) {
                Text(
                    text = "이 영상에는 자막이 없습니다",
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CaptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
