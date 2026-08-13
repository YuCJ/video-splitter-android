package dev.yucj.videosplitter.split

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** 把私有目錄的 mp4 複製進 MediaStore（Movies/VideoSplitter/），讓 IG 等其他 app 讀得到。 */
object MediaStoreSaver {

    private const val RELATIVE_DIR = "Movies/VideoSplitter"

    fun save(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            // 不要在這裡寫 DATE_ADDED/DATE_TAKEN/DATE_MODIFIED：MediaProvider 在 insert 時
            // 會無條件把 DATE_ADDED 蓋成當下時間，清 IS_PENDING 發佈時又會觸發 scan，
            // 把 DATE_TAKEN/DATE_MODIFIED 從檔案本身重算——app 給的值都活不下來。
            // 段落順序改由呼叫端「每段 insert 之間跨秒」來保證（見 SplitService）。
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore insert failed for ${file.name}")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream returned null for $uri")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
