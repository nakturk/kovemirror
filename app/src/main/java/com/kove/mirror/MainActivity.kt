package com.kove.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kove.mirror.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_SCREEN_CAPTURE = 100
        const val REQ_NOTIFICATION   = 101
    }

    private lateinit var binding: ActivityMainBinding
    private var isStreaming = false
    private var titleClickCount = 0
    private var titleClickTime = 0L

    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.init(this)
        DebugLogger.initFile(getExternalFilesDir(null))
        super.onCreate(savedInstanceState)
        
        // Ekranın kapanmasını engelle (Keep screen on)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        checkSecurityConstraints()

        setupBluetoothSpinner()
        setupButtons()

        checkBluetoothPermissions()
        requestNotifPermission()

        DebugLogger.info(getString(R.string.log_app_started))
    }

    override fun onResume() {
        super.onResume()
        refreshWifiStatus()
    }

    // ─── Setup ───────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener { onStartStopClick() }
        binding.btnShareLogs.setOnClickListener { shareLogs() }

        // Setup Debug Terminal
        DebugLogger.addListener { entry ->
            uiHandler.post {
                val currentText = binding.tvDebugLog.text.toString()
                val lines = currentText.split("\n")
                // Keep only last 100 lines to prevent memory issues
                val newText = if (lines.size > 100) {
                    lines.drop(lines.size - 100).joinToString("\n") + "\n[${entry.timestamp}] ${entry.message}"
                } else {
                    currentText + (if (currentText.isNotEmpty()) "\n" else "") + "[${entry.timestamp}] ${entry.message}"
                }
                binding.tvDebugLog.text = newText
                
                // Auto-scroll to bottom
                binding.svDebug.post {
                    binding.svDebug.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun shareLogs() {
        val logFile = java.io.File(getExternalFilesDir(null), "kove_mirror_log.txt")
        if (!logFile.exists()) {
            Toast.makeText(this, "Log dosyası bulunamadı / Log file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Logları Paylaş / Share Logs"))
        } catch (e: Exception) {
            Toast.makeText(this, "Log paylaşılırken hata oluştu: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── WiFi Status ─────────────────────────────────────────────

    private fun refreshWifiStatus() {
        val ip = NetworkUtils.getWifiIpAddress(this)
        val ssid = NetworkUtils.getWifiSsid(this)

        if (ip.startsWith("0.0") || ip == "null") {
            binding.tvStatus.text = getString(R.string.wifi_not_connected_status)
            binding.tvStatus.setTextColor(Color.parseColor("#EF5350"))
        } else if (!isStreaming) {
            binding.tvStatus.text = getString(R.string.wifi_connected_status, ssid)
            binding.tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        }
    }

    // ─── Start/Stop ──────────────────────────────────────────────

    private fun onStartStopClick() {
        if (!isStreaming) {

            val ip = NetworkUtils.getWifiIpAddress(this)
            if (ip.startsWith("0.0") || ip == "null") {
                Toast.makeText(this, getString(R.string.wifi_required_toast), Toast.LENGTH_LONG).show()
                return
            }

            MirrorService.TFT_WIDTH   = 600
            MirrorService.TFT_HEIGHT  = 1024
            MirrorService.TFT_PADDING = 0

            DebugLogger.info(getString(R.string.log_target_resolution, 600, 1024, 0))
            requestScreenCapture()
        } else {
            stopMirroring()
        }
    }

    private fun checkSecurityConstraints() {
        val isRooted = SecurityManager.isDeviceRooted()
        val isDebugged = SecurityManager.isDebuggerAttached(this)
        
        if (isRooted || isDebugged) {
            AlertDialog.Builder(this)
                .setTitle("Güvenlik Uyarısı")
                .setMessage("Cihazınızda Root veya Debugger tespit edildi. Bu durum uygulamanın güvenlik ve abonelik altyapısını etkileyebilir. Uygulamayı kullanmaya devam edebilirsiniz ancak abonelikleriniz daha sık doğrulanacaktır.")
                .setPositiveButton("Anladım", null)
                .show()
        }
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        DebugLogger.info(getString(R.string.log_request_screen_capture))
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                isStreaming = true
                binding.btnStartStop.text = getString(R.string.btn_stop_mirroring)
                binding.btnStartStop.setBackgroundColor(Color.parseColor("#B71C1C"))
                binding.tvStatus.text = getString(R.string.status_stream_active)
                binding.tvStatus.setTextColor(Color.parseColor("#EF5350"))
                DebugLogger.success(getString(R.string.log_permission_granted))
                try {
                    MirrorService.startService(this, resultCode, data)
                } catch (e: Exception) {
                    val errMsg = getString(R.string.log_service_start_failed, e.message ?: "")
                    DebugLogger.error(errMsg)
                    Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                    resetToStopped()
                }
            } else {
                DebugLogger.error(getString(R.string.log_screen_capture_denied))
            }
        }
    }

    private fun stopMirroring() {
        MirrorService.stopService(this)
        DebugLogger.info(getString(R.string.log_stopped_by_user))
        resetToStopped()
    }

    private fun resetToStopped() {
        isStreaming = false
        binding.btnStartStop.text = getString(R.string.btn_start_mirroring)
        binding.btnStartStop.setBackgroundColor(Color.parseColor("#2E7D32"))
        binding.tvStatus.text = getString(R.string.status_stopped)
        binding.tvStatus.setTextColor(Color.parseColor("#AAAAAA"))
    }

    // ─── Bluetooth Spinner ───────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun setupBluetoothSpinner() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            binding.spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf(getString(R.string.bt_not_supported)))
            return
        }

        if (!adapter.isEnabled) {
            binding.spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf(getString(R.string.bt_off)))
            return
        }

        val bonded = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            DebugLogger.warning(getString(R.string.log_bt_permissions_missing))
            binding.spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf(getString(R.string.bt_permission_missing)))
            return
        }

        val names = bonded.map { "${it.name} (${it.address})" }.toMutableList()
        if (names.isEmpty()) {
            names.add(getString(R.string.bt_no_paired_devices))
        }

        binding.spBtDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names.toTypedArray())

        // Load saved selection
        val savedMac = getSharedPreferences("kove_prefs", MODE_PRIVATE).getString("bt_mac", "")
        if (!savedMac.isNullOrEmpty()) {
            val idx = bonded.indexOfFirst { it.address == savedMac }
            if (idx >= 0) {
                binding.spBtDevices.setSelection(idx)
            }
        }

        binding.spBtDevices.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < bonded.size) {
                    val dev = bonded.toList()[position]
                    getSharedPreferences("kove_prefs", MODE_PRIVATE).edit().putString("bt_mac", dev.address).apply()
                    DebugLogger.info(getString(R.string.log_selected_bt, dev.name ?: "Unknown", dev.address))
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // ─── Permissions ─────────────────────────────────────────────

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 999) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                DebugLogger.success(getString(R.string.log_bt_permissions_granted))
                setupBluetoothSpinner()
            } else {
                DebugLogger.warning(getString(R.string.log_bt_permissions_denied))
            }
        }
    }
}
