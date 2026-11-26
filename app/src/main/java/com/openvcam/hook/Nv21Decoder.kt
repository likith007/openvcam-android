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

    @Volatile private var rawFrame: ByteArray? = null
    @Volatile private var rawWidth = 0
    @Volatile private var rawHeight = 0

    @Volatile var rotation: Int = 0
    @Volatile var mirror: Boolean = false
    @Volatile var zoom: Float = 1f
    @Volatile var crop: String = "full"
    @Volatile var playing: Boolean = true

    @Volatile private var currentKey: String? = null

    fun configure(source: JSONObject?, config: JSONObject?) {
        val path = HookConfig.pathOf(source) ?: return release()
        val type = HookConfig.typeOf(source)
        rotation = source?.optInt("rotation", 0) ?: 0
        mirror = source?.optBoolean("mirror", false) ?: true
        zoom = (source?.optDouble("zoom", 1.0) ?: 1.0).toFloat().coerceIn(0.5f, 3f)
        crop = source?.optString("crop", "full") ?: "full"
        playing = config?.optBoolean("playing", true) ?: true

        val key = "$type|$path|$rotation|$mirror|$zoom|$crop"
        if (key == currentKey && running.get()) return
        release()
        currentKey = key

        if (type == "image") {
            loadImage(path)
        } else {
            startVideo(path)
        }
    }

    private fun loadImage(path: String) {
        try {
            val stream = context.contentResolver.openInputStream(HookConfig.mediaUri(path)) ?: return
            val bitmap = BitmapFactory.decodeStream(stream)
            stream.close()
            if (bitmap == null) return
            rawWidth = bitmap.width
            rawHeight = bitmap.height
            val argb = IntArray(rawWidth * rawHeight)
            bitmap.getPixels(argb, 0, rawWidth, 0, 0, rawWidth, rawHeight)
            rawFrame = argbToNv21(argb, rawWidth, rawHeight)
            bitmap.recycle()
        } catch (_: Throwable) {
        }
    }

    private fun startVideo(path: String) {
        try {
            val uri = HookConfig.mediaUri(path)
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return
            afd = descriptor
            val ex = MediaExtractor()
            ex.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            this.extractor = ex
            this.codec = codec

            running.set(true)
            thread = Thread({ decodeLoop(ex, codec) }, "openvcam-decoder").apply { start() }
        } catch (_: Throwable) {
            release()
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
                                        imageToNv21(image)?.let { frame ->
                                            rawFrame = frame
                                            rawWidth = image.width
                                            rawHeight = image.height
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

                if (!playing) Thread.sleep(40)
            } catch (t: Throwable) {
                if (!running.get()) break
            }
        }
    }

    /** Returns a frame scaled/rotated/mirrored to the requested preview size. */
    fun frame(width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val src = rawFrame ?: return null
        val sw = rawWidth
        val sh = rawHeight
        if (sw <= 0 || sh <= 0) return null
        if (sw == width && sh == height && rotation % 180 == 0 && !mirror &&
            zoom == 1f && crop == "full"
        ) return src
        return transformNv21(src, sw, sh, width, height, rotation, mirror, zoom, crop)
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
        rawFrame = null
        currentKey = null
    }

    private fun imageToNv21(image: Image): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null
        val w = image.width
        val h = image.height
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val out = ByteArray(w * h * 3 / 2)

        val cropLeft = image.cropRect.left
        val cropTop = image.cropRect.top
        val yStride = yPlane.rowStride
        val yPixel = yPlane.pixelStride
        val uStride = uPlane.rowStride
        val uPixel = uPlane.pixelStride
        val vStride = vPlane.rowStride
        val vPixel = vPlane.pixelStride
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        var index = 0
        for (row in 0 until h) {
            val base = (cropTop + row) * yStride + cropLeft * yPixel
            for (col in 0 until w) {
                out[index++] = yPlane.buffer.get(base + col * yPixel)
            }
        }
        for (row in 0 until h / 2) {
            val uBase = (cropTop / 2 + row) * uStride + (cropLeft / 2) * uPixel
            val vBase = (cropTop / 2 + row) * vStride + (cropLeft / 2) * vPixel
            for (col in 0 until w / 2) {
                out[index++] = vBuf.get(vBase + col * vPixel)
                out[index++] = uBuf.get(uBase + col * uPixel)
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
    }
}
