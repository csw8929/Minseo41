package com.minseo41.subfeed.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

data class QualityOption(
    val label: String,
    val maxHeight: Int, // 0 = Auto
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityMenu(
    options: List<QualityOption>,
    selectedMaxHeight: Int,
    onSelect: (QualityOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.HighQuality, contentDescription = null)
                Text(
                    text = "화질 선택",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            options.forEach { opt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(opt) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = opt.maxHeight == selectedMaxHeight,
                        onClick = { onSelect(opt) },
                    )
                    Text(text = opt.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
