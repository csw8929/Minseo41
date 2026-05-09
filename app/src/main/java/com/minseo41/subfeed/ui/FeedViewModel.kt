package com.minseo41.subfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.FavoriteRepo
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.data.refresh.RefreshScheduler
import com.minseo41.subfeed.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FeedTab { Today, Favorites }

sealed interface FeedUiState {
    data object Loading : FeedUiState
    data class Success(val videos: List<VideoItem>) : FeedUiState
    data class Empty(val message: String) : FeedUiState
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    subscriptionRepo: SubscriptionRepo,
    private val favoriteRepo: FavoriteRepo,
    private val refreshScheduler: RefreshScheduler,
) : ViewModel() {

    val uiState: StateFlow<FeedUiState> = subscriptionRepo.observeTodayFeed()
        .map { videos ->
            if (videos.isEmpty()) FeedUiState.Empty("구독한 영상이 없거나 아직 갱신되지 않았습니다")
            else FeedUiState.Success(videos)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState.Loading)

    private val _selectedTab = MutableStateFlow(FeedTab.Today)
    val selectedTab: StateFlow<FeedTab> = _selectedTab

    val favorites: StateFlow<List<VideoItem>> = favoriteRepo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = favoriteRepo.observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun selectTab(tab: FeedTab) {
        _selectedTab.value = tab
    }

    fun refreshNow() {
        refreshScheduler.triggerNow()
    }

    fun toggleFavorite(video: VideoItem) {
        viewModelScope.launch {
            favoriteRepo.toggle(video)
        }
    }
}
