package com.openvcam.hook

import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.InputConfiguration
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
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
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

    // One canonical set of states; the maps below are only lookup indexes.
    private val states = Collections.newSetFromMap(
        Collections.synchronizedMap(IdentityHashMap<SessionState, Boolean>())
    )
    private val devices = Collections.synchronizedMap(WeakHashMap<Any, SessionState>())
    private val sessionKeys = Collections.synchronizedMap(WeakHashMap<Any, SessionState>())

    private val hookedMethods = Collections.newSetFromMap(
        Collections.synchronizedMap(IdentityHashMap<Method, Boolean>())
    )
    private val pending = ThreadLocal.withInitial { mutableListOf<Dummy>() }
    @Volatile private var jpegDecoder: Nv21Decoder? = null
    @Volatile private var syncRetryPending = false
    @Volatile private var internalSessionHooked = false

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

    private fun hookOnce(
        method: Method,
        callback: XC_MethodHook,
        tag: String
    ): Boolean {
        if (!hookedMethods.add(method)) return false
        return HookUtils.hook(method, callback, tag)
    }

    private fun hookAllNamedOnce(
        clazz: Class<*>?,
        names: Collection<String>,
        callback: XC_MethodHook,
        tag: String
    ) {
        if (clazz == null) return
        HookUtils.declaredMethods(clazz).forEach { method ->
            if (method.name in names) {
                hookOnce(method, callback, "$tag.${method.name}")
            }
        }
    }

    private fun rollbackPending(since: Int) {
        val collector = pending.get()
        val created = collector.drop(since)
        created.forEach { runCatching { releaseDummy(it) } }
        while (collector.size > since) collector.removeAt(collector.lastIndex)
    }

    private val outputMetadataGetters = arrayOf(
        "getPhysicalCameraId" to "setPhysicalCameraId",
        "getStreamUseCase" to "setStreamUseCase",
        "getTimestampBase" to "setTimestampBase",
        "getDynamicRangeProfile" to "setDynamicRangeProfile",
        "getSensorPixelModesUsed" to "setSensorPixelModesUsed",
        "getColorSpace" to "setColorSpace",
        "getMirrorMode" to "setMirrorMode",
        "isReadoutTimestampEnabled" to "setReadoutTimestampEnabled",
        "getMultiResolutionMode" to "setMultiResolutionMode"
    )

    private val safeOutputFields = setOf(
        "mPhysicalCameraId",
        "mStreamUseCase",
        "mTimestampBase",
        "mDynamicRangeProfile",
        "mSensorPixelModesUsed",
        "mColorSpace",
        "mMirrorMode",
        "mReadoutTimestampEnabled",
        "mMultiResolutionMode",
        "mSurfaceSharingId"
    )

    /**
     * Builds a replacement OutputConfiguration that keeps CameraX's stream-sharing and
     * vendor metadata without copying surface-derived size/format/generation values.
     * Those values belong to the dummy surfaces, not to the original camera surfaces.
     */
    private fun copyOutputConfiguration(
        original: OutputConfiguration,
        replacements: List<Surface>
    ): OutputConfiguration {
        val copy = OutputConfiguration(replacements.first())
        replacements.drop(1).forEach { runCatching { copy.addSurface(it) } }

        outputMetadataGetters.forEach { (getterName, setterName) ->
            runCatching {
                val getter = original.javaClass.getMethod(getterName)
                val value = getter.invoke(original) ?: return@runCatching
                val setter = copy.javaClass.getMethod(setterName, getter.returnType)
                setter.invoke(copy, value)
            }
        }

        var clazz: Class<*>? = original.javaClass
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredFields.forEach { field ->
                if (field.name !in safeOutputFields || Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.set(copy, field.get(original))
                }
            }
            clazz = clazz.superclass
        }
        return copy
    }

    /** Fallback for unusual OutputConfiguration implementations that resist copying. */
    private fun mutateOutputConfiguration(
        config: OutputConfiguration,
        originals: List<Surface>,
        replacements: List<Surface>
    ) {
        val map = IdentityHashMap<Surface, Surface>()
        originals.forEachIndexed { index, surface -> map[surface] = replacements.getOrNull(index) ?: surface }
        var clazz: Class<*>? = config.javaClass
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                try {
                    field.isAccessible = true
                    when (val value = field.get(config)) {
                        is Surface -> map[value]?.let { field.set(config, it) }
                        is MutableList<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = value as MutableList<Any?>
                            var index = 0
                            while (index < list.size) {
                                val item = list[index]
                                if (item is Surface) map[item]?.let { list[index] = it }
                                index++
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
        }
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
                    val surfaces = runCatching { item.surfaces }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOfNotNull(runCatching { item.surface }.getOrNull())
                    if (surfaces.isEmpty()) return@map item
                    val replacements = surfaces.map { surface ->
                        if (isReaderSurface(surface)) surface else {
                            val dummy = dummyFor(surface)
                            collector.add(dummy)
                            dummy.surface
                        }
                    }
                    val unchanged = surfaces.indices.all { surfaces[it] === replacements[it] }
                    if (unchanged) return@map item
                    runCatching { copyOutputConfiguration(item, replacements) }
                        .onFailure {
                            HookLog.once(
                                "c2.output-copy",
                                "OutputConfiguration copy failed: ${it.message}"
                            )
                            runCatching { mutateOutputConfiguration(item, surfaces, replacements) }
                        }
                        .getOrDefault(item)
                }
                else -> item
            }
        }
    }

    /**
     * Builds a real replacement SessionConfiguration. Returning the original object after
     * computing dummy outputs would leave CameraX writing to the same surfaces our renderer
     * draws into, which causes black/flickering previews.
     */
    private fun replaceSessionConfiguration(config: Any): Any? {
        val before = pending.get().size
        return try {
            val clazz = config.javaClass
            val outputs = clazz.getMethod("getOutputConfigurations").invoke(config) as? List<*>
                ?: run {
                    rollbackPending(before)
                    return null
                }
            val replaced = replaceOutputs(outputs)
            if (replaced.any { it !is OutputConfiguration }) {
                rollbackPending(before)
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val newOutputs = replaced as List<OutputConfiguration>
            val sessionType = clazz.getMethod("getSessionType").invoke(config) as Int
            val executor = clazz.getMethod("getExecutor").invoke(config) as Executor
            val callback = clazz.getMethod("getStateCallback").invoke(config) as
                CameraCaptureSession.StateCallback
            val copy = SessionConfiguration(sessionType, newOutputs, executor, callback)

            runCatching {
                (clazz.getMethod("getInputConfiguration").invoke(config) as? InputConfiguration)
                    ?.let { copy.setInputConfiguration(it) }
            }
            runCatching {
                (clazz.getMethod("getSessionParameters").invoke(config) as? CaptureRequest)
                    ?.let { copy.setSessionParameters(it) }
            }
            runCatching {
                val colorSpace = clazz.getMethod("getColorSpace").invoke(config) as? ColorSpace
                if (colorSpace != null) {
                    SessionConfiguration::class.java
                        .getMethod("setColorSpace", ColorSpace::class.java)
                        .invoke(copy, colorSpace)
                }
            }
            copy
        } catch (_: Throwable) {
            rollbackPending(before)
            null
        }
    }

    private fun invokeOn(
        target: Any?,
        method: java.lang.reflect.Method,
        args: Array<Any?>?
    ): Any? = try {
        method.isAccessible = true
        method.invoke(target, *(args ?: emptyArray()))
    } catch (_: Throwable) {
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }

    /**
     * The hidden createCaptureSessionInternal path returns void, so the configured
     * session is learned from the original StateCallback instead of the hook result.
     */
    private fun wrapStateCallback(
        original: Any,
        device: Any,
        dummies: List<Dummy>
    ): Any = Proxy.newProxyInstance(
        CameraCaptureSession.StateCallback::class.java.classLoader,
        arrayOf(CameraCaptureSession.StateCallback::class.java)
    ) { _, method, args ->
        when (method.name) {
            "onConfigured" -> {
                (args?.getOrNull(0) as? CameraCaptureSession)?.let { session ->
                    registerState(device, dummies, session)
                }
            }
            "onClosed" -> {
                (args?.getOrNull(0) as? CameraCaptureSession)?.let { unregisterState(it) }
            }
        }
        invokeOn(original, method, args)
    }

    private fun registerState(device: Any, dummies: List<Dummy>, session: Any?) {
        if (dummies.isEmpty()) return
        val cfg = HookConfig.snapshot()
        if (!HookConfig.shouldReplace(cfg)) return
        val context = HookConfig.context ?: return
        val existing = states.firstOrNull {
            !it.disposed && it.device === device && it.dummies == dummies
        }
        val state = existing ?: SessionState(device).also { created ->
            created.dummies.addAll(dummies)
            states.add(created)
            devices[device] = created
            dummies.forEach { dummy ->
                if (!dummy.original.isValid) return@forEach
                val renderer = GLPreviewRenderer(context, dummy.original)
                renderer.start(HookConfig.activeSource(cfg), cfg)
                created.renderers.add(renderer)
            }
            HookLog.once(
                "c2.renderer",
                "Camera2 renderer started for ${created.dummies.size} output(s)"
            )
        }
        if (session != null) sessionKeys[session] = state
    }

    private fun unregisterState(session: Any) {
        val state = sessionKeys.remove(session) ?: return
        devices.entries.toList().forEach { (key, value) ->
            if (value === state) devices.remove(key)
        }
        disposeState(state)
    }

    private fun stopRenderers(state: SessionState) {
        state.renderers.forEach { it.stop() }
        state.renderers.clear()
    }

    private fun disposeState(state: SessionState) {
        synchronized(state) {
            if (state.disposed) return
            state.disposed = true
        }
        states.remove(state)
        stopRenderers(state)
        state.dummies.forEach { releaseDummy(it) }
        state.dummies.clear()
    }

    private fun scheduleConfigSync(attempts: Int = 16) {
        if (syncRetryPending) return
        syncRetryPending = true
        Handler(Looper.getMainLooper()).postDelayed({
            syncRetryPending = false
            val cfg = HookConfig.snapshot()
            syncRenderers(cfg)
            val waiting = states.any { state ->
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
        states.toList().forEach { state ->
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

    /**
     * CameraX 1.4 calls the hidden CameraDeviceImpl.createCaptureSessionInternal
     * path, so hooking only the public createCaptureSession overloads can miss the
     * real session setup. Reflective lookup keeps this working across Android
     * versions without hard-coding a private method signature.
     */
    private fun installInternalSessionHook(deviceClass: Class<*>) {
        val methods = HookUtils.declaredMethods(deviceClass)
            .filter { it.name == "createCaptureSessionInternal" }
        if (methods.isEmpty()) return

        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                pending.get().clear()
                if (!HookConfig.shouldReplace(HookConfig.snapshot())) return
                val args = param.args
                val callbackIndex = args.indexOfFirst { it is CameraCaptureSession.StateCallback }
                val outputIndex = args.indexOfFirst { arg ->
                    arg is List<*> && arg.any { it is OutputConfiguration || it is Surface }
                }
                if (callbackIndex < 0 || outputIndex < 0) return
                val outputs = args[outputIndex] as? List<*> ?: return
                val originalCallback = args[callbackIndex] ?: return

                args[outputIndex] = replaceOutputs(outputs)
                args[callbackIndex] = wrapStateCallback(
                    originalCallback,
                    param.thisObject,
                    pending.get().toList()
                )
                HookLog.once("c2.internal", "Camera2 internal session outputs replaced")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val dummies = pending.get().toList()
                pending.get().clear()
                registerState(param.thisObject, dummies, null)
            }
        }

        val installedCount = methods.count { hookOnce(it, callback, "Camera2.internalSession") }
        if (installedCount == 0) return
        internalSessionHooked = true
        HookLog.once(
            "c2.internal-install",
            "Camera2 internal session hook installed on $installedCount method(s)"
        )
    }

    fun install(loader: ClassLoader) {
        val readerClass = HookUtils.findClass("android.media.ImageReader", loader)
        if (readerClass != null) {
            hookAllNamedOnce(
                readerClass, listOf("newInstance", "build"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val reader = param.result as? ImageReader ?: return
                        runCatching { reader.surface }.getOrNull()?.let { imageReaderSurfaces.add(it) }
                    }
                },
                "Camera2.ImageReader"
            )

            hookAllNamedOnce(
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

        val managerClass = HookUtils.findClass("android.hardware.camera2.CameraManager", loader)
        if (managerClass != null) {
            hookAllNamedOnce(
                managerClass, listOf("openCamera"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookLog.once("c2.open", "Camera2 camera opened by ${HookConfig.context?.packageName}")
                    }
                },
                "Camera2.open"
            )
        }

        val deviceClass = HookUtils.findClass("android.hardware.camera2.impl.CameraDeviceImpl", loader)
            ?: HookUtils.findClass("android.hardware.camera2.CameraDevice", loader)
            ?: return

        installInternalSessionHook(deviceClass)

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
                val dummies = pending.get().toList()
                pending.get().clear()
                registerState(param.thisObject, dummies, param.result)
            }
        }

        // The internal hook also sees calls made through the public overloads, so
        // skip the public hook when it is available to avoid duplicate renderers.
        if (!internalSessionHooked) {
            hookAllNamedOnce(
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
        }

        val builderClass = HookUtils.findClass(
            "android.hardware.camera2.CaptureRequest\$Builder", loader
        )
        if (builderClass != null) {
            hookAllNamedOnce(
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
            hookAllNamedOnce(
                builderClass, listOf("build"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncRenderers(HookConfig.snapshot())
                    }
                },
                "Camera2.build"
            )
        }

        hookAllNamedOnce(
            deviceClass, listOf("close"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val device = param.thisObject
                    devices.entries.toList().forEach { (key, state) ->
                        if (state.device === device) {
                            devices.remove(key)
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

        val closeCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                unregisterState(param.thisObject)
            }
        }
        // Preview requests repeat constantly; use them to apply live config changes
        // and recover a renderer whose surface became valid late.
        val syncCallback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                syncRenderers(HookConfig.snapshot())
            }
        }

        val sessionClasses = listOf(
            "android.hardware.camera2.impl.CameraCaptureSessionImpl",
            "android.hardware.camera2.impl.CameraConstrainedHighSpeedCaptureSessionImpl",
            "android.hardware.camera2.impl.CameraOfflineSessionImpl",
            "android.hardware.camera2.CameraCaptureSession"
        ).mapNotNull { HookUtils.findClass(it, loader) }

        sessionClasses.forEach { sessionClass ->
            hookAllNamedOnce(
                sessionClass,
                listOf("close"),
                closeCallback,
                "Camera2.sessionClose"
            )
            hookAllNamedOnce(
                sessionClass,
                listOf("setRepeatingRequest", "capture", "captureBurst", "setRepeatingBurst"),
                syncCallback,
                "Camera2.sync"
            )
        }
    }
}
