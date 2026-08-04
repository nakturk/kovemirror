package com.kove.mirror

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class DisplayMode {
    CENTER_CROP,
    FIT,
    STRETCH
}

class ProjectionEncoder(
    private val mediaProjection: MediaProjection,
    val width:  Int = 600,
    val height: Int = 1024,
    val dpi:    Int = 320,
    val fps:    Int = 30,
    var padding: Int = 0,
    var topPaddingPx: Int = 0,
    var bottomPaddingPx: Int = 0,
    val displayMode: DisplayMode = DisplayMode.CENTER_CROP,
    val phoneAspectRatio: Float = 0.45f,
    private val context: android.content.Context? = null
) {

    private var mediaCodec:       MediaCodec?         = null
    private var inputSurface:     Surface?            = null
    private var virtualDisplay:   VirtualDisplay?     = null
    private var oesRenderer:      OesTextureRenderer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val streaming    = AtomicBoolean(false)
    val frameCount           = AtomicLong(0)
    val encodedBytes         = AtomicLong(0)

    // ─── init ────────────────────────────────────────────────────

    fun init(): Boolean {
        return try {
            val bitrate = width * height * 3  // ~1.8 Mbps for 600×1024
            DebugLogger.info(R.string.log_encoder_starting)
            DebugLogger.info(R.string.log_codec_output_res, width, height)
            DebugLogger.info("   Display Mode     : ${displayMode.name}")
            DebugLogger.info("   Phone Aspect Ratio: %.3f".format(phoneAspectRatio))
            DebugLogger.info("   FPS              : $fps")
            DebugLogger.info("   Bitrate          : ${bitrate / 1000} Kbps (CBR)")
            DebugLogger.info("   DPI              : $dpi")
            DebugLogger.info("   Top/Bottom Padding: top=${topPaddingPx}px, bottom=${bottomPaddingPx}px")

            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, "video/avc")
                setInteger(MediaFormat.KEY_WIDTH,       width)
                setInteger(MediaFormat.KEY_HEIGHT,      height)
                setInteger(MediaFormat.KEY_BIT_RATE,    bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE,  fps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setLong("repeat-previous-frame-after", 100_000L)
                
                // VBR format key
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )

                // Prepend SPS/PPS headers before sync (key) frames for robustness
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }

                // AVC High Profile and Level 4.1 (matching original app)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)

                if (fps > 0 && Build.VERSION.SDK_INT >= 29) {
                    setFloat("max-fps-to-encoder", fps.toFloat())
                }
            }

            DebugLogger.info("🎬 Creating MediaCodec H.264 encoder...")
            val codec = MediaCodec.createEncoderByType("video/avc")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            mediaCodec   = codec
            inputSurface = surface
            DebugLogger.success(R.string.log_codec_ready)

            // Setup OES GL renderer for letterboxing / padding
            val renderer = OesTextureRenderer(
                codecInputSurface = surface,
                width = width,
                height = height,
                topPaddingPx = topPaddingPx,
                bottomPaddingPx = bottomPaddingPx
            )
            if (!renderer.start()) {
                DebugLogger.error("❌ Failed to start OesTextureRenderer")
                return false
            }
            oesRenderer = renderer

            val vdSurface = renderer.vdInputSurface ?: surface
            val (vdWidth, vdHeight) = calculateVirtualDisplaySize()
            DebugLogger.info(R.string.log_vd_creating)
            DebugLogger.info("   VD Resolution    : ${vdWidth}×${vdHeight}")
            DebugLogger.info(R.string.log_codec_output_res, width, height)
            val vdFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "KoveMirror",
                vdWidth, vdHeight, dpi,
                vdFlags,
                vdSurface,
                null, null
            )
            DebugLogger.success(R.string.log_vd_ready, displayMode.name)
            true

        } catch (e: Exception) {
            DebugLogger.error(R.string.log_encoder_init_error, e.message ?: "")
            e.printStackTrace()
            false
        }
    }

    private fun calculateVirtualDisplaySize(): Pair<Int, Int> {
        return when (displayMode) {
            DisplayMode.CENTER_CROP -> {
                if (phoneAspectRatio > 0f) {
                    if (height >= width) {
                        // Vertical TFT (e.g. 600×1024, 480×800)
                        val calculatedVdHeight = (width / phoneAspectRatio).toInt()
                        Pair(width, calculatedVdHeight.coerceAtLeast(height))
                    } else {
                        // Horizontal TFT (e.g. 1280×720)
                        val calculatedVdWidth = (height * phoneAspectRatio).toInt()
                        Pair(calculatedVdWidth.coerceAtLeast(width), height)
                    }
                } else {
                    Pair(width, height)
                }
            }
            DisplayMode.FIT -> {
                Pair(width, height)
            }
            DisplayMode.STRETCH -> {
                Pair(width, height)
            }
        }
    }

    // ─── encoding loop ───────────────────────────────────────────

    fun startEncoding(onData: (ByteArray) -> Unit) {
        if (streaming.getAndSet(true)) return
        val codec = mediaCodec ?: run {
            DebugLogger.error(R.string.log_codec_not_init)
            return
        }

        Thread({
            DebugLogger.info(R.string.log_encoding_loop_started)
            val bufInfo     = MediaCodec.BufferInfo()
            var lastStatMs  = System.currentTimeMillis()
            var fpsCounter  = 0
            var keyFrames   = 0

            while (streaming.get()) {
                try {
                    val idx = codec.dequeueOutputBuffer(bufInfo, 10_000L)

                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            DebugLogger.info("🎬 Codec format: ${codec.outputFormat}")
                        }
                        idx >= 0 -> {
                            val isEos      = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val isKeyFrame = (bufInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isEos) {
                                DebugLogger.info(R.string.log_eos_received)
                                codec.releaseOutputBuffer(idx, false)
                                break
                            }

                            val outBuf = codec.getOutputBuffer(idx)
                            if (outBuf != null && bufInfo.size > 0) {
                                val data = ByteArray(bufInfo.size)
                                outBuf.get(data)
                                codec.releaseOutputBuffer(idx, false)

                                onData(data)
                                frameCount.incrementAndGet()
                                encodedBytes.addAndGet(data.size.toLong())
                                fpsCounter++
                                if (isKeyFrame) keyFrames++

                                // Her saniyede bir istatistik log
                                val now = System.currentTimeMillis()
                                if (now - lastStatMs >= 1000) {
                                    val kb = encodedBytes.get() / 1024
                                    DebugLogger.data(
                                        "📊 ${fpsCounter}fps | ${data.size}B/frame | " +
                                        "🔑${keyFrames}IDR | Total:${kb}KB"
                                    )
                                    fpsCounter = 0
                                    keyFrames  = 0
                                    lastStatMs = now
                                }
                            } else {
                                codec.releaseOutputBuffer(idx, false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (streaming.get()) {
                        DebugLogger.error(R.string.log_encoding_error, e.message ?: "")
                    }
                    break
                }
            }
            DebugLogger.info(R.string.log_encoding_loop_finished, frameCount.get())
        }, "KoveMirror-Encoder").also {
            it.isDaemon = true
            it.start()
        }
    }

    // ─── cleanup ─────────────────────────────────────────────────

    fun stop() {
        streaming.set(false)
        try { oesRenderer?.release() } catch (_: Exception) {}
        try { mediaCodec?.stop()    } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        try { inputSurface?.release()   } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection.stop()    } catch (_: Exception) {}
        oesRenderer    = null
        mediaCodec     = null
        inputSurface   = null
        virtualDisplay = null
        DebugLogger.info(R.string.log_encoder_stopped)
    }

    fun updatePadding(newTopPx: Int, newBottomPx: Int) {
        topPaddingPx = newTopPx
        bottomPaddingPx = newBottomPx
        oesRenderer?.topPaddingPx = newTopPx
        oesRenderer?.bottomPaddingPx = newBottomPx
        DebugLogger.info("🔄 Margin dynamically updated: top=${newTopPx}px, bottom=${newBottomPx}px")
    }

    fun updatePadding(newPadding: Int) {
        updatePadding(newPadding, newPadding)
    }

    fun isStreaming() = streaming.get()
}

