package com.minseo41.subfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.SyncRepo
import com.minseo41.subfeed.data.VideoExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val streamUrl: String? = null,
    val resumePositionMs: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val extractor: VideoExtractor,
    private val syncRepo: SyncRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private var saveJob: Job? = null
    private var currentVideoId: String = ""

    fun loadVideo(videoId: String) {
        currentVideoId = videoId
        viewModelScope.launch {
            _uiState.value = PlayerUiState(isLoading = true)
            val savedPosition = runCatching { syncRepo.getPosition(videoId) }
                .getOrNull()?.positionMs ?: 0L
            runCatching { extractor.getStreamUrl(videoId) }
                .onSuccess { url ->
                    _uiState.value = PlayerUiState(
                        streamUrl = url,
                        resumePositionMs = savedPosition,
                        isLoading = false,
                    )
                }
                .onFailure { e ->
                    _uiState.value = PlayerUiState(isLoading = false, error = e.message)
                }
        }
    }

    // 30초 debounce — 재생 중 주기적으로 호출
    fun onPositionChanged(positionMs: Long) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(30_000L)
            runCatching { syncRepo.savePosition(currentVideoId, positionMs) }
        }
    }

    // 앱 종료나 화면 벗어날 때 즉시 저장
    fun savePositionNow(positionMs: Long) {
        saveJob?.cancel()
        viewModelScope.launch {
            runCatching { syncRepo.savePosition(currentVideoId, positionMs) }
        }
    }
}
