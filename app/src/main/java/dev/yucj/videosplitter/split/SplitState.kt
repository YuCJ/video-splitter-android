package dev.yucj.videosplitter.split

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SegmentResult(
    val index: Int,
    val fileName: String,
    val success: Boolean,
    /** 成功時為 MediaStore 的 content uri 字串。 */
    val mediaStoreUri: String? = null,
    val error: String? = null,
)

sealed interface SplitJobState {
    data object Idle : SplitJobState

    data class Running(
        val currentSegment: Int,
        val totalSegments: Int,
        /** 當前段的 export 進度 0–100。 */
        val segmentProgress: Int,
    ) : SplitJobState

    data class Finished(val results: List<SegmentResult>) : SplitJobState

    data object Cancelled : SplitJobState

    data class Failed(val message: String) : SplitJobState
}

/**
 * Service 與 UI 之間的橋。Service 寫入、UI 讀取；
 * 用 process 內的 singleton 避免 binder 樣板程式碼。
 */
object SplitStateHolder {
    private val _state = MutableStateFlow<SplitJobState>(SplitJobState.Idle)
    val state: StateFlow<SplitJobState> = _state.asStateFlow()

    fun update(state: SplitJobState) {
        _state.value = state
    }
}
