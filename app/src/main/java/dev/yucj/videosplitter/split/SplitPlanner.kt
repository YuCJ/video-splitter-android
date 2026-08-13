package dev.yucj.videosplitter.split

import kotlin.math.ceil

enum class SplitMode {
    /** 段數 = ceil(duration / max)，每段等長。 */
    EVEN,

    /** 每段固定為 max 秒，最後一段是剩餘長度。 */
    FIXED,
}

data class Segment(
    /** 1-based，對應輸出檔名 part_001.mp4…。 */
    val index: Int,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

object SplitPlanner {

    fun plan(durationMs: Long, maxSegmentSec: Int, mode: SplitMode): List<Segment> {
        require(durationMs > 0) { "durationMs must be positive" }
        require(maxSegmentSec > 0) { "maxSegmentSec must be positive" }
        val maxMs = maxSegmentSec * 1000L

        return when (mode) {
            SplitMode.EVEN -> {
                val count = ceil(durationMs.toDouble() / maxMs).toInt()
                (1..count).map { i ->
                    // 用乘法算邊界避免累積誤差，最後一段強制對齊總長。
                    val start = durationMs * (i - 1) / count
                    val end = if (i == count) durationMs else durationMs * i / count
                    Segment(i, start, end)
                }
            }

            SplitMode.FIXED -> {
                val count = ceil(durationMs.toDouble() / maxMs).toInt()
                (1..count).map { i ->
                    val start = (i - 1) * maxMs
                    val end = minOf(i * maxMs, durationMs)
                    Segment(i, start, end)
                }
            }
        }
    }
}
