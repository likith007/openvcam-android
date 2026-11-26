package com.openvcam.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Renders the configured media into a Surface owned by a hooked app.
 * Video is decoded by MediaPlayer into an external OES texture; images use a 2D texture.
 */
class GLPreviewRenderer(private val context: Context, private val surface: Surface) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val frameLock = Object()

    private var display = EGL14.EGL_NO_DISPLAY
    private var contextEgl = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var programOes = 0
    private var program2d = 0
    private var textureOes = 0
    private var texture2d = 0
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var player: MediaPlayer? = null
    private var videoMode = false
    private var frameAvailable = false

    @Volatile private var rotation = 0
    @Volatile private var mirror = false
    @Volatile private var zoom = 1f
    @Volatile private var speed = 1f
    @Volatile private var playing = true
    @Volatile private var lastKey = ""

    private val positions = floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    )
    private val texCoords = floatArrayOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
    )

    fun start(source: JSONObject?, config: JSONObject?) {
        val path = HookConfig.pathOf(source) ?: return
        val type = HookConfig.typeOf(source)
        if (type != "video" && type != "image") return
        val key = "$type|$path"
        if (key == lastKey && running.get()) {
            updateConfig(source, config)
            return
        }
        stop()
        lastKey = key
        videoMode = type == "video"
        applyConfig(source, config)
        running.set(true)
        thread = Thread({ renderLoop(path) }, "openvcam-render").apply { start() }
    }

    fun updateConfig(source: JSONObject?, config: JSONObject?) {
        applyConfig(source, config)
        val player = player ?: return
        try {
            val p = android.media.PlaybackParams().setSpeed(speed)
            player.playbackParams = p
            if (playing && !player.isPlaying) player.start()
            if (!playing && player.isPlaying) player.pause()
        } catch (_: Throwable) {
        }
    }

    private fun applyConfig(source: JSONObject?, config: JSONObject?) {
        rotation = source?.optInt("rotation", 0) ?: 0
        mirror = source?.optBoolean("mirror", false) ?: false
        zoom = (source?.optDouble("zoom", 1.0) ?: 1.0).toFloat().coerceIn(0.5f, 3f)
        speed = (config?.optDouble("speed", 1.0) ?: 1.0).toFloat().coerceIn(0.25f, 4f)
        playing = config?.optBoolean("playing", true) ?: true
    }

    private fun renderLoop(path: String) {
        try {
            setupEgl()
            setupTextures(path)
            val stMatrix = FloatArray(16)
            val ourMatrix = FloatArray(16)
            val combined = FloatArray(16)

            while (running.get()) {
                if (!surface.isValid) break
                if (videoMode) {
                    synchronized(frameLock) {
                        if (!frameAvailable) try { frameLock.wait(100) } catch (_: InterruptedException) { }
                    }
                } else {
                    Thread.sleep(80)
                }
                if (!running.get()) break

                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, contextEgl)
                val dims = IntArray(2)
                EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, dims, 0)
                EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, dims, 1)
                GLES20.glViewport(0, 0, dims[0].coerceAtLeast(1), dims[1].coerceAtLeast(1))
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                buildTransform(ourMatrix)
                if (videoMode) {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                    Matrix.multiplyMM(combined, 0, ourMatrix, 0, stMatrix, 0)
                    draw(programOes, textureOes, combined, flipV = false)
                } else {
                    Matrix.setIdentityM(combined, 0)
                    draw(program2d, texture2d, combined, flipV = true)
                }

                EGL14.eglSwapBuffers(display, eglSurface)
                synchronized(frameLock) { frameAvailable = false }
            }
        } catch (_: Throwable) {
        } finally {
            cleanupGl()
        }
    }

    private fun buildTransform(out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, 0.5f, 0.5f, 0f)
        if (mirror) Matrix.scaleM(out, 0, -1f, 1f, 1f)
        Matrix.rotateM(out, 0, -rotation.toFloat(), 0f, 0f, 1f)
        val z = 1f / zoom.coerceIn(0.5f, 3f)
        Matrix.scaleM(out, 0, z, z, 1f)
        Matrix.translateM(out, 0, -0.5f, -0.5f, 0f)
    }

    private fun draw(program: Int, texture: Int, matrix: FloatArray, flipV: Boolean) {
        if (program == 0) return
        GLES20.glUseProgram(program)
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, positions.toFloatBuffer())

        val coords = if (flipV) floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f) else texCoords
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, coords.toFloatBuffer())

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(
            if (program == programOes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D,
            texture
        )
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        val config = configs[0] ?: throw IllegalStateException("no EGL config")
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        contextEgl = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, contextEgl)

        programOes = buildProgram(VERTEX_SHADER, FRAGMENT_OES)
        program2d = buildProgram(VERTEX_SHADER, FRAGMENT_2D)
        textureOes = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        texture2d = createTexture(GLES20.GL_TEXTURE_2D)
    }

    private fun setupTextures(path: String) {
        if (videoMode) {
            val st = android.graphics.SurfaceTexture(textureOes, true)
            st.setOnFrameAvailableListener {
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            }
            surfaceTexture = st
            val mp = MediaPlayer()
            mp.setDataSource(context, HookConfig.mediaUri(path))
            mp.setSurface(Surface(st))
            mp.isLooping = true
            try {
                mp.playbackParams = android.media.PlaybackParams().setSpeed(speed)
            } catch (_: Throwable) {
            }
            if (!playing) mp.pause() else mp.start()
            player = mp
        } else {
            val stream = context.contentResolver.openInputStream(HookConfig.mediaUri(path)) ?: return
            val bitmap: Bitmap? = BitmapFactory.decodeStream(stream)
            stream.close()
            if (bitmap != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture2d)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                bitmap.recycle()
            }
        }
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun cleanupGl() {
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, contextEgl)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        }
        display = EGL14.EGL_NO_DISPLAY
        contextEgl = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        synchronized(frameLock) { frameLock.notifyAll() }
        val t = thread
        try { t?.join(1500) } catch (_: InterruptedException) { }
        thread = null
        lastKey = ""
    }

    private fun FloatArray.toFloatBuffer(): java.nio.FloatBuffer {
        val buffer = java.nio.ByteBuffer.allocateDirect(size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(this)
        buffer.position(0)
        return buffer
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """
        private const val FRAGMENT_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
        """
        private const val FRAGMENT_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
        """
    }
}
