package com.kove.mirror

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Ön plan servisi — MediaProjection + TCP server yaşam döngüsünü yönetir.
 *
 * Başlatma akışı:
 * 1. MainActivity → createScreenCaptureIntent() → kullanıcı onayı
 * 2. onActivityResult → MirrorService.startService(resultCode, data)
 * 3. MirrorService.onCreate → startForeground()
 * 4. startMirroring():
 *    a. MediaProjection oluştur
 *    b. TcpServer başlat (port 17818 dinle)
 *    c. ProjectionEncoder başlat (VirtualDisplay + H.264)
 *    d. TFT bağlanınca: VideoSize header + HeartBeat + stream
 */
class MirrorService : Service() {

    companion object {
        const val ACTION_START       = "com.kove.mirror.START"
        const val ACTION_STOP        = "com.kove.mirror.STOP"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val CHANNEL_ID         = "KoveMirrorCh"
        const val NOTIF_ID           = 1001

        // Değiştirilebilir çözünürlük (MainActivity'den ayarlanır)
        @Volatile var TFT_WIDTH   = 480
        @Volatile var TFT_HEIGHT  = 800
        @Volatile var TFT_PADDING = 0

        @Volatile var runningInstance: MirrorService? = null

        fun updatePadding(padding: Int) {
            TFT_PADDING = padding
            runningInstance?.projectionEncoder?.updatePadding(padding)
        }

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, MirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i)
            else
                context.startService(i)
        }

        fun stopService(context: Context) {
            context.startService(
                Intent(context, MirrorService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private var tcpServer:         TcpServer?         = null
    private var projectionEncoder: ProjectionEncoder? = null
    private var mediaProjection:   MediaProjection?   = null
    private var bleManager:        BleManager?        = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var tcpServerStarted = false

    // ─── Lifecycle ───────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        createNotificationChannel()
        DebugLogger.info("🚀 MirrorService oluşturuldu")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (code != 0 && data != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIF_ID,
                            buildNotif("⏳ TFT bağlantısı bekleniyor..."),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(NOTIF_ID, buildNotif("⏳ TFT bağlantısı bekleniyor..."))
                    }
                    startMirroring(code, data)
                } else {
                    DebugLogger.error("❌ Geçersiz MediaProjection verisi: code=$code, data=$data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopMirroring()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMirroring()
        runningInstance = null
        super.onDestroy()
    }

    // ─── Mirroring logic ─────────────────────────────────────────

    private fun startMirroring(resultCode: Int, data: Intent) {
        try {
            DebugLogger.info("▶️ Yansıtma başlatılıyor...")
            DebugLogger.info("   Video Port: ${TcpServer.PORT_VIDEO}")
            DebugLogger.info("   Control Port: ${TcpServer.PORT_CONTROL}")
            DebugLogger.info("   Heartbeat Port: ${TcpServer.PORT_HEARTBEAT}")

            // 1) MediaProjection (TCP Server'dan önce hazır olmalı)
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = pm.getMediaProjection(resultCode, data) ?: throw NullPointerException("MediaProjection is null")
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    DebugLogger.warning("⚠️ MediaProjection sistem tarafından durduruldu")
                    stopMirroring()
                }
            }, null)

            // 2) Bluetooth (TFT BLE)
            val savedMac = getSharedPreferences("kove_prefs", MODE_PRIVATE).getString("bt_mac", "")
            if (!savedMac.isNullOrEmpty()) {
                bleManager = BleManager(this) { msg ->
                    DebugLogger.log(LogLevel.INFO, msg)
                }
                bleManager?.connect(savedMac)
            } else {
                DebugLogger.warning("⚠️ Bluetooth MAC adresi seçilmemiş, BLE tetikleme devre dışı")
            }

            // 3) WiFi Ağına bağlan — Bağlanınca TCP Server asenkron olarak tetiklenecek
            bindToWifiNetwork()
        } catch (e: Exception) {
            DebugLogger.error("❌ startMirroring hatası: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startTcpServer() {
        val projection = mediaProjection
        if (projection == null) {
            DebugLogger.warning("⚠️ MediaProjection henüz hazır değil, TCP Server başlatılmadı")
            return
        }

        if (tcpServerStarted) {
            DebugLogger.info("🔄 TCP Server zaten çalışıyor, yeniden başlatılıyor...")
            tcpServer?.stop()
            tcpServer = null
        }

        val ipAddress = NetworkUtils.getWifiIpAddress(applicationContext)
        DebugLogger.info("🔌 TCP Server başlatılıyor, bind IP: $ipAddress")

        val server = TcpServer(
            hostIp = ipAddress,
            width  = TFT_WIDTH,
            height = TFT_HEIGHT,
            onConnected = { os ->
                DebugLogger.success("🚀 TFT Video bağlandı, encoder başlatılıyor...")
                try {
                    val encoder = ProjectionEncoder(projection, TFT_WIDTH, TFT_HEIGHT, padding = TFT_PADDING)
                    projectionEncoder = encoder
                    if (encoder.init()) {
                        encoder.startEncoding { nalData ->
                            tcpServer?.writeData(nalData)
                        }
                    } else {
                        DebugLogger.error("❌ Encoder başlatılamadı")
                    }
                } catch (e: Exception) {
                    DebugLogger.error("❌ Encoder start hatası: ${e.message}")
                }
                updateNotif("🔴 TFT bağlı — Stream aktif")
            },
            onDisconnected = {
                DebugLogger.warning("🔌 TFT koptu — yeniden bağlantı bekleniyor...")
                projectionEncoder?.stop()
                projectionEncoder = null
                updateNotif("🟡 TFT bekleniyor (port ${TcpServer.PORT_VIDEO})")
            }
        )
        tcpServer = server
        server.start()
        tcpServerStarted = true

        val gw = NetworkUtils.getGatewayAddress(applicationContext)
        DebugLogger.info("─────────────────────────────")
        DebugLogger.info("📡 Telefon IP  : $ipAddress")
        DebugLogger.info("🏍️ Gateway(TBox): $gw")
        DebugLogger.info("🔌 Dinlenen portlar: Video=${TcpServer.PORT_VIDEO}, Control=${TcpServer.PORT_CONTROL}, Heartbeat=${TcpServer.PORT_HEARTBEAT}")
        DebugLogger.info("─────────────────────────────")
        DebugLogger.info("Motosiklet WiFi'sine bağlanın ve TFT'nin")
        DebugLogger.info("bu IP:portlara bağlanmasını bekleyin.")
    }

    private fun stopMirroring() {
        DebugLogger.info("⏹️ Yansıtma durduruluyor...")
        bleManager?.disconnect()
        bleManager = null
        unbindFromWifiNetwork()
        projectionEncoder?.stop()
        projectionEncoder = null
        tcpServer?.stop()
        tcpServer = null
        tcpServerStarted = false
        mediaProjection?.stop()
        mediaProjection = null
        DebugLogger.info("⏹️ Tümü durduruldu")
    }

    private fun bindToWifiNetwork() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(network)
                            DebugLogger.success("🔗 Uygulama süreci başarıyla WiFi ağına bağlandı (bindProcessToNetwork)")
                        } else {
                            @Suppress("DEPRECATION")
                            ConnectivityManager.setProcessDefaultNetwork(network)
                            DebugLogger.success("🔗 Uygulama süreci başarıyla WiFi ağına bağlandı (setProcessDefaultNetwork)")
                        }
                        Handler(Looper.getMainLooper()).post {
                            startTcpServer()
                        }
                    } catch (e: Exception) {
                        DebugLogger.error("❌ Ağa bağlanırken hata oluştu: ${e.message}")
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(null)
                        } else {
                            @Suppress("DEPRECATION")
                            ConnectivityManager.setProcessDefaultNetwork(null)
                        }
                        DebugLogger.warning("🔌 WiFi ağ bağlantısı koptu, ağ bağı kaldırıldı")
                    } catch (e: Exception) {
                        DebugLogger.error("❌ Ağ bağı kaldırılırken hata: ${e.message}")
                    }
                }
            }

            wifiNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            DebugLogger.info("📡 WiFi ağı aranıyor ve süreç bağlanmaya hazırlanıyor...")
        } catch (e: Exception) {
            DebugLogger.error("❌ bindToWifiNetwork hatası: ${e.message}")
        }
    }

    private fun unbindFromWifiNetwork() {
        try {
            val callback = wifiNetworkCallback
            if (callback != null) {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(callback)
                wifiNetworkCallback = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.bindProcessToNetwork(null)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(null)
            }
            DebugLogger.info("🔌 WiFi ağ bağı temizlendi")
        } catch (e: Exception) {
            DebugLogger.error("❌ unbindFromWifiNetwork hatası: ${e.message}")
        }
    }

    // ─── Notification ────────────────────────────────────────────

    private fun buildNotif(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🏍️ KoveMirror")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "KoveMirror Yansıtma",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Kove TFT ekran yansıtma servisi" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
