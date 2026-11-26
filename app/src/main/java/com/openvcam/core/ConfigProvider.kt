package com.openvcam.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor

class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** Only OpenVCam itself or a package the user explicitly scoped may read config/media. */
    private fun isTrustedCaller(): Boolean {
        val ctx = context ?: return false
        if (Binder.getCallingUid() == ctx.applicationInfo.uid) return true
        val targets = ConfigStore.get(ctx).config.targets
        val pkg = callingPackage
        if (pkg != null && pkg in targets) return true
        val uid = Binder.getCallingUid()
        return targets.any { target ->
            runCatching { ctx.packageManager.getApplicationInfo(target, 0).uid == uid }
                .getOrDefault(false)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isTrustedCaller()) return Bundle()
        val store = ConfigStore.get(context ?: return null)
        return Bundle().apply {
            when (method) {
                "getConfig" -> putString("json", store.snapshotJson())
                "heartbeat" -> {
                    store.recordHeartbeat(extras?.getString("package") ?: arg ?: "")
                    putString("json", store.snapshotJson())
                }
                "getStatus" -> {
                    val active = store.anyHookActive()
                    putBoolean("hookActive", active)
                    putLong("lastHeartbeat", store.heartbeats().values.maxOrNull() ?: 0L)
                    putString("json", store.snapshotJson())
                }
                "applyControl" -> {
                    // Settings may only be changed from OpenVCam itself.
                    if (Binder.getCallingUid() == context?.applicationInfo?.uid) {
                        val action = extras?.getString("action") ?: ""
                        putString("json", store.applyControl(action, extras?.get("value")))
                    }
                }
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!isTrustedCaller()) return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains("..") || name.contains("/")) return null
        val file = ConfigStore.get(context ?: return null).mediaFile(name)
        if (!file.exists()) return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Throwable) {
            null
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? =
        openFile(uri, mode)?.let { AssetFileDescriptor(it, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }

    override fun getType(uri: Uri): String? = "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
