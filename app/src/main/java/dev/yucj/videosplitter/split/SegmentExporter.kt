package dev.yucj.videosplitter.split

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 用 Transformer 把來源影片的一個時間區間 export 成 H.264 + AAC mp4。
 * 一次只能有一個 export 在跑（呼叫端負責循序執行）。
 */
class SegmentExporter(
    private val context: Context,
    /** 來源影片的總 bitrate（bps），拿來當視訊編碼位元率，盡量貼近原始品質；未知時給 0。 */
    private val sourceBitrate: Int,
) {

    /** 提供給進度輪詢用；只在 main thread 讀寫。 */
    private var activeTransformer: Transformer? = null

    /**
     * 必須在 main thread 呼叫（Transformer 需要有 Looper 的 thread）。
     * HDR 來源第一次失敗時，強制 tone map 成 SDR 再試一次。
     */
    suspend fun export(
        inputUri: Uri,
        segment: Segment,
        outputFile: File,
        onProgress: (Int) -> Unit,
        pollScope: CoroutineScope,
    ): ExportResult {
        return try {
            runExport(inputUri, segment, outputFile, forceSdrToneMap = false, onProgress, pollScope)
        } catch (e: ExportException) {
            outputFile.delete()
            // HDR 裝置間差異大，錯誤碼不一定精準指向 tone mapping，
            // 所以任何 export 失敗都用強制 tone map 再試一次，仍失敗才回報。
            runExport(inputUri, segment, outputFile, forceSdrToneMap = true, onProgress, pollScope)
        }
    }

    private suspend fun runExport(
        inputUri: Uri,
        segment: Segment,
        outputFile: File,
        forceSdrToneMap: Boolean,
        onProgress: (Int) -> Unit,
        pollScope: CoroutineScope,
    ): ExportResult = suspendCancellableCoroutine { continuation ->
        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(segment.startMs)
                    .setEndPositionMs(segment.endMs)
                    .build(),
            )
            .build()

        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(EditedMediaItem.Builder(mediaItem).build()).build(),
        )
            .setHdrMode(
                if (forceSdrToneMap) {
                    Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
                } else {
                    Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                },
            )
            .build()

        // 解析度與幀率 Transformer 預設不動；bitrate 沿用來源，避免二次壓縮把畫質壓爛。
        val videoEncoderSettings = if (sourceBitrate > 0) {
            VideoEncoderSettings.Builder().setBitrate(sourceBitrate).build()
        } else {
            VideoEncoderSettings.DEFAULT
        }
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeTransformer = null
                        if (continuation.isActive) continuation.resumeWith(Result.success(exportResult))
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        activeTransformer = null
                        if (continuation.isActive) continuation.resumeWith(Result.failure(exportException))
                    }
                },
            )
            .build()

        activeTransformer = transformer
        transformer.start(composition, outputFile.absolutePath)

        val progressJob = pollScope.launch {
            val holder = ProgressHolder()
            while (isActive && activeTransformer === transformer) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                delay(500)
            }
        }

        continuation.invokeOnCancellation {
            progressJob.cancel()
            transformer.cancel()
            activeTransformer = null
            outputFile.delete()
        }
    }
}
