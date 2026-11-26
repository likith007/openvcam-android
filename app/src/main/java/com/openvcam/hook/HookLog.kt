package com.openvcam.hook

import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/** Throttled Xposed logging so diagnostics stay useful without flooding logcat. */
object HookLog {

    private val last = ConcurrentHashMap<String, Long>()
    private const val INTERVAL_MS = 30_000L

    fun once(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = last[key] ?: Long.MIN_VALUE
        if (now - previous < INTERVAL_MS) return
        last[key] = now
        XposedBridge.log("[OpenVCam] $message")
    }

    fun error(tag: String, throwable: Throwable) {
        once("error.$tag", "$tag failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
    }
}
