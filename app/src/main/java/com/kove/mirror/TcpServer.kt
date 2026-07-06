package com.kove.mirror

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ThinkerRide kaynak analizi ve test logları doğrultusunda Port Rolleri:
 *
 * 1. Port 17818 (Control): TFT bağlanıp MAC adresi, versiyon ve seri numarası (TUC) gönderir.
 *    - Telefon control mesajlarını okur ve her 2 saniyede bir heartbeat gönderir.
 *
 * 2. Port 15456 (Video / Projection): TFT bağlanır.
 *    - Telefon önce 69-byte VideoSize header gönderir.
 *    - Ardından ham H.264 NAL unit stream gönderir.
 *    - Ayrıca her 2 saniyede bir heartbeat gönderir.
 *
 * 3. Port 15457 (Heartbeat): TFT bağlanır.
 *    - Telefon her 200ms'de bir 6-byte heartbeat paketi gönderir (keep-alive).
 */
class TcpServer(
    private val hostIp: String?,
    private val width: Int,
    private val height: Int,
    private val onConnected:    (OutputStream) -> Unit,
    private val onDisconnected: () -> Unit
) {

    companion object {
        const val PORT_VIDEO = 15456
        const val PORT_CONTROL = 17818
        const val PORT_HEARTBEAT = 15457
        const val ACCEPT_TIMEOUT_MS = 5000
    }

    // ServerSockets
    private var videoServerSocket: ServerSocket? = null
    private var controlServerSocket: ServerSocket? = null
    private var heartbeatServerSocket: ServerSocket? = null

    // ClientSockets
    private var videoClientSocket: Socket? = null
    private var controlClientSocket: Socket? = null
    private var heartbeatClientSocket: Socket? = null

    // OutputStreams
    private var videoOutputStream: OutputStream? = null
    private var controlOutputStream: OutputStream? = null

    private val running    = AtomicBoolean(false)
    private val connected  = AtomicBoolean(false)
    val bytesSent          = AtomicLong(0)

    // Threads
    private var videoServerThread: Thread? = null
    private var controlServerThread: Thread? = null
    private var heartbeatServerThread: Thread? = null

    private var videoReaderThread: Thread? = null
    private var controlReaderThread: Thread? = null

    private var videoHeartbeatThread: Thread? = null
    private var controlHeartbeatThread: Thread? = null
    private var dedicatedHeartbeatThread: Thread? = null

    // ─── Public API ──────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        
        videoServerThread = Thread(::runVideoServer, "KoveMirror-VideoServer").also {
            it.isDaemon = true
            it.start()
        }
        controlServerThread = Thread(::runControlServer, "KoveMirror-ControlServer").also {
            it.isDaemon = true
            it.start()
        }
        heartbeatServerThread = Thread(::runHeartbeatServer, "KoveMirror-HeartbeatServer").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        connected.set(false)

        // Interrupt heartbeat threads
        videoHeartbeatThread?.interrupt()
        controlHeartbeatThread?.interrupt()
        dedicatedHeartbeatThread?.interrupt()
        
        videoReaderThread?.interrupt()
        controlReaderThread?.interrupt()

        videoHeartbeatThread = null
        controlHeartbeatThread = null
        dedicatedHeartbeatThread = null
        videoReaderThread = null
        controlReaderThread = null

        // Close sockets
        try { videoClientSocket?.close() } catch (_: Exception) {}
        try { videoServerSocket?.close() } catch (_: Exception) {}
        try { controlClientSocket?.close() } catch (_: Exception) {}
        try { controlServerSocket?.close() } catch (_: Exception) {}
        try { heartbeatClientSocket?.close() } catch (_: Exception) {}
        try { heartbeatServerSocket?.close() } catch (_: Exception) {}

        videoClientSocket = null
        videoServerSocket = null
        controlClientSocket = null
        controlServerSocket = null
        heartbeatClientSocket = null
        heartbeatServerSocket = null

        videoOutputStream = null
        controlOutputStream = null

        DebugLogger.info("🔌 All TCP port servers closed / Tüm TCP port sunucuları kapatıldı / Tüm TCP port sunucuları kapatıldı")
    }

    fun isClientConnected(): Boolean = connected.get() &&
            videoClientSocket?.isClosed == false &&
            videoClientSocket?.isConnected == true

    /**
     * H.264 NAL unit'lerini bağlı TFT'ye (Port 15456) gönder.
     */
    fun writeData(data: ByteArray): Boolean {
        return try {
            videoOutputStream?.write(data)
            videoOutputStream?.flush()
            bytesSent.addAndGet(data.size.toLong())
            true
        } catch (e: IOException) {
            DebugLogger.error("❌ writeData error / writeData hatası (Video): ${e.message}")
            false
        }
    }

    // ─── Video Server (Port 15456) ─────────────────────────────────

    private fun runVideoServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            if (!hostIp.isNullOrEmpty() && hostIp != "0.0.0.0") {
                ss.bind(InetSocketAddress(hostIp, PORT_VIDEO))
            } else {
                ss.bind(InetSocketAddress(PORT_VIDEO))
            }
            videoServerSocket = ss

            DebugLogger.success("✅ Video ServerSocket opened / Video ServerSocket açıldı → PORT $PORT_VIDEO")

            while (running.get()) {
                try {
                    ss.soTimeout = ACCEPT_TIMEOUT_MS
                    val socket = ss.accept() ?: continue
                    videoClientSocket = socket

                    DebugLogger.success("🔌 TFT Video/Projection connected! / TFT Video/Projeksiyon bağlandı! → ${socket.inetAddress.hostAddress}:${socket.port}")
                    handleVideoClient(socket)
                } catch (e: SocketTimeoutException) {
                } catch (e: IOException) {
                    if (running.get()) {
                        DebugLogger.error("❌ Video accept error / Video accept hatası / Video accept hatası: ${e.message}")
                        Thread.sleep(1000)
                    }
                }
            }
        } catch (e: IOException) {
            DebugLogger.error("❌ Video ServerSocket bind error / Video ServerSocket bind hatası (Port $PORT_VIDEO): ${e.message}")
        }
    }

    private fun handleVideoClient(socket: Socket) {
        try {
            val os = socket.getOutputStream()
            videoOutputStream = os
            connected.set(true)

            // 1. VideoSize header gönder (69 byte)
            sendVideoSizeHeader(os)

            // 2. TFT'den gelen verileri oku (TFT geri bildirim yapabilir)
            startTftVideoReader(socket.getInputStream())

            // 3. Start heartbeat / Heartbeat başlat (Port 15456 üzerinde 2s aralıklarla)
            startVideoHeartbeat(os)

            // 4. Callback tetikle (Video encoder'ı başlatır)
            onConnected(os)

            while (running.get() && !socket.isClosed && socket.isConnected) {
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ Video client error / Video client hatası / Video client hatası: ${e.message}")
        } finally {
            connected.set(false)
            videoOutputStream = null
            videoHeartbeatThread?.interrupt()
            videoHeartbeatThread = null
            try { socket.close() } catch (_: Exception) {}
            DebugLogger.warning("🔌 TFT Video connection lost / TFT Video bağlantısı koptu / TFT Video bağlantısı koptu")
            onDisconnected()
        }
    }

    private fun startVideoHeartbeat(os: OutputStream) {
        videoHeartbeatThread?.interrupt()
        videoHeartbeatThread = Thread({
            val packet = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
            try {
                while (running.get() && isClientConnected()) {
                    Thread.sleep(2000) // 2 saniye
                    os.write(packet)
                    os.flush()
                    DebugLogger.heartbeat("💓 Heartbeat sent / Heartbeat gönderildi (Video - 15456)")
                }
            } catch (_: Exception) {}
        }, "KoveMirror-VideoHeartbeat").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun sendVideoSizeHeader(os: OutputStream) {
        val buf = ByteArray(69)
        val name = "android".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(name, 0, buf, 1, minOf(name.size, 64))
        buf[65] = ((width  shr 8) and 0xFF).toByte()
        buf[66] = (width          and 0xFF).toByte()
        buf[67] = ((height shr 8) and 0xFF).toByte()
        buf[68] = (height         and 0xFF).toByte()

        os.write(buf)
        os.flush()

        val hexPreview = buf.take(10).joinToString(" ") { "%02X".format(it) }
        DebugLogger.data("📤 VideoSize header sent / VideoSize header gönderildi (69 byte) → Port $PORT_VIDEO:")
        DebugLogger.data("   HEX[0..9]: $hexPreview...")
        DebugLogger.data("   Width/Genişlik : $width px | Height/Yükseklik: $height px")
    }

    private fun startTftVideoReader(inputStream: InputStream) {
        videoReaderThread = Thread({
            DebugLogger.info("👂 TFT->Phone video reader started / TFT→Telefon video reader başlatıldı / TFT→Telefon video reader başlatıldı")
            val buf = ByteArray(4096)
            try {
                while (running.get() && connected.get()) {
                    val n = inputStream.read(buf)
                    if (n == -1) break
                    if (n > 0) {
                        val preview = buf.take(minOf(n, 20)).joinToString(" ") { "%02X".format(it) }
                        val more = if (n > 20) " (+${n - 20}B)" else ""
                        DebugLogger.data("📥 TFT→Tel (Video-15456): [$preview$more] total/toplam=$n byte")
                    }
                }
            } catch (_: Exception) {}
            DebugLogger.info("👂 TFT video reader stopped / TFT video reader durdu / TFT video reader durdu")
        }, "KoveMirror-TftVideoReader").also {
            it.isDaemon = true
            it.start()
        }
    }

    // ─── Control Server (Port 17818) ───────────────────────────────

    private fun runControlServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            if (!hostIp.isNullOrEmpty() && hostIp != "0.0.0.0") {
                ss.bind(InetSocketAddress(hostIp, PORT_CONTROL))
            } else {
                ss.bind(InetSocketAddress(PORT_CONTROL))
            }
            controlServerSocket = ss
            DebugLogger.success("✅ Control ServerSocket opened / Control ServerSocket açıldı → PORT $PORT_CONTROL")

            while (running.get()) {
                try {
                    ss.soTimeout = ACCEPT_TIMEOUT_MS
                    val socket = ss.accept() ?: continue
                    controlClientSocket = socket
                    DebugLogger.success("🔌 TFT Control connected! / TFT Control bağlandı! → ${socket.inetAddress.hostAddress}:${socket.port}")
                    handleControlClient(socket)
                } catch (e: SocketTimeoutException) {
                } catch (e: IOException) {
                    if (running.get()) Thread.sleep(1000)
                }
            }
        } catch (e: IOException) {
            DebugLogger.error("❌ Control ServerSocket bind error / Control ServerSocket bind hatası (Port $PORT_CONTROL): ${e.message}")
        }
    }

    private fun handleControlClient(socket: Socket) {
        try {
            val os = socket.getOutputStream()
            controlOutputStream = os
            
            // 1. TUC GET paketini göndererek el sıkışmayı başlat
            DebugLogger.info("📤 Sending TUC GET query to TFT... / TFT'ye TUC GET sorgusu gönderiliyor... / TFT'ye TUC GET sorgusu gönderiliyor...")
            sendJsonControlPacket(os, "{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"GET\"}")

            // 2. TFT'den yanıt geldiğinde diğer paketleri göndereceğiz
            var handshakeCompleted = false
            val inputStream = socket.getInputStream()
            controlReaderThread = Thread({
                DebugLogger.info("👂 TFT->Phone control reader started / TFT→Telefon control reader başlatıldı / TFT→Telefon control reader başlatıldı")
                val buf = ByteArray(4096)
                try {
                    while (running.get() && controlClientSocket?.isClosed == false) {
                        val n = inputStream.read(buf)
                        if (n == -1) break
                        if (n > 0) {
                            val preview = buf.take(minOf(n, 20)).joinToString(" ") { "%02X".format(it) }
                            val more = if (n > 20) " (+${n - 20}B)" else ""
                            DebugLogger.data("📥 TFT→Tel (Control-17818): [$preview$more] total/toplam=$n byte")
                            
                            // Gelen ASCII metin varsa göster
                            try {
                                val text = String(buf, 0, n, StandardCharsets.UTF_8).filter { it.code in 32..126 }
                                if (text.isNotBlank()) {
                                    DebugLogger.info("   [ASCII Text]: $text")
                                }
                            } catch (_: Exception) {}

                            // Kove 2026: Control Port üzerinden gelen 02 01 00 00 00 00 Heartbeat paketlerini Yankıla (Echo)
                            if (n == 6 && buf[0] == 0x02.toByte() && buf[1] == 0x01.toByte() && buf[2] == 0x00.toByte()) {
                                os.write(buf, 0, 6)
                                os.flush()
                                // Çok fazla log birikmesin diye sadece data olarak işaretleyebiliriz veya silebiliriz
                                // DebugLogger.heartbeat("💓 Control Heartbeat Echoed / Control Heartbeat Yankılandı (17818)")
                            }

                            // TFT'den ilk veri geldiğinde (TUC SEND veya versiyon) kalan el sıkışmayı tamamla
                            if (!handshakeCompleted) {
                                handshakeCompleted = true
                                Thread.sleep(100) // Küçük gecikme
                                sendBinaryControlHandshake(os)
                                sendJsonControlPacket(os, "{\"msg_id\":27,\"func\":\"INSIDENAVI\",\"query\":2}")
                                sendJsonControlPacket(os, "{\"msg_id\":27,\"func\":\"INSIDENAVI\",\"query\":1}")
                                DebugLogger.success("✅ Control handshake completed successfully! / Control el sıkışması başarıyla tamamlandı! / Control el sıkışması başarıyla tamamlandı!")
                            }
                        }
                    }
                } catch (_: Exception) {}
                DebugLogger.info("👂 TFT control reader stopped / TFT control reader durdu / TFT control reader durdu")
            }, "KoveMirror-TftControlReader").also {
                it.isDaemon = true
                it.start()
            }

            while (running.get() && !socket.isClosed && socket.isConnected) {
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ Control client hatası: ${e.message}")
        } finally {
            controlOutputStream = null
            controlReaderThread?.interrupt()
            controlReaderThread = null
            try { socket.close() } catch (_: Exception) {}
            DebugLogger.warning("🔌 TFT Control connection lost / TFT Control bağlantısı koptu / TFT Control bağlantısı koptu")
        }
    }

    private fun sendJsonControlPacket(os: OutputStream, jsonStr: String) {
        val jsonBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        val len = jsonBytes.size
        val buf = ByteArray(2 + 4 + len + 1)
        buf[0] = 0xEE.toByte()
        buf[1] = 0xFD.toByte()
        buf[2] = ((len shr 24) and 0xFF).toByte()
        buf[3] = ((len shr 16) and 0xFF).toByte()
        buf[4] = ((len shr 8) and 0xFF).toByte()
        buf[5] = (len and 0xFF).toByte()
        System.arraycopy(jsonBytes, 0, buf, 6, len)
        buf[6 + len] = 0xFF.toByte()
        os.write(buf)
        os.flush()
    }

    private fun sendBinaryControlHandshake(os: OutputStream) {
        // 1. Command 1 (6 bytes)
        os.write(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00))
        
        // 2. Command 23 (10 bytes)
        os.write(byteArrayOf(0x01, 0x17, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x02))
        
        // 3. Command 18 (262 bytes)
        val emailHeader = byteArrayOf(0x01, 0x12, 0x00, 0x00, 0x01, 0x00)
        val emailBody = ByteArray(256)
        val emailStrBytes = "yahoo@yahoo.com".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(emailStrBytes, 0, emailBody, 0, minOf(emailStrBytes.size, 256))
        os.write(emailHeader)
        os.write(emailBody)
        
        // 4. Command 14 (6 bytes)
        os.write(byteArrayOf(0x01, 0x0E, 0x00, 0x00, 0x00, 0x00))
        
        // 5. Command 17 (6 bytes)
        os.write(byteArrayOf(0x01, 0x11, 0x00, 0x00, 0x00, 0x00))
        
        os.flush()
        DebugLogger.info("📤 Control port binary handshake packets sent / Control port binary el sıkışma paketleri gönderildi / Control port binary el sıkışma paketleri gönderildi")
    }

    // Bu metod artık kullanılmıyor, ancak stop() içindeki referanslar hata vermesin diye boş gövdeyle tutuluyor
    private fun startControlHeartbeat(os: OutputStream) {
        // Boş
    }

    // ─── Dedicated Heartbeat Server (Port 15457) ───────────────────

    private fun runHeartbeatServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            if (!hostIp.isNullOrEmpty() && hostIp != "0.0.0.0") {
                ss.bind(InetSocketAddress(hostIp, PORT_HEARTBEAT))
            } else {
                ss.bind(InetSocketAddress(PORT_HEARTBEAT))
            }
            heartbeatServerSocket = ss
            DebugLogger.success("✅ Heartbeat ServerSocket opened / Heartbeat ServerSocket açıldı → PORT $PORT_HEARTBEAT")

            while (running.get()) {
                try {
                    ss.soTimeout = ACCEPT_TIMEOUT_MS
                    val socket = ss.accept() ?: continue
                    heartbeatClientSocket = socket
                    DebugLogger.success("🔌 TFT Dedicated Heartbeat connected! / TFT Dedicated Heartbeat bağlandı! → ${socket.inetAddress.hostAddress}:${socket.port}")
                    startDedicatedHeartbeat(socket.getOutputStream())
                } catch (e: SocketTimeoutException) {
                } catch (e: IOException) {
                    if (running.get()) Thread.sleep(1000)
                }
            }
        } catch (e: IOException) {
            DebugLogger.error("❌ Heartbeat ServerSocket bind error / Heartbeat ServerSocket bind hatası (Port $PORT_HEARTBEAT): ${e.message}")
        }
    }

    private fun startDedicatedHeartbeat(os: OutputStream) {
        dedicatedHeartbeatThread?.interrupt()
        dedicatedHeartbeatThread = Thread({
            val packet = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
            DebugLogger.info("💓 Dedicated Heartbeat sending started / Dedicated Heartbeat gönderimi başladı (15457, 200ms aralık)")
            var count = 0L
            try {
                while (running.get() && heartbeatClientSocket?.isClosed == false && heartbeatClientSocket?.isConnected == true) {
                    os.write(packet)
                    os.flush()
                    count++
                    if (count % 25 == 0L) { // Her 5 saniyede bir log yaz
                        DebugLogger.heartbeat("💓 Dedicated Heartbeat #$count gönderildi (15457)")
                    }
                    Thread.sleep(200L)
                }
            } catch (e: Exception) {
                DebugLogger.warning("⚠️ Dedicated Heartbeat sending stopped / Dedicated Heartbeat gönderim durdu / Dedicated Heartbeat gönderim durdu: ${e.message}")
            }
            DebugLogger.info("💓 Dedicated Heartbeat sending finished / Dedicated Heartbeat gönderimi bitti / Dedicated Heartbeat gönderimi bitti")
        }, "KoveMirror-DedicatedHeartbeat").also {
            it.isDaemon = true
            it.start()
        }
    }
}
