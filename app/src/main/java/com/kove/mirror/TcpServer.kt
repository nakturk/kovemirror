package com.kove.mirror

import android.net.Network
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketImpl
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TcpServer(
    private val hostIp: String?,
    private val wifiNetwork: Network? = null,
    private val width: Int,
    private val height: Int,
    private val videoEnabled: Boolean = true,
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
    @Volatile private var videoServerSocket: ServerSocket? = null
    @Volatile private var controlServerSocket: ServerSocket? = null
    @Volatile private var heartbeatServerSocket: ServerSocket? = null

    // ClientSockets
    @Volatile private var videoClientSocket: Socket? = null
    @Volatile private var controlClientSocket: Socket? = null
    @Volatile private var heartbeatClientSocket: Socket? = null

    // OutputStreams
    @Volatile private var videoOutputStream: OutputStream? = null
    @Volatile private var controlOutputStream: OutputStream? = null
    private val videoWriteLock = Any()

    private val running    = AtomicBoolean(false)
    private val connected  = AtomicBoolean(false)
    val bytesSent          = AtomicLong(0)

    // Threads
    @Volatile private var videoServerThread: Thread? = null
    @Volatile private var controlServerThread: Thread? = null
    @Volatile private var heartbeatServerThread: Thread? = null

    @Volatile private var videoReaderThread: Thread? = null
    @Volatile private var controlReaderThread: Thread? = null

    @Volatile private var videoHeartbeatThread: Thread? = null
    @Volatile private var controlHeartbeatThread: Thread? = null
    @Volatile private var dedicatedHeartbeatThread: Thread? = null

    // ─── Public API ──────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        
        if (videoEnabled) {
            videoServerThread = Thread(::runVideoServer, "KoveMirror-VideoServer").also {
                it.isDaemon = true
                it.start()
            }
        } else {
            DebugLogger.info("📺 Video server disabled (Control Only mode)")
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

        // Interrupt server threads
        videoServerThread?.interrupt()
        controlServerThread?.interrupt()
        heartbeatServerThread?.interrupt()

        // Interrupt heartbeat threads
        videoHeartbeatThread?.interrupt()
        controlHeartbeatThread?.interrupt()
        dedicatedHeartbeatThread?.interrupt()
        
        videoReaderThread?.interrupt()
        controlReaderThread?.interrupt()

        videoServerThread = null
        controlServerThread = null
        heartbeatServerThread = null
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

        synchronized(videoWriteLock) {
            videoOutputStream = null
        }
        controlOutputStream = null

        DebugLogger.info(R.string.log_tcp_all_closed)
    }

    fun isClientConnected(): Boolean = connected.get() &&
            videoClientSocket?.isClosed == false

    /**
     * H.264 NAL unit'lerini bağlı TFT'ye (Port 15456) gönder.
     */
    fun writeData(data: ByteArray): Boolean {
        return try {
            synchronized(videoWriteLock) {
                val os = videoOutputStream ?: return false
                os.write(data)
                os.flush()
            }
            bytesSent.addAndGet(data.size.toLong())
            true
        } catch (e: IOException) {
            DebugLogger.error(R.string.log_write_data_error, e.message ?: "")
            connected.set(false)
            try { videoClientSocket?.close() } catch (_: Exception) {}
            false
        }
    }

    // ─── Network Socket Binding Helpers ───────────────────────────

    private fun createBoundServerSocket(port: Int): ServerSocket {
        val ss = ServerSocket()
        ss.reuseAddress = true
        bindServerSocketToNetwork(ss, wifiNetwork)
        if (!hostIp.isNullOrEmpty() && hostIp != "0.0.0.0") {
            ss.bind(InetSocketAddress(hostIp, port))
        } else {
            ss.bind(InetSocketAddress(port))
        }
        return ss
    }

    private fun bindServerSocketToNetwork(ss: ServerSocket, network: Network?) {
        if (network == null) return
        try {
            val getImplMethod = ServerSocket::class.java.getDeclaredMethod("getImpl")
            getImplMethod.isAccessible = true
            val socketImpl = getImplMethod.invoke(ss)
            val getFdMethod = SocketImpl::class.java.getDeclaredMethod("getFileDescriptor")
            getFdMethod.isAccessible = true
            val fd = getFdMethod.invoke(socketImpl) as? FileDescriptor
            if (fd != null && fd.valid()) {
                network.bindSocket(fd)
            }
        } catch (_: Throwable) {
            // Fallback: ServerSocket is bound to Wi-Fi interface IP (hostIp)
            // and accepted client sockets are bound via network.bindSocket(socket)
        }
    }

    private fun bindClientSocketToNetwork(socket: Socket) {
        val network = wifiNetwork ?: return
        try {
            network.bindSocket(socket)
        } catch (_: Throwable) {}
    }

    // ─── Video Server (Port 15456) ─────────────────────────────────

    private fun runVideoServer() {
        try {
            val ss = createBoundServerSocket(PORT_VIDEO)
            videoServerSocket = ss

            DebugLogger.success(R.string.log_video_socket_opened, PORT_VIDEO)

            while (running.get() && ss.isBound && !ss.isClosed) {
                try {
                    ss.soTimeout = ACCEPT_TIMEOUT_MS
                    val socket = ss.accept() ?: continue
                    bindClientSocketToNetwork(socket)
                    videoClientSocket = socket

                    DebugLogger.success(R.string.log_tft_video_connected_ip, socket.inetAddress.hostAddress ?: "", socket.port)
                    handleVideoClient(socket)
                } catch (e: SocketTimeoutException) {
                } catch (e: IOException) {
                    if (!running.get() || ss.isClosed) break
                    DebugLogger.error(R.string.log_video_accept_error, e.message ?: "")
                    Thread.sleep(1000)
                }
            }
        } catch (e: IOException) {
            DebugLogger.error(R.string.log_video_bind_error, PORT_VIDEO, e.message ?: "")
        }
    }

    private fun handleVideoClient(socket: Socket) {
        try {
            val os = socket.getOutputStream()
            synchronized(videoWriteLock) {
                videoOutputStream = os
            }
            connected.set(true)

            // 1. VideoSize header gönder (69 byte)
            sendVideoSizeHeader(os)

            // 2. TFT'den gelen verileri oku (TFT geri bildirim yapabilir)
            startTftVideoReader(socket, socket.getInputStream())

            // 3. Start heartbeat / Heartbeat başlat (Port 15456 üzerinde 2s aralıklarla)
            startVideoHeartbeat(os)

            // 4. Callback tetikle (Video encoder'ı başlatır)
            onConnected(os)

            while (running.get() && connected.get() && !socket.isClosed) {
                Thread.sleep(200)
            }
        } catch (e: Exception) {
            DebugLogger.error(R.string.log_video_accept_error, e.message ?: "")
        } finally {
            connected.set(false)
            synchronized(videoWriteLock) {
                videoOutputStream = null
            }
            videoHeartbeatThread?.interrupt()
            videoHeartbeatThread = null
            videoReaderThread?.interrupt()
            videoReaderThread = null
            try { socket.close() } catch (_: Exception) {}
            DebugLogger.warning(R.string.log_tft_video_conn_lost)
            onDisconnected()
        }
    }

    private fun startVideoHeartbeat(os: OutputStream) {
        videoHeartbeatThread?.interrupt()
        videoHeartbeatThread = Thread({
            val packet = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
            try {
                while (running.get() && isClientConnected()) {
                    Thread.sleep(2000)
                    synchronized(videoWriteLock) {
                        if (isClientConnected() && videoOutputStream != null) {
                            os.write(packet)
                            os.flush()
                        }
                    }
                    DebugLogger.heartbeat(R.string.log_hb_sent, 0, "Video-15456")
                }
            } catch (_: Exception) {
                connected.set(false)
                try { videoClientSocket?.close() } catch (_: Exception) {}
            }
        }, "KoveMirror-VideoHeartbeat").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun sendVideoSizeHeader(os: OutputStream) {
        val buf = ByteArray(69)
        val name = "android".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(name, 0, buf, 0, minOf(name.size, 65))
        buf[65] = ((width  shr 8) and 0xFF).toByte()
        buf[66] = (width          and 0xFF).toByte()
        buf[67] = ((height shr 8) and 0xFF).toByte()
        buf[68] = (height         and 0xFF).toByte()

        synchronized(videoWriteLock) {
            os.write(buf)
            os.flush()
        }

        val hexPreview = buf.take(10).joinToString(" ") { "%02X".format(it) }
        DebugLogger.data("📤 VideoSize header -> Port $PORT_VIDEO:")
        DebugLogger.data("   HEX[0..9]: $hexPreview...")
        DebugLogger.data("   Width: $width px | Height: $height px")
    }

    private fun startTftVideoReader(socket: Socket, inputStream: InputStream) {
        videoReaderThread = Thread({
            DebugLogger.info("👂 TFT video reader started")
            val buf = ByteArray(4096)
            try {
                while (running.get() && connected.get() && !socket.isClosed) {
                    val n = inputStream.read(buf)
                    if (n == -1) break
                    if (n > 0) {
                        val preview = buf.take(minOf(n, 20)).joinToString(" ") { "%02X".format(it) }
                        val more = if (n > 20) " (+${n - 20}B)" else ""
                        DebugLogger.data("📥 TFT->Phone (Video-15456): [$preview$more] total=$n byte")
                    }
                }
            } catch (_: Exception) {
            } finally {
                connected.set(false)
                try { socket.close() } catch (_: Exception) {}
                DebugLogger.info("👂 TFT video reader stopped")
            }
        }, "KoveMirror-TftVideoReader").also {
            it.isDaemon = true
            it.start()
        }
    }

    // ─── Control Server (Port 17818) ───────────────────────────────

    private fun runControlServer() {
        try {
            val ss = createBoundServerSocket(PORT_CONTROL)
            controlServerSocket = ss
            DebugLogger.success(R.string.log_control_socket_opened, PORT_CONTROL)

            while (running.get() && ss.isBound && !ss.isClosed) {
                try {
                    ss.soTimeout = ACCEPT_TIMEOUT_MS
                    val socket = ss.accept() ?: continue
                    bindClientSocketToNetwork(socket)
                    controlClientSocket = socket
                    DebugLogger.success(R.string.log_tft_control_connected_ip, socket.inetAddress.hostAddress ?: "", socket.port)
                    handleControlClient(socket)
                } catch (e: SocketTimeoutException) {
                } catch (e: IOException) {
                    if (!running.get() || ss.isClosed) break
                    Thread.sleep(1000)
                }
            }
        } catch (e: IOException) {
            DebugLogger.error(R.string.log_control_bind_error, PORT_CONTROL, e.message ?: "")
        }
    }

    private fun handleControlClient(socket: Socket) {
        try {
            val os = socket.getOutputStream()
            controlOutputStream = os
            
            // 1. TUC GET paketini göndererek el sıkışmayı başlat
            DebugLogger.info(R.string.log_sending_tuc_query)
            sendJsonControlPacket(os, "{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"GET\"}")

            // 2. TFT'den yanıt geldiğinde diğer paketleri göndereceğiz
            var handshakeCompleted = false
            val inputStream = socket.getInputStream()
            controlReaderThread = Thread({
                DebugLogger.info("👂 TFT control reader started")
                val readBuf = ByteArray(4096)
                val buffer = java.io.ByteArrayOutputStream()

                fun triggerHandshake() {
                    if (!handshakeCompleted) {
                        handshakeCompleted = true
                        Thread.sleep(100)
                        sendBinaryControlHandshake(os)
                        sendJsonControlPacket(os, "{\"msg_id\":27,\"func\":\"INSIDENAVI\",\"query\":2}")
                        sendJsonControlPacket(os, "{\"msg_id\":27,\"func\":\"INSIDENAVI\",\"query\":1}")
                        DebugLogger.success(R.string.log_control_handshake_done)
                    }
                }

                try {
                    while (running.get() && !socket.isClosed) {
                        val n = inputStream.read(readBuf)
                        if (n == -1) break
                        if (n > 0) {
                            val preview = readBuf.take(minOf(n, 20)).joinToString(" ") { "%02X".format(it) }
                            val more = if (n > 20) " (+${n - 20}B)" else ""
                            DebugLogger.data("📥 TFT->Phone (Control-17818): [$preview$more] total=$n byte")

                            buffer.write(readBuf, 0, n)

                            // Deframing loop
                            while (buffer.size() > 0) {
                                val bytes = buffer.toByteArray()

                                // 1. Check for 6-byte Heartbeat: 02 01 00 00 00 00
                                if (bytes.size >= 6 &&
                                    bytes[0] == 0x02.toByte() &&
                                    bytes[1] == 0x01.toByte() &&
                                    bytes[2] == 0x00.toByte() &&
                                    bytes[3] == 0x00.toByte() &&
                                    bytes[4] == 0x00.toByte() &&
                                    bytes[5] == 0x00.toByte()
                                ) {
                                    os.write(bytes, 0, 6)
                                    os.flush()
                                    triggerHandshake()

                                    buffer.reset()
                                    if (bytes.size > 6) {
                                        buffer.write(bytes, 6, bytes.size - 6)
                                    }
                                    continue
                                }

                                // 2. Check for Framed JSON packet: EE FD [len 4 BE] [payload] FF
                                if (bytes.size >= 2 && bytes[0] == 0xEE.toByte() && bytes[1] == 0xFD.toByte()) {
                                    if (bytes.size < 6) {
                                        // Wait for length header
                                        break
                                    }
                                    val len = ((bytes[2].toInt() and 0xFF) shl 24) or
                                            ((bytes[3].toInt() and 0xFF) shl 16) or
                                            ((bytes[4].toInt() and 0xFF) shl 8) or
                                            (bytes[5].toInt() and 0xFF)

                                    if (len < 0 || len > 65536) {
                                        // Invalid length, discard header to resync
                                        DebugLogger.warning("⚠️ Invalid control frame length: $len, resyncing...")
                                        buffer.reset()
                                        if (bytes.size > 2) {
                                            buffer.write(bytes, 2, bytes.size - 2)
                                        }
                                        continue
                                    }

                                    val frameLen = 2 + 4 + len + 1
                                    if (bytes.size < frameLen) {
                                        // Full frame not received yet, wait for more data
                                        break
                                    }

                                    val payload = String(bytes, 6, len, StandardCharsets.UTF_8)
                                    DebugLogger.info("   [Control JSON]: $payload")
                                    HandlebarKeyManager.processJson(payload)
                                    triggerHandshake()

                                    buffer.reset()
                                    if (bytes.size > frameLen) {
                                        buffer.write(bytes, frameLen, bytes.size - frameLen)
                                    }
                                    continue
                                }

                                // 3. Check for raw JSON starting with '{'
                                if (bytes[0] == '{'.code.toByte()) {
                                    var depth = 0
                                    var jsonEnd = -1
                                    for (i in bytes.indices) {
                                        if (bytes[i] == '{'.code.toByte()) depth++
                                        else if (bytes[i] == '}'.code.toByte()) {
                                            depth--
                                            if (depth == 0) {
                                                jsonEnd = i
                                                break
                                            }
                                        }
                                    }
                                    if (jsonEnd != -1) {
                                        val jsonStr = String(bytes, 0, jsonEnd + 1, StandardCharsets.UTF_8)
                                        DebugLogger.info("   [Raw JSON]: $jsonStr")
                                        HandlebarKeyManager.processJson(jsonStr)
                                        triggerHandshake()

                                        buffer.reset()
                                        if (bytes.size > jsonEnd + 1) {
                                            buffer.write(bytes, jsonEnd + 1, bytes.size - (jsonEnd + 1))
                                        }
                                        continue
                                    } else {
                                        // Incomplete raw JSON, wait for more bytes
                                        break
                                    }
                                }

                                // 4. Unrecognized byte: scan forward to find next known header (0x02, 0xEE, '{')
                                var syncIdx = -1
                                for (i in 1 until bytes.size) {
                                    if (bytes[i] == 0x02.toByte() || bytes[i] == 0xEE.toByte() || bytes[i] == '{'.code.toByte()) {
                                        syncIdx = i
                                        break
                                    }
                                }
                                if (syncIdx != -1) {
                                    buffer.reset()
                                    buffer.write(bytes, syncIdx, bytes.size - syncIdx)
                                } else {
                                    buffer.reset()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                    DebugLogger.info("👂 TFT control reader stopped")
                }
            }, "KoveMirror-TftControlReader").also {
                it.isDaemon = true
                it.start()
            }

            while (running.get() && !socket.isClosed) {
                Thread.sleep(200)
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ Control client error: ${e.message}")
        } finally {
            controlOutputStream = null
            controlReaderThread?.interrupt()
            controlReaderThread = null
            try { socket.close() } catch (_: Exception) {}
            DebugLogger.warning(R.string.log_tft_control_conn_lost)
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
        os.write(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00))
        os.write(byteArrayOf(0x01, 0x17, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x02))
        
        val emailHeader = byteArrayOf(0x01, 0x12, 0x00, 0x00, 0x01, 0x00)
        val emailBody = ByteArray(256)
        val emailStrBytes = "yahoo@yahoo.com".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(emailStrBytes, 0, emailBody, 0, minOf(emailStrBytes.size, 256))
        os.write(emailHeader)
        os.write(emailBody)
        
        os.write(byteArrayOf(0x01, 0x0E, 0x00, 0x00, 0x00, 0x00))
        os.write(byteArrayOf(0x01, 0x11, 0x00, 0x00, 0x00, 0x00))
        
        os.flush()
        DebugLogger.info(R.string.log_binary_handshake_sent)
    }

    private fun startControlHeartbeat(os: OutputStream) {}

    // ─── Dedicated Heartbeat Server (Port 15457) ───────────────────

    private fun runHeartbeatServer() {
        try {
            val ss = createBoundServerSocket(PORT_HEARTBEAT)
            heartbeatServerSocket = ss
            DebugLogger.success(R.string.log_hb_socket_opened, PORT_HEARTBEAT)

            while (running.get() && ss.isBound && !ss.isClosed) {
                try {
                    ss.soTimeout = ACCEPT_TIMEOUT_MS
                    val socket = ss.accept() ?: continue
                    bindClientSocketToNetwork(socket)
                    
                    // Close previous socket if still open
                    try { heartbeatClientSocket?.close() } catch (_: Exception) {}
                    heartbeatClientSocket = socket

                    DebugLogger.success(R.string.log_tft_dedicated_hb_connected, socket.inetAddress.hostAddress ?: "", socket.port)
                    startDedicatedHeartbeat(socket)
                } catch (e: SocketTimeoutException) {
                } catch (e: IOException) {
                    if (!running.get() || ss.isClosed) break
                    Thread.sleep(1000)
                }
            }
        } catch (e: IOException) {
            DebugLogger.error(R.string.log_control_bind_error, PORT_HEARTBEAT, e.message ?: "")
        }
    }

    private fun startDedicatedHeartbeat(socket: Socket) {
        dedicatedHeartbeatThread?.interrupt()
        dedicatedHeartbeatThread = Thread({
            val packet = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
            DebugLogger.info(R.string.log_dedicated_hb_started)
            var count = 0L
            try {
                val os = socket.getOutputStream()
                while (running.get() && !socket.isClosed) {
                    os.write(packet)
                    os.flush()
                    count++
                    if (count % 25 == 0L) {
                        DebugLogger.heartbeat(R.string.log_hb_sent, count, "15457")
                    }
                    Thread.sleep(200L)
                }
            } catch (e: Exception) {
                DebugLogger.warning(R.string.log_dedicated_hb_stopped, e.message ?: "")
            } finally {
                try { socket.close() } catch (_: Exception) {}
                if (heartbeatClientSocket == socket) {
                    heartbeatClientSocket = null
                }
            }
        }, "KoveMirror-DedicatedHeartbeat").also {
            it.isDaemon = true
            it.start()
        }
    }
}
