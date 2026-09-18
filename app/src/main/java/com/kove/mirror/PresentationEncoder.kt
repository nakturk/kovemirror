package com.kove.mirror

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated Hardware H.264 Encoder for KovePresentation / Secondary App Display.
 * Implements Thinkerride direct zero-copy VirtualDisplay architecture.
 * Directly pipes Presentation or target app draw surface into MediaCodec input surface.
 */
class PresentationEncoder(
    private val context: Context,
    val width: Int = 600,
    val height: Int = 1024,
    val dpi: Int = 320,
    val fps: Int = 30,
    val targetPackage: String? = null
) {

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: KovePresentation? = null
    private var encoderThread: Thread? = null

    private val streaming = AtomicBoolean(false)
    val frameCount = AtomicLong(0)
    val encodedBytes = AtomicLong(0)

    fun init(): Boolean {
        return try {
            val bitrate = width * height * 3 // ~1.8 Mbps for 600x1024
            DebugLogger.info("🎬 [PresentationEncoder] Initializing zero-copy encoder for ${width}x${height} (targetApp=$targetPackage)...")

            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, "video/avc")
                setInteger(MediaFormat.KEY_WIDTH, width)
                setInteger(MediaFormat.KEY_HEIGHT, height)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setLong("repeat-previous-frame-after", 100_000L) // 100ms repeating for static map frames
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }

                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)

                if (fps > 0 && Build.VERSION.SDK_INT >= 29) {
                    setFloat("max-fps-to-encoder", fps.toFloat())
                }
            }

            val codec = MediaCodec.createEncoderByType("video/avc")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            inputSurface = surface

            // Direct DisplayManager VirtualDisplay binding (Zero-Copy)
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vdFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            val vd = displayManager.createVirtualDisplay(
                "KovePresentationVD",
                width,
                height,
                dpi,
                surface,
                vdFlags
            )
            virtualDisplay = vd

            Handler(Looper.getMainLooper()).post {
                if (!targetPackage.isNullOrEmpty() && targetPackage != "internal_map") {
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        }
                        if (launchIntent != null) {
                            val options = ActivityOptions.makeBasic()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                options.launchDisplayId = vd.display.displayId
                            }
                            context.startActivity(launchIntent, options.toBundle())
                            DebugLogger.success("🚀 [PresentationEncoder] Launched $targetPackage on VirtualDisplay #${vd.display.displayId}")
                        } else {
                            DebugLogger.warning("⚠️ Target app ($targetPackage) launch intent not found, falling back to internal map")
                            showDefaultPresentation(vd.display)
                        }
                    } catch (e: Exception) {
                        DebugLogger.error("❌ Failed to launch $targetPackage on VirtualDisplay: ${e.message}")
                        showDefaultPresentation(vd.display)
                    }
                } else {
                    showDefaultPresentation(vd.display)
                }
            }

            DebugLogger.success("✅ [PresentationEncoder] VirtualDisplay and Encoder ready (${width}x${height})")
            true
        } catch (e: Exception) {
            DebugLogger.error("❌ [PresentationEncoder] Init failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun showDefaultPresentation(display: Display) {
        try {
            val p = KovePresentation(context, display)
            p.show()
            presentation = p
            DebugLogger.success("🏍️ [PresentationEncoder] Internal Map Presentation shown on VirtualDisplay")
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to show KovePresentation: ${e.message}")
        }
    }

    fun startEncoding(onData: (ByteArray) -> Unit) {
        if (streaming.getAndSet(true)) return
        val codec = mediaCodec ?: run {
            DebugLogger.error("❌ Codec not initialized in PresentationEncoder")
            return
        }

        encoderThread = Thread({
            DebugLogger.info("🎬 [PresentationEncoder] Encoding loop started")
            val bufInfo = MediaCodec.BufferInfo()
            var lastStatMs = System.currentTimeMillis()
            var fpsCounter = 0
            var keyFrames = 0

            while (streaming.get()) {
                try {
                    val idx = codec.dequeueOutputBuffer(bufInfo, 10_000L)

                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            DebugLogger.info("🎬 [PresentationEncoder] Format: ${codec.outputFormat}")
                        }
                        idx >= 0 -> {
                            val isEos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val isKeyFrame = (bufInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isEos) {
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

                                val now = System.currentTimeMillis()
                                if (now - lastStatMs >= 1000) {
                                    val kb = encodedBytes.get() / 1024
                                    DebugLogger.data(
                                        "📊 [VD Mode] ${fpsCounter}fps | ${data.size}B | 🔑${keyFrames}IDR | Total:${kb}KB"
                                    )
                                    fpsCounter = 0
                                    keyFrames = 0
                                    lastStatMs = now
                                }
                            } else {
                                codec.releaseOutputBuffer(idx, false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (streaming.get()) {
                        DebugLogger.error("❌ [PresentationEncoder] Encoding error: ${e.message}")
                    }
                    break
                }
            }
            DebugLogger.info("🎬 [PresentationEncoder] Encoding loop stopped")
        }, "KoveMirror-PresentationEncoder").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        streaming.set(false)
        try {
            encoderThread?.interrupt()
            encoderThread?.join(500L)
        } catch (_: Exception) {}
        encoderThread = null

        Handler(Looper.getMainLooper()).post {
            try {
                presentation?.dismiss()
            } catch (_: Exception) {}
            presentation = null
        }

        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}

        virtualDisplay = null
        mediaCodec = null
        inputSurface = null
        DebugLogger.info("🎬 [PresentationEncoder] Stopped and released")
    }

    fun isStreaming() = streaming.get()
}
