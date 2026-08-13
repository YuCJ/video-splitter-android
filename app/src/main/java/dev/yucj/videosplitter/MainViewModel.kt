package dev.yucj.videosplitter

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.yucj.videosplitter.split.Segment
import dev.yucj.videosplitter.split.SplitJobState
import dev.yucj.videosplitter.split.SplitMode
import dev.yucj.videosplitter.split.SplitPlanner
import dev.yucj.videosplitter.split.SplitService
import dev.yucj.videosplitter.split.SplitStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val videoUri: Uri? = null,
    val durationMs: Long? = null,
    val maxSegmentSec: Int = 60,
    val mode: SplitMode = SplitMode.EVEN,
    val loadError: String? = null,
) {
    val segments: List<Segment>
        get() = durationMs?.takeIf { it > 0 }
            ?.let { SplitPlanner.plan(it, maxSegmentSec, mode) }
            .orEmpty()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val jobState: StateFlow<SplitJobState> = SplitStateHolder.state

    fun onVideoPicked(uri: Uri) {
        _uiState.value = _uiState.value.copy(videoUri = uri, durationMs = null, loadError = null)
        viewModelScope.launch {
            try {
                val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
                _uiState.value = _uiState.value.copy(durationMs = duration)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loadError = e.message ?: e.toString())
            }
        }
    }

    fun onMaxSecondsChanged(sec: Int) {
        _uiState.value = _uiState.value.copy(maxSegmentSec = sec.coerceIn(15, 90))
    }

    fun onModeChanged(mode: SplitMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun startSplit() {
        val state = _uiState.value
        val uri = state.videoUri ?: return
        if (state.durationMs == null) return
        SplitStateHolder.update(SplitJobState.Running(1, state.segments.size, 0))
        SplitService.start(getApplication(), uri, state.maxSegmentSec, state.mode)
    }

    fun cancelSplit() {
        SplitService.cancel(getApplication())
    }

    fun resetJobState() {
        SplitStateHolder.update(SplitJobState.Idle)
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: error("無法讀取影片長度")
        } finally {
            retriever.release()
        }
    }
}
