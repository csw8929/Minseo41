package com.minseo41.subfeed.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.CaptionTrack
import com.minseo41.subfeed.data.Cue
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
import javax.inject.Inject

data class PlayerUiState(
    val streamInfo: StreamInfo? = null,
    val resumePositionMs: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showResumeBanner: Boolean = false,
    val selectedCaptionLanguage: String? = null,
    val captionCues: List<Cue> = emptyList(),
    val availableQualityHeights: List<Int> = listOf(0), // 0 = 자동
    val isHlsStream: Boolean = false,
    val selectedMaxHeight: Int = 0,
    val currentVideoHeight: Int = 0, // ExoPlayer가 현재 실제 디코딩 중인 video height (자동 모드에서 채택된 해상도 확인용)
    val isFullscreen: Boolean = false,
    val isInPipMode: Boolean = false,
    val selectedCaptionScale: Float = 1.0f,
    val orientationLocked: Boolean = false,
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
    private val captionCuesCache = mutableMapOf<String, List<Cue>>()
    private val prefs = context.getSharedPreferences(PlayerPrefs.NAME, Context.MODE_PRIVATE)

    fun loadVideo(videoId: String) {
        currentVideoId = videoId
        // 동기 reset — 다른 영상 선택 시 이전 streamInfo/자막이 한 frame 더 그려지는 것 방지.
        _uiState.value = PlayerUiState(
            isLoading = true,
            selectedMaxHeight = prefs.getInt(PlayerPrefs.KEY_DEFAULT_MAX_HEIGHT, 0),
            selectedCaptionScale = prefs.getFloat(PlayerPrefs.KEY_CAPTION_SCALE, 1.0f),
            orientationLocked = prefs.getBoolean(PlayerPrefs.KEY_ORIENTATION_LOCKED, false),
            isFullscreen = prefs.getBoolean(PlayerPrefs.KEY_DEFAULT_FULLSCREEN, false),
        )
        viewModelScope.launch {
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
        val hadPending = saveJob?.isActive == true
        saveJob?.cancel()
        Log.d(TAG, "onPositionChanged defer scheduled (30s) — videoId=$currentVideoId, positionMs=$positionMs, deferredPrev=$hadPending")
        saveJob = viewModelScope.launch {
            delay(30_000L)
            Log.d(TAG, "onPositionChanged debounced save fired: videoId=$currentVideoId, positionMs=$positionMs")
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
            _uiState.update { it.copy(selectedCaptionLanguage = null, captionCues = emptyList()) }
            return
        }
        val cached = captionCuesCache[track.languageCode]
        if (cached != null) {
            _uiState.update {
                it.copy(selectedCaptionLanguage = track.languageCode, captionCues = cached)
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val xml = OkHttpDownloader.get(track.baseUrl)
                    TimedTextToSrt.convertToCues(xml)
                }
            }
                .onSuccess { cues ->
                    captionCuesCache[track.languageCode] = cues
                    _uiState.update {
                        it.copy(selectedCaptionLanguage = track.languageCode, captionCues = cues)
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

    fun updateCurrentVideoHeight(height: Int) {
        if (height <= 0) return
        _uiState.update { it.copy(currentVideoHeight = height) }
    }

    fun setFullscreen(enabled: Boolean) {
        _uiState.update { it.copy(isFullscreen = enabled) }
    }

    fun setInPipMode(enabled: Boolean) {
        _uiState.update { it.copy(isInPipMode = enabled) }
    }

    fun selectCaptionScale(scale: Float) {
        _uiState.update { it.copy(selectedCaptionScale = scale) }
    }

    fun toggleOrientationLocked() {
        val newValue = !_uiState.value.orientationLocked
        prefs.edit().putBoolean(PlayerPrefs.KEY_ORIENTATION_LOCKED, newValue).apply()
        _uiState.update { it.copy(orientationLocked = newValue) }
    }

    companion object {
        private const val TAG = "SubFeedPlayerVM"
    }
}

object PlayerPrefs {
    const val NAME = "player"
    const val KEY_DEFAULT_MAX_HEIGHT = "defaultMaxHeight"
    const val KEY_CAPTION_SCALE = "captionScale"
    const val KEY_ORIENTATION_LOCKED = "orientationLocked"
    const val KEY_DEFAULT_FULLSCREEN = "defaultFullscreen"

    val QUALITY_OPTIONS: List<Int> = listOf(0, 1080, 720, 480, 360)
    val CAPTION_SCALE_OPTIONS: List<Float> = listOf(1.0f, 1.5f, 2.0f)

    fun captionScaleLabel(scale: Float): String =
        if (scale % 1.0f == 0.0f) "${scale.toInt()}x" else "${scale}x"
}
