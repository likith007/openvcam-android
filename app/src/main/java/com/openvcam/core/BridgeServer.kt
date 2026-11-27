package com.openvcam.core

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Local socket bridge used by the injected hook instead of an exported
 * ContentProvider. Android's package visibility rules hide providers a target
 * app did not declare, but an abstract local socket works across app
 * processes without extra permissions or root in the target.
 */
object BridgeServer {

    private const val TAG = "OpenVCam.Bridge"
    const val SOCKET_NAME = "openvcam-bridge"

    @Volatile
    private var server: LocalServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    fun start(context: Context) {
        synchronized(this) {
            if (server != null) return
            server = try {
                LocalServerSocket(SOCKET_NAME)
            } catch (t: Throwable) {
                Log.w(TAG, "cannot start bridge: ${t.message}")
                return
            }
            acceptThread = Thread({ acceptLoop(context.applicationContext) }, "openvcam-bridge")
                .apply { isDaemon = true; start() }
            Log.i(TAG, "bridge listening on $SOCKET_NAME")
        }
    }

    fun stop() {
        synchronized(this) {
            runCatching { server?.close() }
            server = null
            acceptThread = null
        }
    }

    private fun acceptLoop(context: Context) {
        while (true) {
            val socket = try {
                server?.accept()
            } catch (_: Throwable) {
                break
            } ?: break
            Thread({ handle(context, socket) }, "openvcam-bridge-client")
                .apply { isDaemon = true; start() }
        }
    }

    private fun handle(context: Context, socket: LocalSocket) {
        socket.use { s ->
            try {
                s.soTimeout = 3_000
                val command = BufferedReader(
                    InputStreamReader(s.inputStream, StandardCharsets.UTF_8)
                ).readLine() ?: return
                val out = DataOutputStream(s.outputStream)
                val parts = command.split(' ', limit = 2)
                when (parts[0]) {
                    "CONFIG" -> out.writeUTF(ConfigStore.get(context).snapshotJson())
                    "STATUS" -> out.writeUTF(
                        "{\"hookActive\":${ConfigStore.get(context).anyHookActive()}}"
                    )
                    "HEARTBEAT" -> {
                        ConfigStore.get(context).recordHeartbeat(parts.getOrNull(1) ?: "")
                        out.writeUTF("OK")
                    }
                    "MEDIA" -> {
                        val name = parts.getOrNull(1) ?: ""
                        val file = ConfigStore.get(context).mediaFile(name)
                        if (name.contains("..") || name.contains("/") || !file.exists()) {
                            out.writeLong(-1L)
                        } else {
                            out.writeLong(file.length())
                            FileInputStream(file).use { it.copyTo(out) }
                        }
                    }
                    else -> out.writeUTF("ERR")
                }
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "bridge request failed: ${t.message}")
            }
        }
    }
}
