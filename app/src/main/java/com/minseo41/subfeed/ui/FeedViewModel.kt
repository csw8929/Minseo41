package com.minseo41.subfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface FeedUiState {
    data object Loading : FeedUiState
    data class Success(val videos: List<VideoItem>) : FeedUiState
    data class Error(val message: String) : FeedUiState
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val subscriptionRepo: SubscriptionRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val uiState: StateFlow<FeedUiState> = _uiState

    init {
        loadTodayFeed()
    }

    fun loadTodayFeed() {
        viewModelScope.launch {
            _uiState.value = FeedUiState.Loading
            runCatching { subscriptionRepo.fetchTodayVideos() }
                .onSuccess { videos ->
                    _uiState.value = if (videos.isEmpty())
                        FeedUiState.Error("오늘 새 영상이 없습니다")
                    else
                        FeedUiState.Success(videos)
                }
                .onFailure { e ->
                    _uiState.value = FeedUiState.Error(e.message ?: "피드 로드 실패")
                }
        }
    }
}
