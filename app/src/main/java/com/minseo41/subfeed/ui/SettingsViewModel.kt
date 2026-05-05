package com.minseo41.subfeed.ui

import androidx.lifecycle.ViewModel
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.model.SubscribedChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import javax.inject.Inject

data class SettingsUiState(
    val channelCount: Int = 0,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val subscriptionRepo: SubscriptionRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        refresh()
    }

    fun importFromTakeoutXml(stream: InputStream) {
        val channels = runCatching { subscriptionRepo.parseYoutubeTakeoutXml(stream) }
            .getOrDefault(emptyList())
        if (channels.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "채널을 찾지 못했습니다. XML 파일을 확인해주세요.")
            return
        }
        val existing = subscriptionRepo.loadChannels().toMutableList()
        val newIds = existing.map { it.id }.toSet()
        val added = channels.filter { it.id !in newIds }
        subscriptionRepo.saveChannels(existing + added)
        _uiState.value = SettingsUiState(
            channelCount = existing.size + added.size,
            message = "${added.size}개 채널 추가됨",
        )
    }

    fun addChannelByUrl(url: String) {
        if (url.isBlank()) return
        val id = url.substringAfterLast("/").substringBefore("?")
        val channel = SubscribedChannel(id = id, name = id, url = url)
        val existing = subscriptionRepo.loadChannels().toMutableList()
        if (existing.any { it.id == id }) {
            _uiState.value = _uiState.value.copy(message = "이미 추가된 채널입니다")
            return
        }
        existing.add(channel)
        subscriptionRepo.saveChannels(existing)
        _uiState.value = SettingsUiState(channelCount = existing.size, message = "채널 추가됨")
    }

    private fun refresh() {
        _uiState.value = SettingsUiState(channelCount = subscriptionRepo.loadChannels().size)
    }
}
