package dev.yucj.videosplitter.split

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.yucj.videosplitter.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitService : Service() {

    // Transformer 必須在有 Looper 的 thread 上建立與操作，所以整個流程跑在 main dispatcher；
    // 重活（編解碼）在 Transformer 自己的背景 thread，不會卡 UI。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var jobRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                scope.cancel()
                SplitStateHolder.update(SplitJobState.Cancelled)
                cleanupTempDir()
                stopSelf()
            }

            ACTION_START -> {
                if (jobRunning) return START_NOT_STICKY
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val maxSec = intent.getIntExtra(EXTRA_MAX_SEC, 60)
                val mode = SplitMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: SplitMode.EVEN.name)
                if (uri == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                jobRunning = true
                startForegroundWithNotification(buildNotification(getString(R.string.notification_preparing), 0, indeterminate = true))
                scope.launch { runSplitJob(uri, maxSec, mode) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSplitJob(uri: Uri, maxSec: Int, mode: SplitMode) {
        val results = mutableListOf<SegmentResult>()
        try {
            val (durationMs, bitrate) = withContext(Dispatchers.IO) { readMetadata(uri) }
            val segments = SplitPlanner.plan(durationMs, maxSec, mode)
            val exporter = SegmentExporter(this, bitrate)
            val tempDir = tempDir().apply { mkdirs() }

            for (segment in segments) {
                val fileName = "part_%03d.mp4".format(segment.index)
                val outFile = File(tempDir, fileName)
                SplitStateHolder.update(SplitJobState.Running(segment.index, segments.size, 0))
                updateNotification(segment.index, segments.size, 0)

                try {
                    exporter.export(
                        inputUri = uri,
                        segment = segment,
                        outputFile = outFile,
                        onProgress = { progress ->
                            SplitStateHolder.update(
                                SplitJobState.Running(segment.index, segments.size, progress),
                            )
                            updateNotification(segment.index, segments.size, progress)
                        },
                        pollScope = scope,
                    )
                    results += SegmentResult(segment.index, fileName, success = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 單段失敗不中斷整批，記下來最後一起回報。
                    results += SegmentResult(
                        segment.index, fileName, success = false, error = e.message ?: e.toString(),
                    )
                }
            }

            // 全部段落跑完後才寫進 MediaStore，避免使用者中途取消留下半套結果。
            // IG 等選檔清單照 DATE_ADDED（秒精度）排序，而這個欄位由 MediaProvider 在
            // insert 時蓋成當下時間、app 給的值不會保留，所以只能靠「每段 insert 之間
            // 跨秒」讓時間戳嚴格遞增，part 順序才不會因同秒平手而亂掉。
            val finalResults = withContext(Dispatchers.IO) {
                var lastSavedSec = 0L
                results.map { result ->
                    if (!result.success) return@map result
                    try {
                        while (System.currentTimeMillis() / 1000 <= lastSavedSec) {
                            delay(50)
                        }
                        val saved = MediaStoreSaver.save(
                            this@SplitService,
                            File(tempDir(), result.fileName),
                        )
                        lastSavedSec = System.currentTimeMillis() / 1000
                        result.copy(mediaStoreUri = saved.toString())
                    } catch (e: Exception) {
                        result.copy(success = false, error = "存入 MediaStore 失敗：${e.message}")
                    }
                }
            }

            SplitStateHolder.update(SplitJobState.Finished(finalResults))
        } catch (e: CancellationException) {
            SplitStateHolder.update(SplitJobState.Cancelled)
            throw e
        } catch (e: Exception) {
            SplitStateHolder.update(SplitJobState.Failed(e.message ?: e.toString()))
        } finally {
            cleanupTempDir()
            jobRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private data class SourceMetadata(
        val durationMs: Long,
        val bitrate: Int,
    )

    private fun readMetadata(uri: Uri): SourceMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: error("無法讀取影片長度")
            val bitrate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
                ?: 0
            SourceMetadata(duration, bitrate)
        } finally {
            retriever.release()
        }
    }

    private fun tempDir() = File(filesDir, "splits")

    private fun cleanupTempDir() {
        tempDir().deleteRecursively()
    }

    // --- Notification ---

    private fun startForegroundWithNotification(notification: Notification) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(current: Int, total: Int, segmentProgress: Int) {
        val text = getString(R.string.notification_progress, current, total, segmentProgress)
        val overall = ((current - 1) * 100 + segmentProgress) / total
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, overall, indeterminate = false))
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SplitService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.action_cancel), cancelIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "split_progress"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "dev.yucj.videosplitter.action.START"
        const val ACTION_CANCEL = "dev.yucj.videosplitter.action.CANCEL"
        const val EXTRA_URI = "uri"
        const val EXTRA_MAX_SEC = "max_sec"
        const val EXTRA_MODE = "mode"

        fun start(context: Context, uri: Uri, maxSec: Int, mode: SplitMode) {
            val intent = Intent(context, SplitService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_MAX_SEC, maxSec)
                .putExtra(EXTRA_MODE, mode.name)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, SplitService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}
