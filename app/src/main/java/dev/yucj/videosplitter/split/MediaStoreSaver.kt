package dev.yucj.videosplitter.split

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** 把私有目錄的 mp4 複製進 MediaStore（Movies/VideoSplitter/），讓 IG 等其他 app 讀得到。 */
object MediaStoreSaver {

    private const val RELATIVE_DIR = "Movies/VideoSplitter"

    fun save(context: Context, file: File, takenAtMs: Long): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            // IG 等相簿選單照時間排序，不看檔名；每段給遞增的時間戳才能維持 part 順序。
            // DATE_TAKEN 是毫秒、DATE_ADDED/DATE_MODIFIED 是秒。
            put(MediaStore.Video.Media.DATE_TAKEN, takenAtMs)
            put(MediaStore.Video.Media.DATE_ADDED, takenAtMs / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, takenAtMs / 1000)
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
