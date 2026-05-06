package com.minseo41.subfeed.ui

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.AuthRepo
import com.minseo41.subfeed.data.SubscriptionRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

data class SettingsUiState(
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

    val channelCount: StateFlow<Int> = subscriptionRepo.observeChannels()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            authRepo.currentUser.collect { user ->
                _uiState.update { it.copy(signedInEmail = user?.email) }
            }
        }
    }

    fun importFromJson(stream: InputStream) {
        viewModelScope.launch {
            runCatching { subscriptionRepo.importFromJson(stream) }
                .onSuccess { count ->
                    _uiState.update { it.copy(message = "${count}개 채널 import 됨") }
                }
                .onFailure { e ->
                    Log.e(TAG, "JSON import 실패", e)
                    _uiState.update { it.copy(message = "JSON import 실패: ${e.message}") }
                }
        }
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
}
