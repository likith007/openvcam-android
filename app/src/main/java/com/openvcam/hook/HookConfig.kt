package com.openvcam.hook

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject

object HookConfig {
    private const val AUTHORITY = "com.openvcam.provider"
    val ROOT_URI: Uri = Uri.parse("content://$AUTHORITY")

    @Volatile
    var context: Context? = null

    @Volatile
    private var cached: JSONObject? = null

    @Volatile
    private var lastFetch: Long = 0L

    fun snapshot(): JSONObject? {
        val now = SystemClock.elapsedRealtime()
        if (cached != null && now - lastFetch < 300L) return cached
        lastFetch = now

        // The target app cannot resolve com.openvcam.provider because Android's
        // package visibility rules hide providers it did not declare in its own
        // manifest. HookBridge reads the same config through a root shell and
        // intercepts the ContentResolver calls we make here.
        val json = HookBridge.configJson()
        if (!json.isNullOrEmpty()) {
            try {
                cached = JSONObject(json)
                HookLog.once("config.ready", "config received from OpenVCam (${json.length} bytes)")
            } catch (t: Throwable) {
                HookLog.once("config.parse", "invalid config JSON: ${t.message}")
            }
        } else {
            HookLog.once("config.empty", "empty config from bridge")
        }

        context?.packageName?.let { HookBridge.heartbeat(it) }
        return cached
    }

    fun mediaUri(path: String): Uri = Uri.parse("content://$AUTHORITY/media/$path")

    fun activeSource(json: JSONObject?): JSONObject? {
        json ?: return null
        val index = json.optInt("activeIndex", 0).coerceIn(0, 4)
        val sources = json.optJSONArray("sources") ?: return null
        return sources.optJSONObject(index)
    }

    fun shouldReplace(json: JSONObject?): Boolean {
        json ?: return false
        return json.optBoolean("enabled", true) && json.optBoolean("virtualMode", true)
    }

    fun typeOf(source: JSONObject?): String = source?.optString("type", "none") ?: "none"

    fun pathOf(source: JSONObject?): String? {
        val path = source?.optString("path", "") ?: return null
        return path.takeIf { it.isNotEmpty() }
    }
}
