package com.openvcam.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openvcam.core.ConfigStore
import com.openvcam.core.SourceConfig

data class AppEntry(val label: String, val packageName: String)

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

    fun installedApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        return try {
            pm.getInstalledPackages(0)
                .mapNotNull { info ->
                    val pkg = info.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName || pkg == "android") return@mapNotNull null
                    val appInfo = info.applicationInfo ?: return@mapNotNull null
                    val label = pm.getApplicationLabel(appInfo)?.toString()?.takeIf { it.isNotBlank() }
                        ?: pkg.substringAfterLast('.')
                    AppEntry(label, pkg)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
