package com.minseo41.subfeed.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var channelUrlInput by remember { mutableStateOf("") }

    val xmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                viewModel.importFromTakeoutXml(stream)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("구독 채널 import", style = MaterialTheme.typography.titleMedium)

            Button(
                onClick = { xmlLauncher.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("YouTube Takeout XML 불러오기")
            }

            HorizontalDivider()

            Text("채널 URL 직접 추가", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = channelUrlInput,
                onValueChange = { channelUrlInput = it },
                label = { Text("https://www.youtube.com/channel/...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = {
                    viewModel.addChannelByUrl(channelUrlInput.trim())
                    channelUrlInput = ""
                },
                enabled = channelUrlInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("추가")
            }

            HorizontalDivider()

            Text(
                "구독 채널: ${uiState.channelCount}개",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (uiState.message != null) {
                Text(
                    uiState.message!!,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