// ─── OpenGL ES Renderer for Top/Bottom Black Bar Viewport ────

private class OesTextureRenderer(
    private val codecInputSurface: Surface,
    private val width: Int,
    private val height: Int,
    @Volatile var topPaddingPx: Int,
    @Volatile var bottomPaddingPx: Int
) {
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    var vdInputSurface: Surface? = null
        private set

    private var program: Int = 0
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var uSTMatrixLoc: Int = 0
    private var uTextureLoc: Int = 0

    private val stMatrix = FloatArray(16)
    private lateinit var vertexBuffer: FloatBuffer

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        uniform mat4 uSTMatrix;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private val quadData = floatArrayOf(
        // X,     Y,   U,   V
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    )

    fun start(): Boolean {
        val thread = HandlerThread("OesTextureRendererThread").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        val initLatch = java.util.concurrent.CountDownLatch(1)
        var initSuccess = false

        handler.post {
            try {
                initEgl()
                initGl()
                initSurfaceTexture()
                initSuccess = true
            } catch (e: Exception) {
                DebugLogger.error("GL init failed: ${e.message}")
            } finally {
                initLatch.countDown()
            }
        }

        initLatch.await()
        return initSuccess
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }

        val eglConfig = configs[0]
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, codecInputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun initGl() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uTextureLoc  = GLES20.glGetUniformLocation(program, "sTexture")

        vertexBuffer = ByteBuffer.allocateDirect(quadData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadData)
                position(0)
            }

        val texArray = IntArray(1)
        GLES20.glGenTextures(1, texArray, 0)
        textureId = texArray[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun initSurfaceTexture() {
        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(width, height)
        val handler = glHandler!!
        st.setOnFrameAvailableListener({
            handler.post { drawFrame() }
        }, handler)
        surfaceTexture = st
        vdInputSurface = Surface(st)
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
        }
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            // 1. Clear full canvas to black
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 2. Set viewport for middle content area
            val topPx = topPaddingPx.coerceIn(0, (height - 10) / 2)
            val bottomPx = bottomPaddingPx.coerceIn(0, (height - 10) / 2)
            val activeHeight = (height - topPx - bottomPx).coerceAtLeast(1)
            val activeBottom = bottomPx

            GLES20.glViewport(0, activeBottom, width, activeHeight)

            // 3. Draw quad
            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)

            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTextureLoc, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        } catch (_: Exception) {
        }
    }

    fun release() {
        val handler = glHandler
        if (handler != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                try {
                    surfaceTexture?.release()
                    vdInputSurface?.release()
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                        EGL14.eglTerminate(eglDisplay)
                    }
                } catch (_: Exception) {}
                finally {
                    latch.countDown()
                }
            }
            try { latch.await() } catch (_: Exception) {}
            glThread?.quitSafely()
        }
    }
}

