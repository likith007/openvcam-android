package com.openvcam.hook

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.media.ImageReader
import android.view.Surface
import de.robv.android.xposed.callbacks.XC_MethodHook
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor

object Camera2Hook {

    private class Dummy(
        val original: Surface,
        val surface: Surface,
        val texture: SurfaceTexture?,
        val drainer: DummyFrameDrainer?
    )

    private class SessionState(val device: Any) {
        var disposed = false
        val dummies = mutableListOf<Dummy>()
        val renderers = mutableListOf<GLPreviewRenderer>()
    }

    private val imageReaderSurfaces =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Surface, Boolean>()))
    private val dummyByOriginal = Collections.synchronizedMap(WeakHashMap<Surface, Dummy>())
    private val originalByDummy = Collections.synchronizedMap(WeakHashMap<Surface, Surface>())
    private val sessions = Collections.synchronizedMap(WeakHashMap<Any, SessionState>())
    private val sessionKeys = Collections.synchronizedMap(WeakHashMap<Any, SessionState>())
    private val pending = ThreadLocal.withInitial { mutableListOf<Dummy>() }
    @Volatile private var jpegDecoder: Nv21Decoder? = null
    @Volatile private var syncRetryPending = false

    private fun isReaderSurface(surface: Surface): Boolean = imageReaderSurfaces.contains(surface)

    private fun dummyFor(original: Surface): Dummy {
        dummyByOriginal[original]?.let { existing ->
            if (existing.surface.isValid) return existing
            releaseDummy(existing)
        }
        val drainer = DummyFrameDrainer.createOrNull()
        val texture = drainer?.texture ?: SurfaceTexture(17).also { fallback ->
            fallback.setDefaultBufferSize(1, 1)
            fallback.setOnFrameAvailableListener { runCatching { it.updateTexImage() } }
        }
        val size = SurfaceSizeTracker.applyTo(texture, original)
        HookLog.once(
            "c2.dummy",
            "Camera2 dummy surface ${size.width}x${size.height} for ${original.javaClass.simpleName}"
        )
        val dummy = Dummy(original, Surface(texture), texture, drainer)
        dummyByOriginal[original] = dummy
        originalByDummy[dummy.surface] = original
        return dummy
    }

    private fun releaseDummy(dummy: Dummy) {
        if (dummyByOriginal[dummy.original] === dummy) dummyByOriginal.remove(dummy.original)
        originalByDummy.remove(dummy.surface)
        runCatching { dummy.surface.release() }
        if (dummy.drainer != null) dummy.drainer.release() else runCatching { dummy.texture?.release() }
    }

    private fun replaceOutputs(outputs: List<*>): List<Any?> {
        val collector = pending.get()
        return outputs.map { item ->
            when (item) {
                is Surface -> {
                    if (isReaderSurface(item)) item else {
                        val dummy = dummyFor(item)
                        collector.add(dummy)
                        dummy.surface
                    }
                }
                is OutputConfiguration -> {
                    val surface = runCatching { item.surface }.getOrNull()
                    if (surface != null && !isReaderSurface(surface)) {
                        val dummy = dummyFor(surface)
                        collector.add(dummy)
                        runCatching { OutputConfiguration(dummy.surface) }.getOrDefault(item)
                    } else item
                }
                else -> item
            }
        }
    }

    private fun replaceSessionConfiguration(config: Any): Any? {
        return try {
            val clazz = config.javaClass
            val outputs = clazz.getMethod("getOutputConfigurations").invoke(config) as? List<*>
                ?: return null
            val replaced = replaceOutputs(outputs)
            val configs = replaced.filterIsInstance<OutputConfiguration>()
            if (configs.size != replaced.size) return null
            val type = clazz.getMethod("getSessionType").invoke(config) as Int
            val executor = clazz.getMethod("getExecutor").invoke(config) as Executor
            val callback = clazz.getMethod("getStateCallback").invoke(config)
                as CameraCaptureSession.StateCallback
            SessionConfiguration(type, configs, executor, callback)
        } catch (_: Throwable) {
            null
        }
    }

    private fun stopRenderers(state: SessionState) {
        state.renderers.forEach { it.stop() }
        state.renderers.clear()
    }

    private fun disposeState(state: SessionState, key: Any? = null) {
        synchronized(state) {
            if (state.disposed) return
            state.disposed = true
        }
        stopRenderers(state)
        state.dummies.forEach { releaseDummy(it) }
        state.dummies.clear()
        if (key != null) {
            sessions.remove(key)
            sessionKeys.remove(key)
        }
    }

    private fun scheduleConfigSync(attempts: Int = 16) {
        if (syncRetryPending) return
        syncRetryPending = true
        Handler(Looper.getMainLooper()).postDelayed({
            syncRetryPending = false
            val cfg = HookConfig.snapshot()
            syncRenderers(cfg)
            val waiting = sessions.values.any { state ->
                !state.disposed && HookConfig.shouldReplace(cfg) && state.renderers.isEmpty()
            }
            if (waiting && attempts > 1) scheduleConfigSync(attempts - 1)
        }, 300L)
    }

    private fun syncRenderers(cfg: JSONObject?) {
        val context = HookConfig.context ?: return
        val shouldReplace = HookConfig.shouldReplace(cfg)
        val source = HookConfig.activeSource(cfg)
        val usable = HookConfig.pathOf(source)?.let { path ->
            HookConfig.typeOf(source).let { it == "video" || it == "image" }
        } ?: false
        var needsRetry = false
        sessions.values.toList().forEach { state ->
            if (state.disposed) return@forEach
            if (!shouldReplace || !usable) {
                stopRenderers(state)
                if (shouldReplace && !usable) needsRetry = true
                return@forEach
            }
            if (state.renderers.isEmpty()) {
                state.dummies.forEach { dummy ->
                    if (!dummy.original.isValid) {
                        needsRetry = true
                        return@forEach
                    }
                    val renderer = GLPreviewRenderer(context, dummy.original)
                    renderer.start(source, cfg)
                    state.renderers.add(renderer)
                    HookLog.once("c2.renderer", "Camera2 renderer started on ${dummy.original.javaClass.simpleName}")
                }
            }
            state.renderers.forEach { renderer ->
                renderer.start(source, cfg)
                renderer.updateConfig(source, cfg)
            }
        }
        if (needsRetry) scheduleConfigSync()
    }

    private fun replaceJpeg(image: Image, decoder: Nv21Decoder, cfg: JSONObject?) {
        val buffer = image.planes.getOrNull(0)?.buffer ?: return
        if (buffer.isReadOnly) return
        val capacity = buffer.capacity()
        if (capacity <= 0) return

        var width = image.width
        var height = image.height
        var frame = decoder.frame(width, height)
            ?: ByteArray(width * height * 3 / 2) { 128.toByte() }
        var bytes = encodeJpeg(frame, width, height, 90)

        // ImageReader JPEG buffers are sized to the camera's original JPEG. Shrink the
        // replacement until it fits instead of writing a truncated, corrupt image.
        var quality = 90
        while (bytes.size > capacity && (width > 320 || height > 240)) {
            width = (width / 2).coerceAtLeast(160)
            height = (height / 2).coerceAtLeast(120)
            frame = decoder.frame(width, height)
                ?: ByteArray(width * height * 3 / 2) { 128.toByte() }
            quality = if (quality > 65) quality - 10 else quality
            bytes = encodeJpeg(frame, width, height, quality)
        }
        if (bytes.size > capacity) return

        runCatching {
            buffer.clear()
            buffer.put(bytes)
            buffer.position(0)
            buffer.limit(bytes.size)
        }
    }

    private fun encodeJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray = try {
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        image.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, out)
        out.toByteArray()
    } catch (_: Throwable) {
        ByteArray(0)
    }

    fun install(loader: ClassLoader) {
        val readerClass = HookUtils.findClass("android.media.ImageReader", loader)
        if (readerClass != null) {
            HookUtils.hookAllNamed(
                readerClass, listOf("newInstance", "build"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val reader = param.result as? ImageReader ?: return
                        runCatching { reader.surface }.getOrNull()?.let { imageReaderSurfaces.add(it) }
                    }
                },
                "Camera2.ImageReader"
            )

            HookUtils.hookAllNamed(
                readerClass, listOf("acquireLatestImage", "acquireNextImage"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = HookConfig.snapshot()
                        if (!HookConfig.shouldReplace(cfg)) return
                        val image = param.result as? Image ?: return
                        if (image.format != ImageFormat.JPEG) return
                        val context = HookConfig.context ?: return
                        val decoder = jpegDecoder ?: Nv21Decoder(context).also { jpegDecoder = it }
                        decoder.configure(HookConfig.activeSource(cfg), cfg)
                        decoder.updateConfig(cfg)
                        replaceJpeg(image, decoder, cfg)
                    }
                },
                "Camera2.jpeg"
            )
        }

        val deviceClass = HookUtils.findClass("android.hardware.camera2.impl.CameraDeviceImpl", loader)
            ?: HookUtils.findClass("android.hardware.camera2.CameraDevice", loader)
            ?: return

        val sessionHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                pending.get().clear()
                val cfg = HookConfig.snapshot()
                if (!HookConfig.shouldReplace(cfg)) return
                if (param.args.isEmpty()) return
                val first = param.args[0]
                val replaced: Any?
                val index: Int
                when {
                    first is SessionConfiguration -> {
                        replaced = replaceSessionConfiguration(first) ?: return
                        index = 0
                    }
                    first is List<*> -> {
                        replaced = replaceOutputs(first)
                        index = 0
                    }
                    param.args.getOrNull(1) is List<*> -> {
                        // createReprocessableCaptureSession* puts outputs in the second argument.
                        replaced = replaceOutputs(param.args[1] as List<*>)
                        index = 1
                    }
                    else -> return
                }
                param.args[index] = replaced
                HookLog.once("c2.session", "Camera2 session outputs replaced (${param.method.name})")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val cfg = HookConfig.snapshot()
                if (!HookConfig.shouldReplace(cfg)) return
                val context = HookConfig.context ?: return
                val device = param.thisObject
                val state = SessionState(device)
                state.dummies.addAll(pending.get())
                pending.get().clear()

                state.dummies.forEach { dummy ->
                    if (!dummy.original.isValid) return@forEach
                    val renderer = GLPreviewRenderer(context, dummy.original)
                    renderer.start(HookConfig.activeSource(cfg), cfg)
                    state.renderers.add(renderer)
                }
                val key = param.result ?: device
                sessions[key] = state
                param.result?.let { sessionKeys[it] = state }
            }
        }

        HookUtils.hookAllNamed(
            deviceClass,
            listOf(
                "createCaptureSession",
                "createCaptureSessionByOutputConfigurations",
                "createConstrainedHighSpeedCaptureSession",
                "createReprocessableCaptureSession",
                "createReprocessableCaptureSessionByConfigurations"
            ),
            sessionHook,
            "Camera2.session"
        )

        val builderClass = HookUtils.findClass(
            "android.hardware.camera2.CaptureRequest\$Builder", loader
        )
        if (builderClass != null) {
            HookUtils.hookAllNamed(
                builderClass, listOf("addTarget"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!HookConfig.shouldReplace(HookConfig.snapshot())) return
                        val surface = param.args.getOrNull(0) as? Surface ?: return
                        val dummy = dummyByOriginal[surface] ?: return
                        param.args[0] = dummy.surface
                    }
                },
                "Camera2.addTarget"
            )
            HookUtils.hookAllNamed(
                builderClass, listOf("build"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncRenderers(HookConfig.snapshot())
                    }
                },
                "Camera2.build"
            )
        }

        HookUtils.hookAllNamed(
            deviceClass, listOf("close"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val device = param.thisObject
                    sessions.entries.toList().forEach { (key, state) ->
                        if (state.device === device) {
                            sessions.remove(key)
                            disposeState(state)
                        }
                    }
                    sessionKeys.entries.toList().forEach { (key, state) ->
                        if (state.device === device) {
                            sessionKeys.remove(key)
                            disposeState(state)
                        }
                    }
                    jpegDecoder?.release()
                    jpegDecoder = null
                }
            },
            "Camera2.deviceClose"
        )

        val sessionClass = HookUtils.findClass(
            "android.hardware.camera2.CameraCaptureSession", loader
        )
        if (sessionClass != null) {
            HookUtils.hookAllNamed(
                sessionClass, listOf("close"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val session = param.thisObject
                        sessionKeys.remove(session)?.let {
                            sessions.remove(session)
                            disposeState(it, session)
                        }
                        sessions.remove(session)?.let { disposeState(it, session) }
                    }
                },
                "Camera2.sessionClose"
            )

            // Preview requests repeat constantly; use them to apply live config changes
            // and recover a renderer whose surface became valid late.
            HookUtils.hookAllNamed(
                sessionClass,
                listOf("setRepeatingRequest", "capture", "captureBurst", "setRepeatingBurst"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncRenderers(HookConfig.snapshot())
                    }
                },
                "Camera2.sync"
            )
        }
    }
}
