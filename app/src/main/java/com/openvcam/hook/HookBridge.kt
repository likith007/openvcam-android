package com.openvcam.hook

import android.content.res.AssetFileDescriptor
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Supplies OpenVCam config and media to a scoped app through an abstract local
 * socket. Android 11+ package visibility prevents a target app from resolving
 * OpenVCam's exported provider when the target did not declare it, so the
 * provider lookup fails even though OpenVCam is installed. The local socket is
 * visible across app processes and needs no root in the target.
 */
object HookBridge {

    private const val AUTHORITY = "com.openvcam.provider"
    private const val SOCKET_NAME = "openvcam-bridge"

    @Volatile
    private var installed = false

    @Volatile
    private var cachedConfig: String? = null

    @Volatile
    private var lastFetch = 0L

    @Volatile
    private var lastHeartbeat = 0L

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true

        val resolver = HookUtils.findClass("android.content.ContentResolver", loader) ?: return
        HookUtils.hookAllNamed(resolver, listOf("call"), callHook, "OpenVCam.bridge.call")
        HookUtils.hookAllNamed(
            resolver,
            listOf("openFileDescriptor"),
            mediaHook("pfd"),
            "OpenVCam.bridge.pfd"
        )
        HookUtils.hookAllNamed(
            resolver,
            listOf("openAssetFileDescriptor"),
            mediaHook("afd"),
            "OpenVCam.bridge.afd"
        )
        HookUtils.hookAllNamed(
            resolver,
            listOf("openInputStream"),
            mediaHook("stream"),
            "OpenVCam.bridge.stream"
        )
        XposedBridge.log("[OpenVCam] bridge installed")
    }

    private fun request(command: String, timeoutMs: Int, block: (DataInputStream) -> Unit) {
        try {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
                )
                socket.soTimeout = timeoutMs
                val out = DataOutputStream(socket.outputStream)
                out.writeBytes("$command\n")
                out.flush()
                block(DataInputStream(socket.inputStream))
            }
        } catch (t: Throwable) {
            HookLog.once("bridge.fail", "bridge request failed: ${t.message}")
        }
    }

    fun configJson(): String? {
        val now = SystemClock.elapsedRealtime()
        if (cachedConfig != null && now - lastFetch < 300L) return cachedConfig
        if (cachedConfig == null && now - lastFetch < 2_000L) return null
        lastFetch = now
        var result: String? = null
        request("CONFIG", 1_500) { result = it.readUTF() }
        if (!result.isNullOrEmpty()) {
            cachedConfig = result
        } else {
            // Do not hammer the camera thread while the provider app is asleep.
            lastFetch = now + 1_700L
        }
        return cachedConfig
    }

    fun heartbeat(packageName: String) {
        if (packageName.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeat < 10_000L) return
        lastHeartbeat = now
        request("HEARTBEAT $packageName", 1_500) { it.readUTF() }
    }

    private fun authorityOf(value: Any?): String? = when (value) {
        is Uri -> value.authority
        is String -> value
        else -> null
    }

    private val callHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (authorityOf(param.args.getOrNull(0)) != AUTHORITY) return
            val method = param.args.getOrNull(1) as? String ?: return
            val json = configJson()
            param.result = Bundle().apply {
                when (method) {
                    "getConfig", "heartbeat" -> putString("json", json)
                    "getStatus" -> {
                        putBoolean("hookActive", true)
                        putLong("lastHeartbeat", System.currentTimeMillis())
                        putString("json", json)
                    }
                }
            }
            if (method == "heartbeat") {
                heartbeat(
                    (param.args.getOrNull(3) as? Bundle)?.getString("package")
                        ?: HookConfig.context?.packageName
                        ?: ""
                )
            }
            HookLog.once("bridge.call.$method", "config bridge served $method")
        }
    }

    private fun mediaHook(kind: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val uri = param.args.getOrNull(0) as? Uri ?: return
            if (uri.authority != AUTHORITY) return
            val name = uri.lastPathSegment ?: return
            if (name.contains("..") || name.contains("/")) return
            val file = copyMedia(name) ?: return
            param.result = when (kind) {
                "pfd" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                "afd" -> AssetFileDescriptor(
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                    0L,
                    AssetFileDescriptor.UNKNOWN_LENGTH
                )
                "stream" -> FileInputStream(file)
                else -> return
            }
            HookLog.once("bridge.media.$name", "config bridge served media $name")
        }
    }

    private fun copyMedia(name: String): File? {
        val context = HookConfig.context ?: return null
        val dest = File(File(context.cacheDir, "openvcam").apply { mkdirs() }, name)
        request("MEDIA $name", 10_000) { input ->
            val length = input.readLong()
            if (length < 0L) return@request
            FileOutputStream(dest).use { out ->
                var remaining = length
                val buffer = ByteArray(16_384)
                while (remaining > 0L) {
                    val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
        }
        return dest.takeIf { it.exists() && it.length() > 0L }
    }
}
