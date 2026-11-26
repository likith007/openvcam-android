package com.openvcam.ui

import android.content.Context
import android.content.Intent
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
            // Launchable apps are far faster to enumerate than every installed package
            // and are the apps that actually own a camera UI.
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .mapNotNull { resolve ->
                    val pkg = resolve.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName || pkg == "android") return@mapNotNull null
                    val label = resolve.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
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
