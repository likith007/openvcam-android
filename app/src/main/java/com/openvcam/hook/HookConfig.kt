package com.openvcam.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
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

    @Volatile
    private var lastHeartbeat: Long = 0L

    fun snapshot(): JSONObject? {
        val ctx = context ?: return cached
        val now = SystemClock.elapsedRealtime()
        if (cached != null && now - lastFetch < 300L) return cached
        lastFetch = now
        try {
            val bundle = ctx.contentResolver.call(ROOT_URI, "getConfig", null, null)
            val json = bundle?.getString("json")
            if (!json.isNullOrEmpty()) cached = JSONObject(json)
        } catch (_: Throwable) {
        }

        if (now - lastHeartbeat > 10_000L) {
            lastHeartbeat = now
            try {
                ctx.contentResolver.call(
                    ROOT_URI,
                    "heartbeat",
                    null,
                    Bundle().apply { putString("package", ctx.packageName) }
                )
            } catch (_: Throwable) {
            }
        }
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
