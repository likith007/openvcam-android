package com.openvcam.hook

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import de.robv.android.xposed.callbacks.XC_MethodHook
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

object Camera1Hook {

    private class State(val camera: Camera, val context: android.content.Context) {
        var originalTexture: SurfaceTexture? = null
        var originalSurface: Surface? = null
        var targetSurface: Surface? = null
        var ownedTargetSurface: Surface? = null
        var dummyTexture: SurfaceTexture? = null
        var dummyTextureDrainer: DummyFrameDrainer? = null
        var dummySurfaceTexture: SurfaceTexture? = null
        var dummySurfaceDrainer: DummyFrameDrainer? = null
        var dummySurface: Surface? = null
        var renderer: GLPreviewRenderer? = null
        val decoder = Nv21Decoder(context)
    }

    private val states = Collections.synchronizedMap(WeakHashMap<Camera, State>())

    private fun state(camera: Camera): State {
        val context = HookConfig.context ?: throw IllegalStateException("no context")
        return states.getOrPut(camera) { State(camera, context) }
    }

    private fun stopRenderer(s: State) {
        s.renderer?.stop()
        s.renderer = null
    }

    private fun releaseDummy(texture: SurfaceTexture?, drainer: DummyFrameDrainer?) {
        if (drainer != null) drainer.release() else runCatching { texture?.release() }
    }

    private fun dispose(s: State) {
        stopRenderer(s)
        s.decoder.release()
        releaseDummy(s.dummyTexture, s.dummyTextureDrainer)
        runCatching { s.dummySurface?.release() }
        releaseDummy(s.dummySurfaceTexture, s.dummySurfaceDrainer)
        runCatching { s.ownedTargetSurface?.release() }
        s.dummyTexture = null
        s.dummyTextureDrainer = null
        s.dummySurface = null
        s.dummySurfaceTexture = null
        s.dummySurfaceDrainer = null
        s.ownedTargetSurface = null
        s.originalTexture = null
        s.originalSurface = null
        s.targetSurface = null
    }

    private fun ensureRenderer(s: State, cfg: JSONObject?, source: JSONObject?) {
        if (!HookConfig.shouldReplace(cfg)) {
            stopRenderer(s)
            return
        }
        val target = s.targetSurface ?: return
        if (!target.isValid) {
            stopRenderer(s)
            return
        }
        if (s.renderer == null) s.renderer = GLPreviewRenderer(s.context, target)
        s.renderer?.start(source, cfg)
        s.renderer?.updateConfig(source, cfg)
    }

