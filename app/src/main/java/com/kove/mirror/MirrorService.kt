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

class MirrorService : Service() {

    companion object {
        const val ACTION_START       = "com.kove.mirror.START"
        const val ACTION_STOP        = "com.kove.mirror.STOP"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val CHANNEL_ID         = "KoveMirrorCh"
        const val NOTIF_ID           = 1001

        @Volatile var TFT_WIDTH          = 600
        @Volatile var TFT_HEIGHT         = 1024
        @Volatile var TFT_PADDING        = 0
        @Volatile var DISPLAY_MODE       = DisplayMode.CENTER_CROP
        @Volatile var PHONE_ASPECT_RATIO = 0.45f

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
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // ─── Lifecycle ───────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        DebugLogger.setContext(this)
        runningInstance = this
        createNotificationChannel()
        DebugLogger.info("🚀 MirrorService started")
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
                            buildNotif(getString(R.string.notif_waiting_tft)),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(NOTIF_ID, buildNotif(getString(R.string.notif_waiting_tft)))
                    }
                    startMirroring(code, data)
                } else {
                    DebugLogger.error("❌ Invalid MediaProjection data: code=$code, data=$data")
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
            DebugLogger.info(getString(R.string.log_mirroring_starting))
            DebugLogger.info("   Video Port: ${TcpServer.PORT_VIDEO}")
            DebugLogger.info("   Control Port: ${TcpServer.PORT_CONTROL}")
            DebugLogger.info("   Heartbeat Port: ${TcpServer.PORT_HEARTBEAT}")

            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "KoveMirror::AlwaysOn"
            )
            wakeLock?.acquire()

            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = pm.getMediaProjection(resultCode, data) ?: throw NullPointerException("MediaProjection is null")
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    DebugLogger.warning("⚠️ MediaProjection stopped by system")
                    stopMirroring()
                }
            }, null)

            val savedMac = getSharedPreferences("kove_prefs", MODE_PRIVATE).getString("bt_mac", "")
            if (!savedMac.isNullOrEmpty()) {
                bleManager = BleManager(this) { msg ->
                    DebugLogger.log(LogLevel.INFO, msg)
                }
                bleManager?.onMirrorRequested = {
                    if (!tcpServerStarted || tcpServer == null) {
                        DebugLogger.info("🔄 Mirroring requested from TFT, TCP Server restarting...")
                        Handler(Looper.getMainLooper()).post {
                            startTcpServer()
                        }
                    }
                }
                bleManager?.connect(savedMac)
            } else {
                DebugLogger.warning(getString(R.string.log_bt_mac_not_selected))
            }

            bindToWifiNetwork()
        } catch (e: Exception) {
            DebugLogger.error("❌ startMirroring error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startTcpServer() {
        val projection = mediaProjection
        if (projection == null) {
            DebugLogger.warning("⚠️ MediaProjection not ready yet, TCP Server not started")
            return
        }

        if (tcpServerStarted) {
            DebugLogger.info("🔄 TCP Server already running, restarting...")
            tcpServer?.stop()
            tcpServer = null
        }

        val ipAddress = NetworkUtils.getWifiIpAddress(applicationContext)
        DebugLogger.info("🔌 TCP Server starting, bind IP: $ipAddress")

        val server = TcpServer(
            hostIp = ipAddress,
            width  = TFT_WIDTH,
            height = TFT_HEIGHT,
            onConnected = { os ->
                DebugLogger.success(getString(R.string.log_tft_video_connected))
                try {
                    val encoder = ProjectionEncoder(
                        mediaProjection = projection,
                        width  = TFT_WIDTH,
                        height = TFT_HEIGHT,
                        padding = TFT_PADDING,
                        displayMode = DISPLAY_MODE,
                        phoneAspectRatio = PHONE_ASPECT_RATIO
                    )
                    projectionEncoder = encoder
                    if (encoder.init()) {
                        encoder.startEncoding { nalData ->
                            tcpServer?.writeData(nalData)
                        }
                    } else {
                        DebugLogger.error("❌ Encoder could not be started")
                    }
                } catch (e: Exception) {
                    DebugLogger.error("❌ Encoder start error: ${e.message}")
                }
                updateNotif(getString(R.string.notif_tft_connected))
            },
            onDisconnected = {
                DebugLogger.warning(getString(R.string.log_tft_disconnected_waiting))
                projectionEncoder?.stop()
                projectionEncoder = null
                updateNotif(getString(R.string.notif_waiting_tft_port, TcpServer.PORT_VIDEO))

                Handler(Looper.getMainLooper()).postDelayed({
                    if (tcpServerStarted && bleManager != null) {
                        DebugLogger.info("🔄 Auto-Reconnect triggered...")
                        bleManager?.sendInitPackets()
                    }
                }, 2000)
            }
        )
        tcpServer = server
        server.start()
        tcpServerStarted = true

        val gw = NetworkUtils.getGatewayAddress(applicationContext)
        DebugLogger.info("─────────────────────────────")
        DebugLogger.info("📡 Phone IP: $ipAddress")
        DebugLogger.info("🏍️ Gateway (TBox): $gw")
        DebugLogger.info("🔌 Ports: Video=${TcpServer.PORT_VIDEO}, Control=${TcpServer.PORT_CONTROL}, Heartbeat=${TcpServer.PORT_HEARTBEAT}")
        DebugLogger.info("─────────────────────────────")
    }

    private fun stopMirroring() {
        DebugLogger.info(getString(R.string.log_stopping_mirroring))
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

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        DebugLogger.info(getString(R.string.log_all_stopped))
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
                            DebugLogger.success(getString(R.string.log_wifi_bound_process))
                        } else {
                            @Suppress("DEPRECATION")
                            ConnectivityManager.setProcessDefaultNetwork(network)
                            DebugLogger.success(getString(R.string.log_wifi_bound_process_legacy))
                        }
                        Handler(Looper.getMainLooper()).post {
                            startTcpServer()
                        }
                    } catch (e: Exception) {
                        DebugLogger.error("❌ Error binding to network: ${e.message}")
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
                        DebugLogger.warning(getString(R.string.log_wifi_lost))
                    } catch (e: Exception) {
                        DebugLogger.error("❌ Error unbinding network: ${e.message}")
                    }
                }
            }

            wifiNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            DebugLogger.info(getString(R.string.log_searching_wifi))
        } catch (e: Exception) {
            DebugLogger.error("❌ bindToWifiNetwork error: ${e.message}")
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
            DebugLogger.info(getString(R.string.log_wifi_unbound))
        } catch (e: Exception) {
            DebugLogger.error("❌ unbindFromWifiNetwork error: ${e.message}")
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
            .setContentTitle("🏍️ " + getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_action_stop), stopPi)
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
                CHANNEL_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_description) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
