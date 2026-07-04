package com.kove.mirror

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ThinkerRide kaynak analizi:
 *   WifiMessageHead: [type=0x02, subtype=0x01, length(4 byte)=0x00000000]
 * Toplam 6 byte, her 2 saniyede bir gönderilir.
 * Bağlantıyı canlı tutar (keep-alive).
 */
class HeartbeatManager(private val outputStream: OutputStream) {

    private val running = AtomicBoolean(false)
    private val count   = AtomicLong(0)
    private var thread: Thread? = null

    // [type=0x02][subtype=0x01][len=0x00000000]
    private val HEARTBEAT_PACKET = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread {
            DebugLogger.info("💓 HeartBeat thread başlatıldı (2s aralık)")
            while (running.get()) {
                try {
                    outputStream.write(HEARTBEAT_PACKET)
                    outputStream.flush()
                    val n = count.incrementAndGet()
                    DebugLogger.heartbeat("💓 Heartbeat #$n → ${HEARTBEAT_PACKET.toHex()}")
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: IOException) {
                    DebugLogger.error("❌ Heartbeat IO hatası: ${e.message}")
                    break
                }
            }
            DebugLogger.info("💓 HeartBeat thread durdu (toplam: ${count.get()})")
        }.also {
            it.name = "KoveMirror-Heartbeat"
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    fun getCount() = count.get()

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
}