    private class PreviewProxy(
        private val original: Any?,
        private val state: State
    ) : Camera.PreviewCallback {
        override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
            val cfg = HookConfig.snapshot()
            if (HookConfig.shouldReplace(cfg)) {
                val source = HookConfig.activeSource(cfg)
                state.decoder.configure(source, cfg)
                state.decoder.updateConfig(cfg)

                val size = try { camera?.parameters?.previewSize } catch (_: Throwable) { null }
                if (size != null) {
                    val frame = state.decoder.frame(size.width, size.height)
                    if (frame != null && data != null) {
                        val count = minOf(frame.size, data.size)
                        System.arraycopy(frame, 0, data, 0, count)
                        if (data.size > count) {
                            java.util.Arrays.fill(data, count, data.size, 128.toByte())
                        }
                    }
                }
                ensureRenderer(state, cfg, source)
            } else {
                stopRenderer(state)
            }

            try {
                if (original is Camera.PreviewCallback) {
                    original.onPreviewFrame(data, camera)
                } else if (original != null) {
                    val method = original.javaClass.getMethod(
                        "onPreviewFrame", ByteArray::class.java, Camera::class.java
                    )
                    method.invoke(original, data, camera)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun install(loader: ClassLoader) {
        val cameraClass = HookUtils.findClass("android.hardware.Camera", loader) ?: return

        HookUtils.hookAllNamed(
            cameraClass,
            listOf("setPreviewCallback", "setPreviewCallbackWithBuffer", "setOneShotPreviewCallback"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args.getOrNull(0) ?: return
                    if (original is PreviewProxy) return
                    if (!HookConfig.shouldReplace(HookConfig.snapshot())) return
                    val camera = param.thisObject as? Camera ?: return
                    param.args[0] = PreviewProxy(original, state(camera))
                }
            },
            "Camera1.callback"
        )

        HookUtils.hookAllNamed(
            cameraClass,
            listOf("setPreviewTexture"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as? Camera ?: return
                    if (param.args.getOrNull(0) == null) {
                        states[camera]?.let { s ->
                            stopRenderer(s)
                            releaseDummy(s.dummyTexture, s.dummyTextureDrainer)
                            runCatching { s.ownedTargetSurface?.release() }
                            s.dummyTexture = null
                            s.dummyTextureDrainer = null
                            s.ownedTargetSurface = null
                            s.originalTexture = null
                            s.targetSurface = null
                        }
                        return
                    }
                    if (!HookConfig.shouldReplace(HookConfig.snapshot())) return
                    val original = param.args.getOrNull(0) as? SurfaceTexture ?: return
                    val s = state(camera)
                    releaseDummy(s.dummyTexture, s.dummyTextureDrainer)
                    runCatching { s.ownedTargetSurface?.release() }
                    s.originalTexture = original
                    s.originalSurface = null
                    val target = try { Surface(original) } catch (_: Throwable) { null }
                    s.ownedTargetSurface = target
                    s.targetSurface = target
                    val drainer = DummyFrameDrainer.createOrNull()
                    val dummy = drainer?.texture ?: SurfaceTexture(15).also { fallback ->
                        fallback.setDefaultBufferSize(1, 1)
                        fallback.setOnFrameAvailableListener { st -> runCatching { st.updateTexImage() } }
                    }
                    s.dummyTextureDrainer = drainer
                    s.dummyTexture = dummy
                    param.args[0] = dummy
                }
            },
            "Camera1.texture"
        )

        HookUtils.hookAllNamed(
            cameraClass,
            listOf("setPreviewDisplay"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as? Camera ?: return
                    if (param.args.getOrNull(0) == null) {
                        states[camera]?.let { s ->
                            stopRenderer(s)
                            runCatching { s.dummySurface?.release() }
                            releaseDummy(s.dummySurfaceTexture, s.dummySurfaceDrainer)
                            s.dummySurface = null
                            s.dummySurfaceTexture = null
                            s.dummySurfaceDrainer = null
                            s.originalSurface = null
                            s.targetSurface = null
                        }
                        return
                    }
                    if (!HookConfig.shouldReplace(HookConfig.snapshot())) return
                    val holder = param.args.getOrNull(0) as? SurfaceHolder ?: return
                    val s = state(camera)
                    s.originalSurface = try { holder.surface } catch (_: Throwable) { null }
                    s.targetSurface = s.originalSurface
                    s.originalTexture = null
                    runCatching { s.ownedTargetSurface?.release() }
                    s.ownedTargetSurface = null
                    val texture = s.dummySurfaceTexture ?: run {
                        val drainer = DummyFrameDrainer.createOrNull()
                        val st = drainer?.texture ?: SurfaceTexture(16).also { fallback ->
                            fallback.setDefaultBufferSize(1, 1)
                            fallback.setOnFrameAvailableListener { source ->
                                runCatching { source.updateTexImage() }
                            }
                        }
                        s.dummySurfaceDrainer = drainer
                        s.dummySurfaceTexture = st
                        st
                    }
                    s.dummySurface = s.dummySurface ?: Surface(texture)
                    val dummySurface = s.dummySurface
                    val dummy = Proxy.newProxyInstance(
                        SurfaceHolder::class.java.classLoader,
                        arrayOf(SurfaceHolder::class.java)
                    ) { _, method, _ ->
                        when (method.name) {
                            "getSurface" -> dummySurface
                            "getSurfaceFrame" -> Rect(0, 0, 1, 1)
                            "addCallback", "removeCallback", "setFixedSize", "setSizeFromLayout",
                            "setFormat", "setType", "setKeepScreenOn" -> null
                            "isCreating" -> false
                            "getFormat" -> 0
                            "getType" -> 0
                            else -> defaultValue(method.returnType)
                        }
                    }
                    param.args[0] = dummy
                }
            },
            "Camera1.display"
        )

        HookUtils.hookAllNamed(
            cameraClass,
            listOf("startPreview"),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as? Camera ?: return
                    val s = states[camera] ?: return
                    val cfg = HookConfig.snapshot() ?: return
                    ensureRenderer(s, cfg, HookConfig.activeSource(cfg))
                }
            },
            "Camera1.start"
        )

        HookUtils.hookAllNamed(
            cameraClass,
            listOf("stopPreview"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as? Camera ?: return
                    // Keep the saved target so startPreview() can restore the renderer.
                    states[camera]?.let { stopRenderer(it) }
                }
            },
            "Camera1.stop"
        )

        HookUtils.hookAllNamed(
            cameraClass,
            listOf("release"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as? Camera ?: return
                    states.remove(camera)?.let { dispose(it) }
                }
            },
            "Camera1.release"
        )

        HookUtils.hookAllNamed(
            cameraClass,
            listOf("takePicture"),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cfg = HookConfig.snapshot()
                    if (!HookConfig.shouldReplace(cfg)) return
                    val camera = param.thisObject as? Camera ?: return
                    val args = param.args
                    if (args.size < 3) return

                    val shutter = args[0]
                    val jpeg = if (args.size == 3) args[2] else args.getOrNull(3)
                    val s = state(camera)
                    s.decoder.configure(HookConfig.activeSource(cfg), cfg)
                    s.decoder.updateConfig(cfg)

                    val pictureSize = try { camera.parameters.pictureSize } catch (_: Throwable) { null }
                    val width = pictureSize?.width ?: 1280
                    val height = pictureSize?.height ?: 720
                    val frame = s.decoder.frame(width, height)
                    val jpegBytes = if (frame != null) {
                        encodeJpeg(frame, width, height)
                    } else {
                        val black = ByteArray(width * height * 3 / 2) { 128.toByte() }
                        encodeJpeg(black, width, height)
                    }

                    invoke(shutter, "onShutter")
                    invoke(jpeg, "onPictureTaken", jpegBytes, camera)
                    if (args.size == 4) invoke(args[2], "onPictureTaken", null, camera)
                    invoke(args[1], "onPictureTaken", null, camera)

                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching { camera.startPreview() }
                    }, 200)

                    param.setResult(null)
                }
            },
            "Camera1.picture"
        )
    }

    private fun encodeJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray = try {
        val image = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        image.compressToJpeg(Rect(0, 0, width, height), 90, out)
        out.toByteArray()
    } catch (_: Throwable) {
        ByteArray(0)
    }

    private fun invoke(target: Any?, methodName: String, vararg args: Any?) {
        if (target == null) return
        try {
            val types = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
            val method = try {
                target.javaClass.getMethod(methodName, *types)
            } catch (_: NoSuchMethodException) {
                target.javaClass.methods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == args.size
                }
            }
            method?.isAccessible = true
            method?.invoke(target, *args)
        } catch (_: Throwable) {
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
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
