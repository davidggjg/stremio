package com.stremio.lite.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a finished download into the phone's own Movies library so it shows up in the gallery /
 * Files app like any other video, instead of staying buried in the app's private folder.
 *
 * On Android 10+ this goes through MediaStore and needs no permission at all. On older versions it
 * writes to the public Movies directory, which needs WRITE_EXTERNAL_STORAGE (declared with
 * maxSdkVersion="28" in the manifest).
 */
object GalleryExport {

    private const val SUBFOLDER = "Stremio Lite"

    suspend fun export(context: Context, source: File, displayName: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    exportViaMediaStore(context, source, displayName)
                } else {
                    exportToPublicDir(source, displayName)
                }
            }.getOrNull()
        }

    private fun exportViaMediaStore(context: Context, source: File, displayName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeTypeOf(displayName))
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused the insert")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE * 8) }
        } ?: error("Could not open the MediaStore entry for writing")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    private fun exportToPublicDir(source: File, displayName: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            SUBFOLDER
        ).apply { mkdirs() }
        val target = File(dir, displayName)
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    private fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }
}
