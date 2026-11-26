package com.openvcam.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import com.openvcam.core.CropPresets
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

/**
 * Produces a cached NV21 frame from the configured image or video source.
 * The decoder runs on its own thread so camera callbacks never block on IO.
 */
class Nv21Decoder(private val context: Context) {

    @Volatile private var extractor: MediaExtractor? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var afd: android.content.res.AssetFileDescriptor? = null
    @Volatile private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    private data class Frame(val bytes: ByteArray, val width: Int, val height: Int)

    @Volatile private var frame: Frame? = null
    @Volatile private var frameVersion = 0L

    @Volatile var rotation: Int = 0
    @Volatile var mirror: Boolean = false
    @Volatile var zoom: Float = 1f
    @Volatile var crop: String = "full"
    @Volatile var playing: Boolean = true
    @Volatile var speed: Float = 1f

    @Volatile private var currentKey: String? = null
    @Volatile private var failedKey: String? = null
    @Volatile private var failedAt = 0L
    @Volatile private var lastTransformKey: String? = null
    @Volatile private var lastTransform: ByteArray? = null
    private val transformLock = Any()

    fun configure(source: JSONObject?, config: JSONObject?) {
        val path = HookConfig.pathOf(source) ?: return release()
        val type = HookConfig.typeOf(source)
        rotation = source?.optInt("rotation", 0) ?: 0
        mirror = source?.optBoolean("mirror", false) ?: false
        zoom = (source?.optDouble("zoom", 1.0) ?: 1.0).toFloat().coerceIn(0.5f, 3f)
        crop = source?.optString("crop", "full") ?: "full"
        playing = config?.optBoolean("playing", true) ?: true
        speed = (config?.optDouble("speed", 1.0) ?: 1.0).toFloat().coerceIn(0.25f, 4f)

        // Only the media identity needs a decoder restart. Rotation, mirror, crop and zoom
        // are applied in frame(), so calling configure on every preview frame is cheap.
        val key = "$type|$path"
        if (key == currentKey) return
        if (key == failedKey && System.currentTimeMillis() - failedAt < 1_000L) return
        release()

        val loaded = if (type == "image") loadImage(path) else startVideo(path)
        if (loaded) {
            currentKey = key
            failedKey = null
        } else {
            failedKey = key
            failedAt = System.currentTimeMillis()
        }
    }

