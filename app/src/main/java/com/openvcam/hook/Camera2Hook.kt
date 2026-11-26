package com.openvcam.hook

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.view.Surface
import de.robv.android.xposed.callbacks.XC_MethodHook
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

object Camera2Hook {

    private val imageReaderSurfaces =
        Collections.newSetFromMap(IdentityHashMap<Surface, Boolean>())
    private val originalToDummy = Collections.synchronizedMap(WeakHashMap<Surface, Surface>())
    private val dummyToOriginal = Collections.synchronizedMap(WeakHashMap<Surface, Surface>())
    private val dummyTextures = Collections.synchronizedList(mutableListOf<SurfaceTexture>())
    private val renderers = Collections.synchronizedList(mutableListOf<GLPreviewRenderer>())
    @Volatile private var jpegDecoder: Nv21Decoder? = null

    private fun dummyFor(original: Surface): Surface {
        originalToDummy[original]?.let { return it }
        val texture = SurfaceTexture(17)
        texture.setOnFrameAvailableListener { runCatching { it.updateTexImage() } }
        dummyTextures.add(texture)
        val dummy = Surface(texture)
        originalToDummy[original] = dummy
        dummyToOriginal[dummy] = original
        return dummy
    }

    private fun isReaderSurface(surface: Surface): Boolean = imageReaderSurfaces.contains(surface)

    fun install(loader: ClassLoader) {
        val readerClass = HookUtils.findClass("android.media.ImageReader", loader)
        val readerHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reader = param.result as? ImageReader ?: return
                runCatching { reader.surface }.getOrNull()?.let { imageReaderSurfaces.add(it) }
            }
        }
        if (readerClass != null) {
            HookUtils.hookAllNamed(
                readerClass, listOf("newInstance", "build"), readerHook, "Camera2.ImageReader"
            )

            val jpegHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cfg = HookConfig.snapshot()
                    if (!HookConfig.shouldReplace(cfg)) return
                    val reader = param.thisObject as? ImageReader ?: return
                    if (reader.imageFormat != ImageFormat.JPEG) return
                    val image = param.result as? Image ?: return
                    val context = HookConfig.context ?: return
                    val decoder = jpegDecoder ?: Nv21Decoder(context).also { jpegDecoder = it }
                    val source = HookConfig.activeSource(cfg)
                    decoder.configure(source, cfg)

                    val width = image.width
                    val height = image.height
                    val frame = decoder.frame(width, height)
                    val bytes = if (frame != null) {
                        encodeJpeg(frame, width, height)
                    } else {
                        encodeJpeg(ByteArray(width * height * 3 / 2) { 128.toByte() }, width, height)
                    }
                    if (bytes.isEmpty()) return
                    runCatching {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val count = minOf(bytes.size, buffer.capacity())
                        buffer.position(0)
                        buffer.limit(count)
                        buffer.put(bytes, 0, count)
                        buffer.position(0)
                    }
                }
            }
            HookUtils.hookAllNamed(
                readerClass,
                listOf("acquireLatestImage", "acquireNextImage"),
                jpegHook,
                "Camera2.jpeg"
            )
        }

        val deviceClass = HookUtils.findClass("android.hardware.camera2.impl.CameraDeviceImpl", loader)
            ?: HookUtils.findClass("android.hardware.camera2.CameraDevice", loader)
            ?: return

        val sessionHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cfg = HookConfig.snapshot()
                if (!HookConfig.shouldReplace(cfg)) return
                val args = param.args
                if (args.isEmpty()) return

                when (val outputs = args[0]) {
                    is List<*> -> {
                        val replaced = outputs.map { item ->
                            when (item) {
                                is Surface -> if (isReaderSurface(item)) item else dummyFor(item)
                                is OutputConfiguration -> {
                                    val surface = runCatching { item.surface }.getOrNull()
                                    if (surface != null && !isReaderSurface(surface)) {
                                        runCatching { OutputConfiguration(dummyFor(surface)) }.getOrNull()
                                            ?: item
                                    } else item
                                }
                                else -> item
                            }
                        }
                        args[0] = replaced
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val cfg = HookConfig.snapshot()
                if (!HookConfig.shouldReplace(cfg)) return
                val source = HookConfig.activeSource(cfg)
                val context = HookConfig.context ?: return
                val originals = dummyToOriginal.values.distinct().toList()
                originals.forEach { surface ->
                    if (!surface.isValid) return@forEach
                    val renderer = GLPreviewRenderer(context, surface)
                    renderer.start(source, cfg)
                    renderers.add(renderer)
                }
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
                builderClass,
                listOf("addTarget"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!HookConfig.shouldReplace(HookConfig.snapshot())) return
                        val surface = param.args.getOrNull(0) as? Surface ?: return
                        val dummy = originalToDummy[surface]
                        if (dummy != null) param.args[0] = dummy
                    }
                },
                "Camera2.addTarget"
            )
            HookUtils.hookAllNamed(
                builderClass,
                listOf("build"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = HookConfig.snapshot()
                        val source = HookConfig.activeSource(cfg)
                        renderers.forEach { it.updateConfig(source, cfg) }
                    }
                },
                "Camera2.build"
            )
        }

        val closeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                renderers.forEach { it.stop() }
                renderers.clear()
                dummyTextures.forEach { runCatching { it.release() } }
                dummyTextures.clear()
                originalToDummy.clear()
                dummyToOriginal.clear()
            }
        }
        HookUtils.hookAllNamed(deviceClass, listOf("close"), closeHook, "Camera2.close")

        val sessionClass = HookUtils.findClass(
            "android.hardware.camera2.CameraCaptureSession", loader
        )
        if (sessionClass != null) {
            HookUtils.hookAllNamed(sessionClass, listOf("close"), closeHook, "Camera2.sessionClose")
        }
    }

    private fun encodeJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray = try {
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        image.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
        out.toByteArray()
    } catch (_: Throwable) {
        ByteArray(0)
    }
}
