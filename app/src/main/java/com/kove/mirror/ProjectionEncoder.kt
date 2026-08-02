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
    val displayMode: DisplayMode = DisplayMode.CENTER_CROP,
    val phoneAspectRatio: Float = 0.45f,
    private val context: android.content.Context? = null
) {

    private var mediaCodec:       MediaCodec?       = null
    private var inputSurface:     Surface?          = null
    private var virtualDisplay:   VirtualDisplay?   = null
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

            val (vdWidth, vdHeight) = calculateVirtualDisplaySize()
            DebugLogger.info(R.string.log_vd_creating)
            DebugLogger.info("   VD Resolution    : ${vdWidth}×${vdHeight}")
            DebugLogger.info(R.string.log_codec_output_res, width, height)
            val vdFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "KoveMirror",
                vdWidth, vdHeight, dpi,
                vdFlags,
                surface,
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
                    Pair(width, height - (2 * padding))
                }
            }
            DisplayMode.FIT -> {
                Pair(width, height - (2 * padding))
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
        try { mediaCodec?.stop()    } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        try { inputSurface?.release()   } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection.stop()    } catch (_: Exception) {}
        mediaCodec     = null
        inputSurface   = null
        virtualDisplay = null
        DebugLogger.info(R.string.log_encoder_stopped)
    }

    fun updatePadding(newPadding: Int) {
        if (newPadding == padding) return
        padding = newPadding
        val surface = inputSurface ?: return
        val vdHeight = height - (2 * newPadding)
        DebugLogger.info(R.string.log_vd_recreating, newPadding, width, vdHeight)
        try {
            virtualDisplay?.release()
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "KoveMirror",
                width, vdHeight, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null, null
            )
        } catch (e: Exception) {
            DebugLogger.error(R.string.log_encoder_init_error, e.message ?: "")
        }
    }

    fun isStreaming() = streaming.get()
}