    private fun loadImage(path: String): Boolean {
        return try {
            val stream = context.contentResolver.openInputStream(HookConfig.mediaUri(path))
                ?: return false
            val bitmap = stream.use { BitmapFactory.decodeStream(it) } ?: return false
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) {
                bitmap.recycle()
                return false
            }
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            publish(argbToNv21(argb, w, h), w, h)
            bitmap.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun startVideo(path: String): Boolean {
        var descriptor: android.content.res.AssetFileDescriptor? = null
        var ex: MediaExtractor? = null
        var decoder: MediaCodec? = null
        return try {
            descriptor = context.contentResolver.openAssetFileDescriptor(
                HookConfig.mediaUri(path), "r"
            ) ?: return false
            val extractor = MediaExtractor()
            ex = extractor
            extractor.setDataSource(
                descriptor.fileDescriptor, descriptor.startOffset, descriptor.length
            )
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            afd = descriptor
            this.extractor = extractor
            this.codec = codec
            running.set(true)
            thread = Thread({ decodeLoop(extractor, codec) }, "openvcam-decoder").apply { start() }
            true
        } catch (_: Throwable) {
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { ex?.release() }
            runCatching { descriptor?.close() }
            false
        }
    }

    private fun publish(bytes: ByteArray, width: Int, height: Int) {
        frame = Frame(bytes, width, height)
        frameVersion++
        synchronized(transformLock) {
            lastTransformKey = null
            lastTransform = null
        }
    }

    private fun decodeLoop(ex: MediaExtractor, codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var sawEof = false
        while (running.get()) {
            try {
                if (!sawEof) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: break
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawEof = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { }
                    else -> {
                        if (outputIndex >= 0) {
                            if (bufferInfo.size > 0) {
                                runCatching {
                                    val image: Image? = codec.getOutputImage(outputIndex)
                                    if (image != null) {
                                        imageToNv21(image)?.let { bytes ->
                                            publish(bytes, image.width, image.height)
                                        }
                                        image.close()
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEof = false
                                runCatching {
                                    ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                    codec.flush()
                                }
                            }
                        }
                    }
                }

                val pauseMs = if (!playing) 60L
                else ((1f / speed.coerceAtLeast(0.25f) - 1f) * 16f).toLong().coerceAtLeast(0)
                if (pauseMs > 0) Thread.sleep(pauseMs)
            } catch (t: Throwable) {
                if (!running.get()) break
            }
        }
    }

    /** Returns a frame scaled/rotated/mirrored to the requested preview size. */
    fun frame(width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val current = frame ?: return null
        val src = current.bytes
        val sw = current.width
        val sh = current.height
        if (sw <= 0 || sh <= 0) return null

        val key = "$frameVersion|$sw|$sh|$width|$height|$rotation|$mirror|$zoom|$crop"
        synchronized(transformLock) {
            if (key == lastTransformKey) lastTransform?.let { return it }
        }

        val out = if (sw == width && sh == height && rotation % 180 == 0 && !mirror &&
            zoom == 1f && crop == "full"
        ) src
        else transformNv21(src, sw, sh, width, height, rotation, mirror, zoom, crop)

        synchronized(transformLock) {
            lastTransformKey = key
            lastTransform = out
        }
        return out
    }

    fun release() {
        running.set(false)
        thread?.interrupt()
        thread = null
        runCatching { codec?.stop(); codec?.release() }
        runCatching { extractor?.release() }
        runCatching { afd?.close() }
        codec = null
        extractor = null
        afd = null
        frame = null
        currentKey = null
        synchronized(transformLock) {
            lastTransformKey = null
            lastTransform = null
        }
    }

    private fun imageToNv21(image: Image): ByteArray? {
        val planes = image.planes
        if (planes.size < 2) return null
        val w = image.width
        val h = image.height
        val yPlane = planes[0]
        val out = ByteArray(w * h * 3 / 2)
        val left = image.cropRect.left
        val top = image.cropRect.top

        var index = 0
        for (row in 0 until h) {
            val base = (top + row) * yPlane.rowStride + left * yPlane.pixelStride
            for (col in 0 until w) {
                out[index++] = yPlane.buffer.get(base + col * yPlane.pixelStride)
            }
        }

        if (planes.size >= 3) {
            // Planar / flexible YUV: planes[1] = U, planes[2] = V.
            val u = planes[1]
            val v = planes[2]
            for (row in 0 until h / 2) {
                val uBase = (top / 2 + row) * u.rowStride + (left / 2) * u.pixelStride
                val vBase = (top / 2 + row) * v.rowStride + (left / 2) * v.pixelStride
                for (col in 0 until w / 2) {
                    out[index++] = v.buffer.get(vBase + col * v.pixelStride)
                    out[index++] = u.buffer.get(uBase + col * u.pixelStride)
                }
            }
        } else {
            // Semi-planar YUV420 (NV12: interleaved U,V) -> NV21 (V,U).
            val uv = planes[1]
            val uvBase = (top / 2) * uv.rowStride + (left / 2) * uv.pixelStride
            for (row in 0 until h / 2) {
                var uvIndex = uvBase + row * uv.rowStride
                for (col in 0 until w / 2) {
                    out[index++] = uv.buffer.get(uvIndex + uv.pixelStride) // V
                    out[index++] = uv.buffer.get(uvIndex)                 // U
                    uvIndex += uv.pixelStride * 2
                }
            }
        }
        return out
    }

    private fun argbToNv21(argb: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[i++] = ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16) and 0xFF).toByte()
            }
        }
        var index = w * h
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val c = argb[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                out[index++] = v.toByte()
                out[index++] = u.toByte()
            }
        }
        return out
    }

    private fun transformNv21(
        src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int,
        rotationDeg: Int, mirror: Boolean, zoom: Float, crop: String
    ): ByteArray {
        val out = ByteArray(dw * dh * 3 / 2)
        val rad = Math.toRadians((-rotationDeg).toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()
        val chromaBase = sw * sh
        val (cropX, cropY) = CropPresets.factors(sw, sh, crop, rotationDeg)
        val zoomX = zoom * cropX
        val zoomY = zoom * cropY

        for (y in 0 until dh) {
            for (x in 0 until dw) {
                var u = (x + 0.5f) / dw - 0.5f
                var v = (y + 0.5f) / dh - 0.5f
                u /= zoomX
                v /= zoomY
                val ru = u * cosR - v * sinR
                val rv = u * sinR + v * cosR
                val mu = if (mirror) -ru else ru
                val sx = ((mu + 0.5f) * sw).toInt().coerceIn(0, sw - 1)
                val sy = ((rv + 0.5f) * sh).toInt().coerceIn(0, sh - 1)

                out[y * dw + x] = src[sy * sw + sx]
                if (x % 2 == 0 && y % 2 == 0) {
                    val ci = chromaBase + (sy / 2) * sw + (sx / 2) * 2
                    val oi = dw * dh + (y / 2) * dw + x
                    if (ci + 1 < src.size && oi + 1 < out.size) {
                        out[oi] = src[ci]
                        out[oi + 1] = src[ci + 1]
                    }
                }
            }
        }
        return out
    }

    fun updateConfig(config: JSONObject?) {
        playing = config?.optBoolean("playing", true) ?: true
        speed = (config?.optDouble("speed", 1.0) ?: 1.0).toFloat().coerceIn(0.25f, 4f)
    }
}
