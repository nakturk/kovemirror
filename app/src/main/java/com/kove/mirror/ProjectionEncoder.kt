package com.kove.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ThinkerRide ProjectionEncoder analizi:
 *
 * - MediaCodec H.264 encoder, Surface input modu
 * - Resolution: 480×800 (shape=1, isVerticalScreen())
 * - Bitrate: width * height * memoryFactor (biz 3 kullanıyoruz = ~1.1 Mbps)
 * - FPS: 30
 * - i-frame interval: 1 saniye
 * - repeat-previous-frame-after: 100000 µs (100ms)
 * - color-format: COLOR_FormatSurface (2130708361)
 * - bitrate-mode: CBR (1)
 *
 * Fark: Biz DisplayManager yerine MediaProjection kullanıyoruz
 *       → MediaProjection gerçek tam ekran yakalama sağlar
 *       → VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR ile tüm ekran gönderilir
 */
class ProjectionEncoder(
    private val mediaProjection: MediaProjection,
    val width:  Int = 480,
    val height: Int = 800,
    val dpi:    Int = 320,
    val fps:    Int = 30,
    var padding: Int = 0
) {

    private var mediaCodec:     MediaCodec?     = null
    private var inputSurface:   Surface?        = null
    private var virtualDisplay: VirtualDisplay? = null

    private val streaming    = AtomicBoolean(false)
    val frameCount           = AtomicLong(0)
    val encodedBytes         = AtomicLong(0)

    // ─── init ────────────────────────────────────────────────────

    /**
     * MediaCodec + VirtualDisplay başlatır.
     * Başarılıysa Surface döner; hata olursa null.
     */
    fun init(): Boolean {
        return try {
            val bitrate = width * height * 3  // ~1.1 Mbps for 480×800
            DebugLogger.info("🎬 Starting ProjectionEncoder...")
            DebugLogger.info("   Resolution : ${width}×${height}")
            DebugLogger.info("   FPS        : $fps")
            DebugLogger.info("   Bitrate    : ${bitrate / 1000} Kbps (CBR)")
            DebugLogger.info("   DPI        : $dpi")

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
            DebugLogger.success("✅ MediaCodec ready")

            val vdHeight = height - (2 * padding)
            DebugLogger.info("🖥️  Creating VirtualDisplay...")
            DebugLogger.info("   VD Resolution: ${width}×${vdHeight}")
            DebugLogger.info("   Surface Resolution: ${width}×${height}")
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "KoveMirror",
                width, vdHeight, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null, null
            )
            DebugLogger.success("✅ VirtualDisplay ready - capturing screen")
            true

        } catch (e: Exception) {
            DebugLogger.error("❌ Encoder init error: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // ─── encoding loop ───────────────────────────────────────────

    /**
     * H.264 NAL unit'lerini asenkron döngüde üretir, her frame'i [onData] callback'e verir.
     * TcpServer.writeData() buradan çağrılır.
     */
    fun startEncoding(onData: (ByteArray) -> Unit) {
        if (streaming.getAndSet(true)) return
        val codec = mediaCodec ?: run {
            DebugLogger.error("❌ Codec not initialized - was init() called?")
            return
        }

        Thread({
            DebugLogger.info("🎬 H.264 encoding loop started")
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
                                DebugLogger.info("🎬 EOS received - encoding finished")
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
                                        "🔑${keyFrames}IDR | Toplam:${kb}KB"
                                    )
                                    fpsCounter = 0
                                    keyFrames  = 0
                                    lastStatMs = now
                                }
                            } else {
                                codec.releaseOutputBuffer(idx, false)
                            }
                        }
                        // idx < 0: timeout veya INFO_TRY_AGAIN_LATER — normal
                    }
                } catch (e: Exception) {
                    if (streaming.get()) {
                        DebugLogger.error("❌ Encoding error: ${e.message}")
                    }
                    break
                }
            }
            DebugLogger.info("🎬 Encoding loop finished (${frameCount.get()} frame)")
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
        DebugLogger.info("🎬 ProjectionEncoder stopped")
    }

    fun updatePadding(newPadding: Int) {
        if (newPadding == padding) return
        padding = newPadding
        val surface = inputSurface ?: return
        val vdHeight = height - (2 * newPadding)
        DebugLogger.info("🔄 Re-creating VirtualDisplay... (New Padding: ${newPadding}px, VD Resolution: ${width}×${vdHeight})")
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
            DebugLogger.error("❌ VirtualDisplay re-create error: ${e.message}")
        }
    }

    fun isStreaming() = streaming.get()
}
