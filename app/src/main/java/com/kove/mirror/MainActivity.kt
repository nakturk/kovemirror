package com.kove.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_SCREEN_CAPTURE = 100
        const val REQ_NOTIFICATION   = 101
    }

    private lateinit var tvIp:        TextView
    private lateinit var tvSsid:      TextView
    private lateinit var tvGateway:   TextView
    private lateinit var tvStatus:    TextView
    private lateinit var btnStart:    Button
    private lateinit var tvLog:       TextView
    private lateinit var scrollLog:   ScrollView
    private lateinit var spWidth:     Spinner
    private lateinit var spHeight:    Spinner
    private lateinit var spPadding:   Spinner
    private lateinit var spBtDevices:  Spinner
    private lateinit var tvResInfo:   TextView

    private var isStreaming = false

    private val logListener: (LogEntry) -> Unit = { entry ->
        appendLog(entry)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.init(this)
        DebugLogger.initFile(getExternalFilesDir(null))
        super.onCreate(savedInstanceState)
        buildUi()
        updateBluetoothDevicesSpinner()
        DebugLogger.addListener(logListener)

        val savedCrash = CrashHandler.getSavedCrash(this)
        if (savedCrash != null) {
            log(LogLevel.ERROR, "🔥 CİHAZDA ÇÖKME TESPİT EDİLDİ:")
            savedCrash.lines().forEach { line ->
                if (line.isNotBlank()) {
                    log(LogLevel.ERROR, "   $line")
                }
            }
            log(LogLevel.ERROR, "─────────────────────────────────")
        }

        log(LogLevel.INFO, "🏍️ KoveMirror v1.0 başlatıldı")
        log(LogLevel.INFO, "─────────────────────────────────")
        refreshNetwork()
        log(LogLevel.INFO, "─────────────────────────────────")
        log(LogLevel.INFO, "1. Motosiklet WiFi'sine bağlanın")
        log(LogLevel.INFO, "2. Telefon IP'si yukarıda görünecek")
        log(LogLevel.INFO, "3. 'Başlat' butonuna basın")
        log(LogLevel.INFO, "4. TFT bağlanınca debug log dolacak")
        log(LogLevel.INFO, "─────────────────────────────────")

        checkBluetoothPermissions()
        requestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshNetwork()
    }

    override fun onDestroy() {
        DebugLogger.removeListener(logListener)
        super.onDestroy()
    }

    // ─── UI builder (programmatic) ───────────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(16, 16, 16, 16)
        }

        // ── Header ──
        root.addView(card("#1A1A2E") {
            addView(label("🏍️ KoveMirror", 20f, Color.WHITE, Typeface.BOLD))
            addView(label("Kove TFT Tam Ekran Yansıtma", 12f, Color.parseColor("#7777AA")))
        })

        // ── Network info ──
        tvIp      = label("0.0.0.0",    16f, Color.parseColor("#4CAF50"), Typeface.BOLD)
        tvSsid    = label("WiFi bekleniyor...", 12f, Color.parseColor("#AAAAAA"))
        tvGateway = label("Gateway: --", 11f, Color.parseColor("#666666"))
        tvStatus  = label("⏸ Hazır", 12f, Color.parseColor("#AAAAAA"))

        root.addView(marginTop(card("#0F2010") {
            addView(label("📱 Telefon IP (port 17818'de dinliyor):", 11f, Color.parseColor("#888888")))
            addView(tvIp)
            addView(tvSsid)
            addView(tvGateway)
            addView(label("↑ TFT bağlanacak adres", 10f, Color.parseColor("#555555")))
        }, 8))

        // ── Bluetooth (TFT BLE Activation) ──
        spBtDevices = Spinner(this)
        root.addView(marginTop(card("#101A2E") {
            addView(label("🔵 TFT Bluetooth Cihazı (WiFi'yi tetiklemek için):", 11f, Color.parseColor("#888888")))
            addView(spBtDevices)
            addView(label("Eşleşmiş cihazlardan motosikletinizin TFT ekranını seçin", 10f, Color.parseColor("#555555")))
        }, 8))

        // ── Resolution & Padding ──
        val widths  = arrayOf("480","512","528","544","560","576","592","600","640","800","1280")
        val heights = arrayOf("800","864","928","960","1024","1080","1120","1184","1200","1280","1284","480","720")
        val paddings = arrayOf("0 px", "40 px", "60 px", "80 px", "100 px", "120 px", "140 px", "160 px")
        
        spWidth   = Spinner(this).also { it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, widths) }
        spHeight  = Spinner(this).also { it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, heights) }
        spPadding = Spinner(this).also { it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, paddings) }
        
        // 600 (Index 7) ve 1024 (Index 4) dikey ekranı için varsayılan seçilsin
        spWidth.setSelection(7)
        spHeight.setSelection(4)
        // 100 px dikey boşluk varsayılan seçilsin (Index 4)
        spPadding.setSelection(4)
        
        spPadding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val padStr = parent?.getItemAtPosition(position)?.toString()?.replace(" px", "")?.trim() ?: "0"
                val pad = padStr.toIntOrNull() ?: 0
                if (isStreaming) {
                    MirrorService.updatePadding(pad)
                    log(LogLevel.INFO, "🔄 Kenar boşluğu anlık olarak güncellendi: ${pad}px")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        tvResInfo = label("Dikey 600×1024 (shape=6) — Kove TFT standart dikey", 10f, Color.parseColor("#555555"))

        root.addView(marginTop(card("#0D1020") {
            addView(label("🖥️ TFT Çözünürlük ve Kenar Boşluğu Ayarları:", 11f, Color.parseColor("#888888")))
            
            // Çözünürlük Satırı
            val resRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            resRow.addView(label("Çözünürlük: ", 12f, Color.WHITE))
            resRow.addView(spWidth)
            resRow.addView(label(" × ", 12f, Color.WHITE))
            resRow.addView(spHeight)
            addView(resRow)
            
            // Padding Satırı
            val padRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            padRow.addView(label("Kenar Boşluğu (Top/Bottom): ", 12f, Color.WHITE))
            padRow.addView(spPadding)
            addView(padRow)
            
            addView(tvResInfo)
        }, 8))

        // ── Start/Stop button ──
        btnStart = Button(this).apply {
            text = "▶  Yansıtmayı Başlat"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130
            ).also { it.topMargin = 12 }
            setOnClickListener { onStartStopClick() }
        }
        root.addView(btnStart)

        // ── Status + log header ──
        root.addView(marginTop(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("📋 Debug Log", 12f, Color.parseColor("#888888")).also {
                (it.layoutParams as LinearLayout.LayoutParams).weight = 1f
                it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(tvStatus)
        }, 10))

        // ── Legend ──
        root.addView(label(
            "🟢Başarı  🔴Hata  🟠Uyarı  🔵Veri  🟣💓Heartbeat  ⚪Bilgi",
            9f, Color.parseColor("#444444")
        ))

        // ── Log view ──
        tvLog = TextView(this).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize    = 10.5f
            typeface    = Typeface.MONOSPACE
            isVerticalScrollBarEnabled = true
            movementMethod = ScrollingMovementMethod()
            setPadding(8, 8, 8, 8)
        }
        scrollLog = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#060606"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).also { it.topMargin = 4 }
            addView(tvLog)
        }
        root.addView(scrollLog)

        setContentView(root)
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun card(bg: String, init: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            init()
        }

    private fun marginTop(v: LinearLayout, dp: Int): LinearLayout {
        (v.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp.dpToPx() ?: 0
        return v
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            this.text = text
            textSize  = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

    // ─── Network refresh ─────────────────────────────────────────

    private fun refreshNetwork() {
        val ip   = NetworkUtils.getWifiIpAddress(this)
        val ssid = NetworkUtils.getWifiSsid(this)
        val gw   = NetworkUtils.getGatewayAddress(this)

        tvIp.text      = ip
        tvSsid.text    = "📶 $ssid"
        tvGateway.text = "🏍️ Gateway (TBox): $gw"

        if (ip.startsWith("0.0") || ip == "null") {
            tvIp.setTextColor(Color.parseColor("#EF5350"))
            tvStatus.text = "⚠️ WiFi bağlı değil"
            log(LogLevel.WARNING, "⚠️ WiFi bağlı değil — motosiklet hotspot'una bağlanın")
        } else {
            tvIp.setTextColor(Color.parseColor("#4CAF50"))
            tvStatus.text = "✅ WiFi: $ssid"
            log(LogLevel.SUCCESS, "📡 Telefon IP: $ip")
            log(LogLevel.INFO,    "🏍️ Gateway   : $gw")
        }
    }

    // ─── Start/Stop ──────────────────────────────────────────────

    private fun onStartStopClick() {
        if (!isStreaming) {
            val wStr = spWidth.selectedItem?.toString() ?: "600"
            val hStr = spHeight.selectedItem?.toString() ?: "1024"
            val w = wStr.toIntOrNull() ?: 600
            val h = hStr.toIntOrNull() ?: 1024
            
            val padStr = spPadding.selectedItem?.toString()?.replace(" px", "")?.trim() ?: "100"
            val pad = padStr.toIntOrNull() ?: 100
            
            MirrorService.TFT_WIDTH   = w
            MirrorService.TFT_HEIGHT  = h
            MirrorService.TFT_PADDING = pad
            
            tvResInfo.text = "Hedef: ${w}×${h} | Boşluk: ${pad}px"
            log(LogLevel.INFO, "🖥️ TFT hedef çözünürlüğü: ${w}×${h} | Kenar Boşluğu: ${pad}px")
            requestScreenCapture()
        } else {
            stopMirroring()
        }
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        log(LogLevel.INFO, "🔐 Ekran yakalama izni isteniyor...")
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                isStreaming = true
                btnStart.text = "⏹  Durdur"
                btnStart.setBackgroundColor(Color.parseColor("#B71C1C"))
                tvStatus.text = "🔴 Stream aktif"
                log(LogLevel.SUCCESS, "✅ İzin alındı — MirrorService başlatılıyor")
                try {
                    MirrorService.startService(this, resultCode, data)
                } catch (e: Exception) {
                    log(LogLevel.ERROR, "❌ Servis başlatılamadı: ${e.message}")
                    Toast.makeText(this, "Servis başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    isStreaming = false
                    btnStart.text = "▶  Yansıtmayı Başlat"
                    btnStart.setBackgroundColor(Color.parseColor("#2E7D32"))
                    tvStatus.text = "⏹ Durduruldu"
                }
            } else {
                log(LogLevel.ERROR, "❌ Ekran yakalama izni reddedildi")
            }
        }
    }

    private fun stopMirroring() {
        isStreaming = false
        btnStart.text = "▶  Yansıtmayı Başlat"
        btnStart.setBackgroundColor(Color.parseColor("#2E7D32"))
        tvStatus.text = "⏹ Durduruldu"
        MirrorService.stopService(this)
        log(LogLevel.INFO, "⏹️ Kullanıcı tarafından durduruldu")
    }

    // ─── Log append ──────────────────────────────────────────────

    private fun appendLog(entry: LogEntry) {
        val color = when (entry.level) {
            LogLevel.SUCCESS   -> "#4CAF50"
            LogLevel.ERROR     -> "#EF5350"
            LogLevel.WARNING   -> "#FF9800"
            LogLevel.DATA      -> "#29B6F6"
            LogLevel.HEARTBEAT -> "#CE93D8"
            LogLevel.INFO      -> "#CCCCCC"
        }
        val line = "[${entry.timestamp}] ${entry.message}\n"

        val old = tvLog.text?.toString() ?: ""
        // Max 300 satır tut
        val lines = old.lines()
        val trimmed = if (lines.size > 280) lines.takeLast(250).joinToString("\n") + "\n" else old

        // Basit renkli append — tek renk (son satır rengi)
        tvLog.setTextColor(Color.parseColor(color))
        tvLog.text = trimmed + line

        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun log(level: LogLevel, msg: String) = DebugLogger.log(level, msg)

    // ─── Permissions ─────────────────────────────────────────────

    // ─── Permissions & Bluetooth Helpers ─────────────────────────

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION
                )
            }
        }
    }

    private fun checkBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 999)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateBluetoothDevicesSpinner() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("Bluetooth Desteklenmiyor"))
            return
        }
        
        if (!adapter.isEnabled) {
            spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("Bluetooth Kapalı"))
            return
        }
        
        val bonded = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            log(LogLevel.WARNING, "⚠️ Bluetooth izinleri eksik, cihaz listelenemedi")
            spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("İzin Eksik"))
            return
        }
        
        val names = bonded.map { "${it.name} (${it.address})" }.toMutableList()
        if (names.isEmpty()) {
            names.add("Eşleşmiş cihaz yok")
        }
        
        spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names.toTypedArray())
        
        // Önceki seçimi yükle
        val savedMac = getSharedPreferences("kove_prefs", MODE_PRIVATE).getString("bt_mac", "")
        if (!savedMac.isNullOrEmpty()) {
            val idx = bonded.indexOfFirst { it.address == savedMac }
            if (idx >= 0) {
                spBtDevices.setSelection(idx)
            }
        }
        
        spBtDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < bonded.size) {
                    val dev = bonded.toList()[position]
                    getSharedPreferences("kove_prefs", MODE_PRIVATE).edit().putString("bt_mac", dev.address).apply()
                    log(LogLevel.INFO, "🔗 Seçilen Bluetooth: ${dev.name} (${dev.address})")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 999) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                log(LogLevel.SUCCESS, "✅ Bluetooth izinleri alındı")
                updateBluetoothDevicesSpinner()
            } else {
                log(LogLevel.WARNING, "⚠️ Bazı Bluetooth izinleri reddedildi")
            }
        }
    }
}
