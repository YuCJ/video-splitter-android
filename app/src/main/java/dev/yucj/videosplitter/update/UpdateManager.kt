package dev.yucj.videosplitter.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.yucj.videosplitter.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ReleaseInfo(
    /** 去掉 v 前綴的版本字串，如 "0.1.7"。 */
    val version: String,
    val apkUrl: String,
)

/** 對 GitHub Releases 檢查新版、下載 APK、叫起系統安裝流程（不經 Play Store）。 */
object UpdateManager {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/YuCJ/video-splitter-android/releases/latest"

    /** 有比目前安裝版本新的 release 就回傳，否則 null。網路錯誤丟例外。 */
    suspend fun checkForUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val body = try {
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(body)
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }
        if (apkUrl == null) return@withContext null
        if (isNewer(version, BuildConfig.VERSION_NAME)) ReleaseInfo(version, apkUrl) else null
    }

    /** remote 比 local 新則 true。local 帶 -dev 等後綴時視為比同號正式版舊。 */
    fun isNewer(remote: String, local: String): Boolean {
        val localIsDev = "-" in local
        val r = remote.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return localIsDev
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 清掉舊的下載，避免快取堆積。
        dir.listFiles()?.forEach { it.delete() }
        val outFile = File(dir, "update.apk")

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied * 100 / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        outFile
    }

    /**
     * 交給系統安裝器。第一次會被導去開「安裝未知應用程式」授權。
     * 簽章與已安裝版本一致才能覆蓋安裝（CI 用固定 keystore 就是為了這裡）。
     */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
