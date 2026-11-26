package com.openvcam.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openvcam.core.ConfigStore
import com.openvcam.core.SourceConfig

object UiUtils {

    fun importMedia(context: Context, uri: Uri, type: String): SourceConfig? {
        return try {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: "media"

            val ext = when {
                displayName.contains('.') -> displayName.substringAfterLast('.')
                type == "video" -> "mp4"
                else -> "jpg"
            }
            val filename = "src_${System.currentTimeMillis()}.${ext}"
            val out = ConfigStore.get(context).mediaFile(filename)
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            SourceConfig(type = type, name = displayName, path = filename)
        } catch (_: Throwable) {
            null
        }
    }
}
