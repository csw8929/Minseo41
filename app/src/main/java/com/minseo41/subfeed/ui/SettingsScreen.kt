package com.minseo41.subfeed.ui

import android.net.Uri
import android.util.Log
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
    onChannelEdit: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val channelCount by viewModel.channelCount.collectAsState()
    val context = LocalContext.current

    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                viewModel.importFromJson(stream)
            }
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(
            "SubFeedSettingsScreen",
            "signIn launcher result: code=${result.resultCode}, hasData=${result.data != null}",
        )
        viewModel.handleSignInResult(result.data)
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
            Text("Google 계정", style = MaterialTheme.typography.titleMedium)

            if (uiState.signedInEmail != null) {
                Text(
                    "${uiState.signedInEmail} ✓",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { viewModel.signOut() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("로그아웃")
                }
            } else {
                Text(
                    "다른 단말과 시청 위치를 동기화하려면 Google 계정 연동이 필요합니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { signInLauncher.launch(viewModel.signInIntent()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Google 계정 연동")
                }
            }

            HorizontalDivider()

            Text("구독 채널 import", style = MaterialTheme.typography.titleMedium)

            Text(
                "JSON 파일을 선택하면 기존 채널 목록을 모두 교체합니다 (즐겨찾기는 유지).",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    jsonLauncher.launch(arrayOf("application/json", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("JSON 파일 import")
            }

            HorizontalDivider()

            Text("채널 관리", style = MaterialTheme.typography.titleMedium)

            Text(
                "구독 채널: ${channelCount}개",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = onChannelEdit,
                enabled = channelCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("채널 편집")
            }

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
