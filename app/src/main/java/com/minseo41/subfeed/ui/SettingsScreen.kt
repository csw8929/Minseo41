package com.minseo41.subfeed.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
                .verticalScroll(rememberScrollState())
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

            Text("재생 기본값", style = MaterialTheme.typography.titleMedium)

            Text("기본 화질", style = MaterialTheme.typography.bodyMedium)
            QualityDefaultRow(
                selected = uiState.defaultMaxHeight,
                onSelect = { viewModel.setDefaultMaxHeight(it) },
            )

            Text("자막 크기", style = MaterialTheme.typography.bodyMedium)
            CaptionScaleRow(
                selected = uiState.captionScale,
                onSelect = { viewModel.setCaptionScale(it) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "화면 회전 잠금",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = uiState.orientationLocked,
                    onCheckedChange = { viewModel.setOrientationLocked(it) },
                )
            }
            Text(
                "재생 화면이 의도치 않게 회전하지 않도록 고정합니다 (풀스크린은 가로 강제 유지).",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("back 키 동작", style = MaterialTheme.typography.bodyMedium)
            BackActionRow(
                selected = uiState.backAction,
                onSelect = { viewModel.setBackAction(it) },
            )
            Text(
                "홈 키·폴드 닫기는 항상 백그라운드 재생 유지. 이 옵션은 back 키만 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
            )

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

@Composable
private fun QualityDefaultRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayerPrefs.QUALITY_OPTIONS.forEach { height ->
            val label = if (height == 0) "자동" else "${height}p"
            FilterChip(
                selected = selected == height,
                onClick = { onSelect(height) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun CaptionScaleRow(selected: Float, onSelect: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayerPrefs.CAPTION_SCALE_OPTIONS.forEach { scale ->
            FilterChip(
                selected = selected == scale,
                onClick = { onSelect(scale) },
                label = { Text(PlayerPrefs.captionScaleLabel(scale)) },
            )
        }
    }
}

@Composable
private fun BackActionRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == PlayerPrefs.BACK_ACTION_STOP,
            onClick = { onSelect(PlayerPrefs.BACK_ACTION_STOP) },
            label = { Text("정지") },
        )
        FilterChip(
            selected = selected == PlayerPrefs.BACK_ACTION_PIP,
            onClick = { onSelect(PlayerPrefs.BACK_ACTION_PIP) },
            label = { Text("PIP 진입") },
        )
    }
}
