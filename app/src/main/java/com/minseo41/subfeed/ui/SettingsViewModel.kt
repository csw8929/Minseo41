package com.minseo41.subfeed.ui

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.AuthRepo
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.model.SubscribedChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

data class SettingsUiState(
    val channelCount: Int = 0,
    val message: String? = null,
    val signedInEmail: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val subscriptionRepo: SubscriptionRepo,
    private val authRepo: AuthRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        refresh()
        viewModelScope.launch {
            authRepo.currentUser.collect { user ->
                _uiState.update { it.copy(signedInEmail = user?.email) }
            }
        }
    }

    fun importFromTakeoutXml(stream: InputStream) {
        val channels = runCatching { subscriptionRepo.parseYoutubeTakeoutXml(stream) }
            .getOrDefault(emptyList())
        if (channels.isEmpty()) {
            _uiState.update { it.copy(message = "채널을 찾지 못했습니다. XML 파일을 확인해주세요.") }
            return
        }
        val existing = subscriptionRepo.loadChannels().toMutableList()
        val newIds = existing.map { it.id }.toSet()
        val added = channels.filter { it.id !in newIds }
        subscriptionRepo.saveChannels(existing + added)
        _uiState.update {
            it.copy(
                channelCount = existing.size + added.size,
                message = "${added.size}개 채널 추가됨",
            )
        }
    }

    fun addChannelByUrl(url: String) {
        if (url.isBlank()) return
        val id = url.substringAfterLast("/").substringBefore("?")
        val channel = SubscribedChannel(id = id, name = id, url = url)
        val existing = subscriptionRepo.loadChannels().toMutableList()
        if (existing.any { it.id == id }) {
            _uiState.update { it.copy(message = "이미 추가된 채널입니다") }
            return
        }
        existing.add(channel)
        subscriptionRepo.saveChannels(existing)
        _uiState.update { it.copy(channelCount = existing.size, message = "채널 추가됨") }
    }

    fun signInIntent(): Intent {
        Log.d(TAG, "signInIntent requested")
        return authRepo.signInIntent()
    }

    fun handleSignInResult(data: Intent?) {
        Log.d(TAG, "handleSignInResult: data=$data, extras=${data?.extras?.keySet()}")
        viewModelScope.launch {
            authRepo.handleSignInResult(data)
                .onSuccess { user ->
                    Log.d(TAG, "signed in: ${user.email}")
                    _uiState.update { it.copy(message = "로그인됨: ${user.email}") }
                }
                .onFailure { e ->
                    Log.e(TAG, "sign-in failed", e)
                    _uiState.update { it.copy(message = "로그인 실패: ${e.message}") }
                }
        }
    }

    fun signOut() {
        Log.d(TAG, "signOut requested")
        viewModelScope.launch {
            authRepo.signOut()
            _uiState.update { it.copy(message = "로그아웃됨") }
        }
    }

    companion object {
        private const val TAG = "SubFeedSettingsVM"
    }

    private fun refresh() {
        _uiState.update { it.copy(channelCount = subscriptionRepo.loadChannels().size) }
    }
}
