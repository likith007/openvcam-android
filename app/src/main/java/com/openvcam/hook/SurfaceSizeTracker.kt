package com.openvcam.hook

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.view.Surface
import android.view.SurfaceHolder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

data class SurfaceSize(val width: Int, val height: Int) {
    val valid: Boolean get() = width > 0 && height > 0
}

/**
 * Learns the size a target app expects from the surfaces it gives to the camera.
 *
 * Camera2 derives a stream's size from the BufferQueue behind a Surface. A dummy
 * SurfaceTexture left at 1x1 can make camera sessions fail or produce a black
 * preview. We therefore remember the size apps set on SurfaceTextures, the size
 * of SurfaceView holder surfaces, and the size of MediaCodec/MediaRecorder input
 * surfaces before any of those Surfaces are handed to the camera.
 */
object SurfaceSizeTracker {

    private val textureSizes =
        Collections.synchronizedMap(IdentityHashMap<SurfaceTexture, SurfaceSize>())
    private val surfaceTextures =
        Collections.synchronizedMap(WeakHashMap<Surface, SurfaceTexture>())
    private val holderBySurface =
        Collections.synchronizedMap(WeakHashMap<Surface, SurfaceHolder>())
    private val sizes =
        Collections.synchronizedMap(WeakHashMap<Surface, SurfaceSize>())
    private val recorderSizes =
        Collections.synchronizedMap(WeakHashMap<MediaRecorder, SurfaceSize>())

    fun sizeOf(texture: SurfaceTexture): SurfaceSize? = textureSizes[texture]?.takeIf { it.valid }

    fun sizeOf(holder: SurfaceHolder): SurfaceSize? = try {
        val rect = holder.surfaceFrame
        SurfaceSize(rect.width(), rect.height()).takeIf { it.valid }
    } catch (_: Throwable) {
        null
    }

    fun sizeOf(surface: Surface): SurfaceSize? {
        sizes[surface]?.takeIf { it.valid }?.let { return it }
        surfaceTextures[surface]?.let { textureSizes[it]?.takeIf { it.valid }?.let { size -> return size } }
        holderBySurface[surface]?.let { sizeOf(it)?.let { size -> return size } }
        return null
    }

    /** Resizes a replacement texture to what the app expects, with a safe fallback. */
    fun applyTo(texture: SurfaceTexture, original: Surface): SurfaceSize {
        val size = sizeOf(original) ?: DEFAULT_SIZE
        runCatching { texture.setDefaultBufferSize(size.width, size.height) }
        return size
    }

    fun applyTo(texture: SurfaceTexture, original: SurfaceTexture): SurfaceSize {
        val size = sizeOf(original) ?: DEFAULT_SIZE
        runCatching { texture.setDefaultBufferSize(size.width, size.height) }
        return size
    }

    fun applyTo(texture: SurfaceTexture, holder: SurfaceHolder): SurfaceSize {
        val size = sizeOf(holder) ?: DEFAULT_SIZE
        runCatching { texture.setDefaultBufferSize(size.width, size.height) }
        return size
    }

    fun install(loader: ClassLoader) {
        val textureClass = HookUtils.findClass("android.graphics.SurfaceTexture", loader)
        if (textureClass != null) {
            HookUtils.hookAllNamed(
                textureClass,
                listOf("setDefaultBufferSize"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val texture = param.thisObject as? SurfaceTexture ?: return
                        val width = param.args.getOrNull(0) as? Int ?: return
                        val height = param.args.getOrNull(1) as? Int ?: return
                        if (width > 0 && height > 0) {
                            textureSizes[texture] = SurfaceSize(width, height)
                        }
                    }
                },
                "Surface.textureSize"
            )
        }

        val surfaceClass = HookUtils.findClass("android.view.Surface", loader)
        if (surfaceClass != null) {
            surfaceClass.declaredConstructors
                .filter { it.parameterTypes.firstOrNull() == SurfaceTexture::class.java }
                .forEach { constructor ->
                    HookUtils.hook(
                        constructor,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val surface = (param.thisObject as? Surface)
                                    ?: param.result as? Surface ?: return
                                val texture = param.args.getOrNull(0) as? SurfaceTexture ?: return
                                surfaceTextures[surface] = texture
                                sizeOf(texture)?.let { sizes[surface] = it }
                            }
                        },
                        "Surface.constructor"
                    )
                }
        }

        val holderClass = HookUtils.findClass("android.view.SurfaceView\$SurfaceViewHolder", loader)
        if (holderClass != null) {
            HookUtils.hookAllNamed(
                holderClass,
                listOf("getSurface"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val surface = param.result as? Surface ?: return
                        val holder = param.thisObject as? SurfaceHolder ?: return
                        holderBySurface[surface] = holder
                        sizeOf(holder)?.let { sizes[surface] = it }
                    }
                },
                "Surface.holder"
            )
        }

        val codecClass = HookUtils.findClass("android.media.MediaCodec", loader)
        if (codecClass != null) {
            HookUtils.hookAllNamed(
                codecClass,
                listOf("configure"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val surface = param.args.firstOrNull { it is Surface } as? Surface ?: return
                        val format = param.args.firstOrNull { it is MediaFormat } as? MediaFormat
                            ?: return
                        val width = format.getIntegerSafe(MediaFormat.KEY_WIDTH)
                        val height = format.getIntegerSafe(MediaFormat.KEY_HEIGHT)
                        if (width > 0 && height > 0) {
                            sizes[surface] = SurfaceSize(width, height)
                            HookLog.once(
                                "size.codec",
                                "tracked MediaCodec surface ${width}x${height}"
                            )
                        }
                    }
                },
                "Surface.codec"
            )
        }

        val recorderClass = HookUtils.findClass("android.media.MediaRecorder", loader)
        if (recorderClass != null) {
            HookUtils.hookAllNamed(
                recorderClass,
                listOf("setVideoSize"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val width = param.args.getOrNull(0) as? Int ?: return
                        val height = param.args.getOrNull(1) as? Int ?: return
                        val recorder = param.thisObject as? MediaRecorder ?: return
                        recorderSizes[recorder] = SurfaceSize(width, height)
                    }
                },
                "Surface.recorderSize"
            )
            HookUtils.hookAllNamed(
                recorderClass,
                listOf("getSurface"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val surface = param.result as? Surface ?: return
                        val recorder = param.thisObject as? MediaRecorder ?: return
                        recorderSizes[recorder]?.takeIf { it.valid }?.let { sizes[surface] = it }
                    }
                },
                "Surface.recorder"
            )
        }
    }

    private fun MediaFormat.getIntegerSafe(key: String): Int = try {
        if (containsKey(key)) getInteger(key) else 0
    } catch (_: Throwable) {
        0
    }

    private val DEFAULT_SIZE = SurfaceSize(1280, 720)
}
