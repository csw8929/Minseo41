package com.minseo41.subfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.FavoriteRepo
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FeedTab { Today, Favorites }

sealed interface FeedUiState {
    data object Loading : FeedUiState
    data class Success(val videos: List<VideoItem>) : FeedUiState
    data class Error(val message: String) : FeedUiState
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val subscriptionRepo: SubscriptionRepo,
    private val favoriteRepo: FavoriteRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val uiState: StateFlow<FeedUiState> = _uiState

    private val _selectedTab = MutableStateFlow(FeedTab.Today)
    val selectedTab: StateFlow<FeedTab> = _selectedTab

    val favorites: StateFlow<List<VideoItem>> = favoriteRepo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = favoriteRepo.observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        loadTodayFeed()
    }

    fun selectTab(tab: FeedTab) {
        _selectedTab.value = tab
    }

    fun loadTodayFeed() {
        viewModelScope.launch {
            _uiState.value = FeedUiState.Loading
            runCatching { subscriptionRepo.fetchTodayVideos() }
                .onSuccess { videos ->
                    _uiState.value = if (videos.isEmpty())
                        FeedUiState.Error("새 영상이 없습니다")
                    else
                        FeedUiState.Success(videos)
                }
                .onFailure { e ->
                    _uiState.value = FeedUiState.Error(e.message ?: "피드 로드 실패")
                }
        }
    }

    fun toggleFavorite(video: VideoItem) {
        viewModelScope.launch {
            favoriteRepo.toggle(video)
        }
    }
}
