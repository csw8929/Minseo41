package com.minseo41.subfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.minseo41.subfeed.data.FavoriteRepo
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.data.refresh.RefreshScheduler
import com.minseo41.subfeed.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _refreshMessage = MutableStateFlow<String?>(null)
    val refreshMessage: StateFlow<String?> = _refreshMessage

    fun clearRefreshMessage() {
        _refreshMessage.value = null
    }

    fun selectTab(tab: FeedTab) {
        _selectedTab.value = tab
    }

    fun refreshNow() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        refreshScheduler.triggerNow()
        viewModelScope.launch {
            val infos = withTimeoutOrNull(30_000L) {
                refreshScheduler.observeManualWork()
                    .filter { it.isNotEmpty() }
                    .first { list -> list.first().state.isFinished }
            }
            _isRefreshing.value = false
            if (infos != null) {
                val info = infos.first()
                _refreshMessage.value = if (info.state == WorkInfo.State.SUCCEEDED) {
                    val ch = info.outputData.getInt("ch", 0)
                    val ok = info.outputData.getInt("ok", 0)
                    "갱신 완료: ${ok}/${ch}개 채널 성공"
                } else {
                    "갱신 실패"
                }
            }
        }
    }

    fun toggleFavorite(video: VideoItem) {
        viewModelScope.launch {
            favoriteRepo.toggle(video)
        }
    }
}
