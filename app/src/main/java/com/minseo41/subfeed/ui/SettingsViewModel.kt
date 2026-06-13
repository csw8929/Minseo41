package com.minseo41.subfeed.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.AuthRepo
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.data.db.ChannelDao
import com.minseo41.subfeed.data.nas.NasStreamFetcher
import androidx.work.WorkInfo
import com.minseo41.subfeed.data.refresh.RefreshLogEntry
import com.minseo41.subfeed.data.refresh.RefreshPrefs
import com.minseo41.subfeed.data.refresh.RefreshScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

data class SettingsUiState(
    val message: String? = null,
    val signedInEmail: String? = null,
    val defaultMaxHeight: Int = 0,
    val captionScale: Float = 1.0f,
    val orientationLocked: Boolean = false,
    val defaultFullscreen: Boolean = false,
    val refreshIntervalHours: Int = RefreshPrefs.DEFAULT_INTERVAL_HOURS,
    val playbackMode: String = PlayerPrefs.PLAYBACK_MODE_YOUTUBE,
    val nasExtractorEnabled: Boolean = false,
    val nasBaseUrl: String = "",
    val nasSecret: String = "",
    val nasTestResult: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val subscriptionRepo: SubscriptionRepo,
    private val authRepo: AuthRepo,
    private val refreshPrefs: RefreshPrefs,
    private val refreshScheduler: RefreshScheduler,
    private val nasStreamFetcher: NasStreamFetcher,
    channelDao: ChannelDao,
) : ViewModel() {

    private val playerPrefs = context.getSharedPreferences(PlayerPrefs.NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    val channelCount: StateFlow<Int> = subscriptionRepo.observeChannels()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val lastFetchAtMs: StateFlow<Long?> = channelDao.observeLastFetchAtMs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val failedFetchCount: StateFlow<Int> = channelDao.observeFailedFetchCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _refreshLogs = MutableStateFlow(refreshPrefs.getLogs())
    val refreshLogs: StateFlow<List<RefreshLogEntry>> = _refreshLogs

    fun loadRefreshLogs() {
        _refreshLogs.value = refreshPrefs.getLogs()
    }

    init {
        _uiState.update {
            it.copy(
                defaultMaxHeight = playerPrefs.getInt(PlayerPrefs.KEY_DEFAULT_MAX_HEIGHT, 0),
                captionScale = playerPrefs.getFloat(PlayerPrefs.KEY_CAPTION_SCALE, 1.0f),
                orientationLocked = playerPrefs.getBoolean(PlayerPrefs.KEY_ORIENTATION_LOCKED, false),
                defaultFullscreen = playerPrefs.getBoolean(PlayerPrefs.KEY_DEFAULT_FULLSCREEN, false),
                refreshIntervalHours = refreshPrefs.intervalHours,
                playbackMode = playerPrefs.getString(PlayerPrefs.KEY_PLAYBACK_MODE, PlayerPrefs.PLAYBACK_MODE_YOUTUBE)
                    ?: PlayerPrefs.PLAYBACK_MODE_YOUTUBE,
                nasExtractorEnabled = playerPrefs.getBoolean(PlayerPrefs.KEY_NAS_EXTRACTOR_ENABLED, false),
                nasBaseUrl = playerPrefs.getString(PlayerPrefs.KEY_NAS_BASE_URL, "").orEmpty(),
                nasSecret = playerPrefs.getString(PlayerPrefs.KEY_NAS_SECRET, "").orEmpty(),
            )
        }
        viewModelScope.launch {
            authRepo.currentUser.collect { user ->
                _uiState.update { it.copy(signedInEmail = user?.email) }
            }
        }
    }

    fun setRefreshIntervalHours(hours: Int) {
        refreshPrefs.intervalHours = hours
        _uiState.update { it.copy(refreshIntervalHours = hours) }
        refreshScheduler.schedulePeriodic(replaceExisting = true)
    }

    fun refreshNow() {
        refreshScheduler.triggerNow()
        _uiState.update { it.copy(message = "갱신 중...") }
        viewModelScope.launch {
            val infos = refreshScheduler.observeManualWork()
                .filter { it.isNotEmpty() }
                .first { list -> list.first().state.isFinished }
            val info = infos.first()
            val msg = if (info.state == WorkInfo.State.SUCCEEDED) {
                val ch = info.outputData.getInt("ch", 0)
                val ok = info.outputData.getInt("ok", 0)
                "갱신 완료: ${ok}/${ch}개 채널 성공"
            } else {
                "갱신 실패"
            }
            _uiState.update { it.copy(message = msg) }
            loadRefreshLogs()
        }
    }

    fun setPlaybackMode(mode: String) {
        playerPrefs.edit().putString(PlayerPrefs.KEY_PLAYBACK_MODE, mode).apply()
        _uiState.update { it.copy(playbackMode = mode) }
    }

    fun setNasExtractorEnabled(enabled: Boolean) {
        playerPrefs.edit().putBoolean(PlayerPrefs.KEY_NAS_EXTRACTOR_ENABLED, enabled).apply()
        _uiState.update { it.copy(nasExtractorEnabled = enabled) }
    }

    fun setNasBaseUrl(url: String) {
        playerPrefs.edit().putString(PlayerPrefs.KEY_NAS_BASE_URL, url).apply()
        _uiState.update { it.copy(nasBaseUrl = url) }
    }

    fun setNasSecret(secret: String) {
        playerPrefs.edit().putString(PlayerPrefs.KEY_NAS_SECRET, secret).apply()
        _uiState.update { it.copy(nasSecret = secret) }
    }

    fun testNasConnection() {
        val state = _uiState.value
        if (state.nasBaseUrl.isBlank()) {
            _uiState.update { it.copy(nasTestResult = "base URL 을 입력하세요") }
            return
        }
        _uiState.update { it.copy(nasTestResult = "테스트 중...") }
        viewModelScope.launch {
            val ok = nasStreamFetcher.testConnection(state.nasBaseUrl, state.nasSecret)
            _uiState.update { it.copy(nasTestResult = if (ok) "연결 성공" else "연결 실패") }
        }
    }

    fun setDefaultMaxHeight(height: Int) {
        playerPrefs.edit().putInt(PlayerPrefs.KEY_DEFAULT_MAX_HEIGHT, height).apply()
        _uiState.update { it.copy(defaultMaxHeight = height) }
    }

    fun setCaptionScale(scale: Float) {
        playerPrefs.edit().putFloat(PlayerPrefs.KEY_CAPTION_SCALE, scale).apply()
        _uiState.update { it.copy(captionScale = scale) }
    }

    fun setOrientationLocked(locked: Boolean) {
        playerPrefs.edit().putBoolean(PlayerPrefs.KEY_ORIENTATION_LOCKED, locked).apply()
        _uiState.update { it.copy(orientationLocked = locked) }
    }

    fun setDefaultFullscreen(enabled: Boolean) {
        playerPrefs.edit().putBoolean(PlayerPrefs.KEY_DEFAULT_FULLSCREEN, enabled).apply()
        _uiState.update { it.copy(defaultFullscreen = enabled) }
    }

    fun importFromStream(stream: InputStream) {
        viewModelScope.launch {
            runCatching { subscriptionRepo.importFromStream(stream) }
                .onSuccess { count ->
                    _uiState.update { it.copy(message = "${count}개 채널 import 됨") }
                }
                .onFailure { e ->
                    Log.e(TAG, "import 실패", e)
                    _uiState.update { it.copy(message = "import 실패: ${e.message}") }
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
