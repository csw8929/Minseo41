package com.minseo41.subfeed.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.CaptionTrack
import com.minseo41.subfeed.data.OkHttpDownloader
import com.minseo41.subfeed.data.StreamInfo
import com.minseo41.subfeed.data.SyncRepo
import com.minseo41.subfeed.data.TimedTextToSrt
import com.minseo41.subfeed.data.VideoExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class PlayerUiState(
    val streamInfo: StreamInfo? = null,
    val resumePositionMs: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showResumeBanner: Boolean = false,
    val selectedCaptionLanguage: String? = null,
    val captionSrtUri: Uri? = null,
    val availableQualityHeights: List<Int> = listOf(0), // 0 = 자동
    val isHlsStream: Boolean = false,
    val selectedMaxHeight: Int = 0,
    val isFullscreen: Boolean = false,
    val isInPipMode: Boolean = false,
    val backgroundPlaybackEnabled: Boolean = true,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractor: VideoExtractor,
    private val syncRepo: SyncRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private var saveJob: Job? = null
    private var bannerJob: Job? = null
    private var currentVideoId: String = ""
    private val captionUriCache = mutableMapOf<String, Uri>()
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    init {
        _uiState.update {
            it.copy(backgroundPlaybackEnabled = prefs.getBoolean("backgroundPlayback", true))
        }
    }

    fun loadVideo(videoId: String) {
        currentVideoId = videoId
        viewModelScope.launch {
            _uiState.value = PlayerUiState(
                isLoading = true,
                backgroundPlaybackEnabled = prefs.getBoolean("backgroundPlayback", true),
            )
            val savedPosition = runCatching { syncRepo.getPosition(videoId) }
                .onFailure { Log.e(TAG, "loadVideo getPosition failed", it) }
                .getOrNull()?.positionMs ?: 0L
            Log.d(TAG, "loadVideo: videoId=$videoId, savedPosition=$savedPosition")
            runCatching { extractor.getStreamInfo(videoId) }
                .onSuccess { info ->
                    val isHls = info.streamUrl.startsWith("hls:")
                    _uiState.update {
                        it.copy(
                            streamInfo = info,
                            resumePositionMs = savedPosition,
                            showResumeBanner = savedPosition > 0L,
                            isHlsStream = isHls,
                            isLoading = false,
                            error = null,
                        )
                    }
                    if (savedPosition > 0L) startBannerCountdown()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    private fun startBannerCountdown() {
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(5_000L)
            _uiState.update { it.copy(showResumeBanner = false) }
        }
    }

    fun dismissResumeBanner() {
        bannerJob?.cancel()
        _uiState.update { it.copy(showResumeBanner = false) }
    }

    fun restartFromBeginning() {
        bannerJob?.cancel()
        _uiState.update { it.copy(showResumeBanner = false, resumePositionMs = 0L) }
    }

    // 30초 debounce — 재생 중 주기적으로 호출. ViewModel이 살아있는 동안만 의미 있으니 viewModelScope 사용.
    fun onPositionChanged(positionMs: Long) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(30_000L)
            Log.d(TAG, "onPositionChanged debounced save: videoId=$currentVideoId, positionMs=$positionMs")
            syncRepo.savePositionDetached(currentVideoId, positionMs)
        }
    }

    // 화면 이탈 시 즉시 저장 — 이 시점엔 ViewModel이 곧 cleared 상태이므로 detached scope로 위임.
    fun savePositionNow(positionMs: Long) {
        saveJob?.cancel()
        Log.d(TAG, "savePositionNow: videoId=$currentVideoId, positionMs=$positionMs")
        syncRepo.savePositionDetached(currentVideoId, positionMs)
    }

    fun selectCaption(track: CaptionTrack?) {
        if (track == null) {
            _uiState.update { it.copy(selectedCaptionLanguage = null, captionSrtUri = null) }
            return
        }
        val cached = captionUriCache[track.languageCode]
        if (cached != null) {
            _uiState.update {
                it.copy(selectedCaptionLanguage = track.languageCode, captionSrtUri = cached)
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val xml = OkHttpDownloader.get(track.baseUrl)
                    val srt = TimedTextToSrt.convert(xml)
                    val file = File(context.cacheDir, "captions_${currentVideoId}_${track.languageCode}.srt")
                    file.writeText(srt, Charsets.UTF_8)
                    Uri.fromFile(file)
                }
            }
                .onSuccess { uri ->
                    captionUriCache[track.languageCode] = uri
                    _uiState.update {
                        it.copy(selectedCaptionLanguage = track.languageCode, captionSrtUri = uri)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "자막 로드 실패: ${e.message}") }
                }
        }
    }

    fun updateAvailableQualities(heights: List<Int>) {
        val sorted = (listOf(0) + heights.distinct().sortedDescending()).distinct()
        _uiState.update { it.copy(availableQualityHeights = sorted) }
    }

    fun selectQuality(maxHeight: Int) {
        _uiState.update { it.copy(selectedMaxHeight = maxHeight) }
    }

    fun setFullscreen(enabled: Boolean) {
        _uiState.update { it.copy(isFullscreen = enabled) }
    }

    fun setInPipMode(enabled: Boolean) {
        _uiState.update { it.copy(isInPipMode = enabled) }
    }

    fun toggleBackgroundPlayback() {
        val newValue = !_uiState.value.backgroundPlaybackEnabled
        prefs.edit().putBoolean("backgroundPlayback", newValue).apply()
        _uiState.update { it.copy(backgroundPlaybackEnabled = newValue) }
    }

    companion object {
        private const val TAG = "SubFeedPlayerVM"
    }
}
