package com.minseo41.subfeed.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.minseo41.subfeed.model.SubscribedChannel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelEditScreen(
    onBack: () -> Unit,
    viewModel: ChannelEditViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsState()
    var editing by remember { mutableStateOf<SubscribedChannel?>(null) }
    var deleting by remember { mutableStateOf<SubscribedChannel?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("채널 편집 (${channels.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { padding ->
        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "구독 채널이 없습니다.\n설정에서 JSON으로 import 하세요.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(channels, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        onEdit = { editing = channel },
                        onDelete = { deleting = channel },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { ch ->
        EditDialog(
            channel = ch,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                viewModel.updateChannel(updated)
                editing = null
            },
        )
    }

    deleting?.let { ch ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("채널 삭제") },
            text = { Text("'${ch.name}' 채널을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChannel(ch.id)
                    deleting = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ChannelRow(
    channel: SubscribedChannel,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "최근 ${channel.windowDays}일 · 최대 ${channel.maxCount}개",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "편집")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "삭제")
        }
    }
}

@Composable
private fun EditDialog(
    channel: SubscribedChannel,
    onDismiss: () -> Unit,
    onConfirm: (SubscribedChannel) -> Unit,
) {
    var name by remember(channel.id) { mutableStateOf(channel.name) }
    var windowDaysText by remember(channel.id) { mutableStateOf(channel.windowDays.toString()) }
    var maxCountText by remember(channel.id) { mutableStateOf(channel.maxCount.toString()) }

    val windowDays = windowDaysText.toIntOrNull()
    val maxCount = maxCountText.toIntOrNull()
    val valid = name.isNotBlank() && (windowDays != null && windowDays >= 1) &&
        (maxCount != null && maxCount >= 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("채널 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("채널명") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = windowDaysText,
                    onValueChange = { windowDaysText = it.filter { c -> c.isDigit() } },
                    label = { Text("최근 N일") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = maxCountText,
                    onValueChange = { maxCountText = it.filter { c -> c.isDigit() } },
                    label = { Text("최대 개수") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        channel.copy(
                            name = name.trim(),
                            windowDays = windowDays!!,
                            maxCount = maxCount!!,
                        )
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
