package com.kove.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
        const val REQ_OVERLAY        = 102

        const val APP_MODE_MIRRORING        = 0
        const val APP_MODE_CONTROL_ONLY     = 1
    }

    private lateinit var binding: ActivityMainBinding
    private var isStreaming = false
    private var titleClickCount = 0
    private var titleClickTime = 0L
    private var currentAppMode = APP_MODE_MIRRORING

    private val uiHandler = Handler(Looper.getMainLooper())

    private val languageCodes = listOf("tr", "en", "el", "es")
    private val languageNames = listOf("Türkçe", "English", "Ελληνικά", "Español")

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.init(this)
        DebugLogger.initFile(getExternalFilesDir(null))
        DebugLogger.setContext(this)
        super.onCreate(savedInstanceState)
        
        // Ekranın kapanmasını engelle (Keep screen on)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        checkSecurityConstraints()

        setupLanguageButton()
        setupBluetoothButton()
        setupAboutButton()
        setupAppModeRadioGroup()
        setupDisplayModeSpinner()
        setupResolutionSpinner()
        setupButtons()
        setupPermissionButtons()
        setupHeaderEasterEgg()

        checkBluetoothPermissions()
        requestNotifPermission()

        DebugLogger.info(getString(R.string.log_app_started))
    }

    private fun setupHeaderEasterEgg() {
        binding.tvAppTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - titleClickTime > 2000) {
                titleClickCount = 0
            }
            titleClickTime = now
            titleClickCount++

            if (titleClickCount >= 10) {
                titleClickCount = 0
                val isVisible = binding.llTestButtons.visibility == View.VISIBLE
                if (isVisible) {
                    binding.llTestButtons.visibility = View.GONE
                    Toast.makeText(this, "🙈 Test Simülatörü Gizlendi", Toast.LENGTH_SHORT).show()
                } else {
                    binding.llTestButtons.visibility = View.VISIBLE
                    Toast.makeText(this, "🎮 Test Simülatörü Açıldı!", Toast.LENGTH_LONG).show()
                }
            } else if (titleClickCount >= 5) {
                val remaining = 10 - titleClickCount
                Toast.makeText(this, "🎮 Simülatör için $remaining tık kaldı...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (HandlebarKeyManager.processKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        refreshWifiStatus()
        updatePermissionButtonStates()
    }

    override fun onPause() {
        super.onPause()
    }

    // ─── Language Selector ───────────────────────────────────────

    private fun setupLanguageButton() {
        val currentLang = LocaleHelper.getSavedLanguage(this)
        val currentIdx = languageCodes.indexOf(currentLang).let { if (it >= 0) it else 0 }
        val currentLangName = languageNames.getOrNull(currentIdx) ?: "English"
        binding.btnSelectLanguage.text = getString(R.string.btn_select_language, currentLangName)

        binding.btnSelectLanguage.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.label_language)
                .setItems(languageNames.toTypedArray()) { _, which ->
                    val selectedLang = languageCodes[which]
                    if (selectedLang != LocaleHelper.getSavedLanguage(this@MainActivity)) {
                        LocaleHelper.setLocale(this@MainActivity, selectedLang)
                        recreate()
                    }
                }
                .show()
        }
    }

    // ─── App Mode Selector ──────────────────────────────────────

    private fun setupAppModeRadioGroup() {
        val savedMode = getSharedPreferences("kove_prefs", MODE_PRIVATE).getInt("app_mode", APP_MODE_MIRRORING)
        currentAppMode = if (savedMode == APP_MODE_CONTROL_ONLY) APP_MODE_CONTROL_ONLY else APP_MODE_MIRRORING
        if (currentAppMode == APP_MODE_CONTROL_ONLY) {
            binding.rbModeControlOnly.isChecked = true
        } else {
            binding.rbModeMirroring.isChecked = true
        }
        updateUiForMode(currentAppMode)

        binding.rgAppMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.rbModeControlOnly) APP_MODE_CONTROL_ONLY else APP_MODE_MIRRORING
            currentAppMode = newMode
            getSharedPreferences("kove_prefs", MODE_PRIVATE).edit().putInt("app_mode", newMode).apply()
            updateUiForMode(newMode)
        }
    }

    private fun updateUiForMode(mode: Int) {
        when (mode) {
            APP_MODE_MIRRORING -> {
                binding.spDisplayMode.isEnabled = true
                binding.spTftResolution.isEnabled = true
                binding.spDisplayMode.alpha = 1.0f
                binding.spTftResolution.alpha = 1.0f
                binding.llPermissions.visibility = View.GONE
                binding.llTestButtons.visibility = View.GONE
                if (!isStreaming) {
                    binding.btnStartStop.text = getString(R.string.btn_start_mirroring)
                }
            }
            APP_MODE_CONTROL_ONLY -> {
                binding.spDisplayMode.isEnabled = false
                binding.spTftResolution.isEnabled = false
                binding.spDisplayMode.alpha = 0.4f
                binding.spTftResolution.alpha = 0.4f
                binding.llPermissions.visibility = View.VISIBLE
                if (!isStreaming) {
                    binding.btnStartStop.text = getString(R.string.btn_start_controller)
                }
                updatePermissionButtonStates()
            }
        }
    }

    // ─── Permission Buttons ─────────────────────────────────────

    private fun setupPermissionButtons() {
        binding.btnOverlayPermission.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, REQ_OVERLAY)
            } else {
                Toast.makeText(this, "✅ Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAccessibilityPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, getString(R.string.toast_enable_accessibility), Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePermissionButtonStates() {
        if (currentAppMode == APP_MODE_CONTROL_ONLY) {
            // Overlay permission
            if (Settings.canDrawOverlays(this)) {
                binding.btnOverlayPermission.setBackgroundColor(Color.parseColor("#2E7D32"))
                binding.btnOverlayPermission.text = getString(R.string.btn_overlay_granted)
            } else {
                binding.btnOverlayPermission.setBackgroundColor(Color.parseColor("#FF6F00"))
                binding.btnOverlayPermission.text = getString(R.string.btn_overlay_permission)
            }

            // Accessibility service
            if (KoveAccessibilityService.isEnabled(this)) {
                binding.btnAccessibilityPermission.setBackgroundColor(Color.parseColor("#2E7D32"))
                binding.btnAccessibilityPermission.text = getString(R.string.btn_accessibility_granted)
            } else {
                binding.btnAccessibilityPermission.setBackgroundColor(Color.parseColor("#FF6F00"))
                binding.btnAccessibilityPermission.text = getString(R.string.btn_accessibility_permission)
            }
        }
    }

    private fun setupAboutButton() {
        binding.btnAbout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.about_dialog_title)
                .setMessage(R.string.about_developer_credit)
                .setPositiveButton(R.string.btn_ok, null)
                .show()
        }
    }

    // ─── Setup ───────────────────────────────────────────────────

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener { onStartStopClick() }
        binding.btnTftPadding.setOnClickListener { showTftPaddingDialog() }
        binding.btnShareLogs.setOnClickListener { shareLogs() }
        binding.btnOpenMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        binding.btnToggleDebug.setOnClickListener {
            val isVisible = binding.svDebug.visibility == View.VISIBLE
            if (isVisible) {
                binding.svDebug.visibility = View.GONE
                binding.btnToggleDebug.text = getString(R.string.btn_toggle_debug_show)
            } else {
                binding.svDebug.visibility = View.VISIBLE
                binding.btnToggleDebug.text = getString(R.string.btn_toggle_debug_hide)
                binding.svDebug.post {
                    binding.svDebug.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        binding.svDebug.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        // Test Handlebar Simulation Buttons
        binding.btnTestUp.setOnClickListener {
            HandlebarKeyManager.dispatchKey(HandlebarKey.UP)
        }
        binding.btnTestEnt.setOnClickListener {
            HandlebarKeyManager.dispatchKey(HandlebarKey.ENTER)
        }
        binding.btnTestDown.setOnClickListener {
            HandlebarKeyManager.dispatchKey(HandlebarKey.DOWN)
        }

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
                
                // Auto-scroll to bottom if visible
                if (binding.svDebug.visibility == View.VISIBLE) {
                    binding.svDebug.post {
                        binding.svDebug.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    private fun shareLogs() {
        val logFile = java.io.File(getExternalFilesDir(null), "kove_mirror_log.txt")
        if (!logFile.exists()) {
            Toast.makeText(this, getString(R.string.log_file_not_found), Toast.LENGTH_SHORT).show()
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
            startActivity(Intent.createChooser(intent, getString(R.string.share_logs_title)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.log_share_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
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

    private data class TftPresetOption(
        val titleResId: Int,
        val width: Int,
        val height: Int
    )

    private val tftPresets = listOf(
        TftPresetOption(R.string.preset_600x1024, 600, 1024),
        TftPresetOption(R.string.preset_480x800, 480, 800),
        TftPresetOption(R.string.preset_640x1284, 640, 1284),
        TftPresetOption(R.string.preset_1280x720, 1280, 720),
        TftPresetOption(R.string.preset_800x800, 800, 800)
    )

    // ─── Start/Stop ──────────────────────────────────────────────

    private fun onStartStopClick() {
        if (!isStreaming) {
            if (currentAppMode == APP_MODE_CONTROL_ONLY) {
                // Control Only Mode — no screen capture or motorcycle WiFi required
                startControlOnly()
            } else {
                // Mirroring Mode — requires motorcycle WiFi
                val ip = NetworkUtils.getWifiIpAddress(this)
                if (ip.startsWith("0.0") || ip == "null") {
                    Toast.makeText(this, getString(R.string.wifi_required_toast), Toast.LENGTH_LONG).show()
                    return
                }

                val presetIdx = binding.spTftResolution.selectedItemPosition.coerceIn(0, tftPresets.size - 1)
                val selectedPreset = tftPresets[presetIdx]

                val modeIdx = binding.spDisplayMode.selectedItemPosition.coerceIn(0, 2)
                val selectedMode = when (modeIdx) {
                    1 -> DisplayMode.FIT
                    2 -> DisplayMode.STRETCH
                    else -> DisplayMode.CENTER_CROP
                }

                val ratio = getPhoneAspectRatio()

                val prefs = getSharedPreferences("kove_prefs", MODE_PRIVATE)
                val topDp = prefs.getInt("tft_top_padding_dp", 0)
        val bottomDp = prefs.getInt("tft_bottom_padding_dp", 0)

        MirrorService.TFT_WIDTH          = selectedPreset.width
        MirrorService.TFT_HEIGHT         = selectedPreset.height
        MirrorService.DISPLAY_MODE       = selectedMode
        MirrorService.PHONE_ASPECT_RATIO = ratio
        MirrorService.TFT_PADDING        = topDp
        MirrorService.TFT_TOP_PADDING_DP    = topDp
        MirrorService.TFT_BOTTOM_PADDING_DP = bottomDp

        DebugLogger.info(
            "🖥️ Target: ${selectedPreset.width}×${selectedPreset.height} | " +
            "Mode: ${selectedMode.name} | Ratio: %.3f | Padding: top=${topDp}dp, bottom=${bottomDp}dp".format(ratio)
        )
        requestScreenCapture()
    }
} else {
    stopService()
}
}

private fun showTftPaddingDialog() {
    val prefs = getSharedPreferences("kove_prefs", MODE_PRIVATE)
    var currentTop = prefs.getInt("tft_top_padding_dp", 0)
    var currentBottom = prefs.getInt("tft_bottom_padding_dp", 0)

    val view = layoutInflater.inflate(R.layout.dialog_tft_padding, null)
    val dialog = AlertDialog.Builder(this)
        .setView(view)
        .setCancelable(true)
        .create()

    val tvTopLabel = view.findViewById<TextView>(R.id.tvTopLabel)
    val btnTopMinus = view.findViewById<Button>(R.id.btnTopMinus)
    val btnTopPlus = view.findViewById<Button>(R.id.btnTopPlus)
    val seekBarTop = view.findViewById<SeekBar>(R.id.seekBarTop)

    val tvBottomLabel = view.findViewById<TextView>(R.id.tvBottomLabel)
    val btnBottomMinus = view.findViewById<Button>(R.id.btnBottomMinus)
    val btnBottomPlus = view.findViewById<Button>(R.id.btnBottomPlus)
    val seekBarBottom = view.findViewById<SeekBar>(R.id.seekBarBottom)

    val btnReset = view.findViewById<Button>(R.id.btnResetPadding)
    val btnSave = view.findViewById<Button>(R.id.btnSavePadding)

    fun updateUI() {
        currentTop = currentTop.coerceIn(0, 200)
        currentBottom = currentBottom.coerceIn(0, 200)

        tvTopLabel.text = getString(R.string.tft_fit_top_label, currentTop)
        tvBottomLabel.text = getString(R.string.tft_fit_bottom_label, currentBottom)

        seekBarTop.progress = currentTop
        seekBarBottom.progress = currentBottom

        MirrorService.updatePadding(currentTop, currentBottom)
    }

    updateUI()

    btnTopMinus.setOnClickListener { currentTop -= 5; updateUI() }
    btnTopPlus.setOnClickListener { currentTop += 5; updateUI() }
    seekBarTop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) { currentTop = progress; updateUI() }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    })

    btnBottomMinus.setOnClickListener { currentBottom -= 5; updateUI() }
    btnBottomPlus.setOnClickListener { currentBottom += 5; updateUI() }
    seekBarBottom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) { currentBottom = progress; updateUI() }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    })

    btnReset.setOnClickListener {
        currentTop = 0
        currentBottom = 0
        updateUI()
    }

    btnSave.setOnClickListener {
        prefs.edit().apply {
            putInt("tft_top_padding_dp", currentTop)
            putInt("tft_bottom_padding_dp", currentBottom)
            apply()
        }
        Toast.makeText(this, getString(R.string.toast_tft_fit_saved), Toast.LENGTH_SHORT).show()
        dialog.dismiss()
    }

    dialog.show()
}

    private fun startControlOnly() {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_required_title))
                .setMessage(getString(R.string.overlay_permission_required_msg))
                .setPositiveButton(getString(R.string.btn_grant_permission)) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivityForResult(intent, REQ_OVERLAY)
                }
                .setNegativeButton(getString(R.string.map_btn_cancel), null)
                .show()
            return
        }

        // Warn if accessibility not enabled (but allow anyway)
        if (!KoveAccessibilityService.isEnabled(this)) {
            DebugLogger.warning("⚠️ Accessibility Service not enabled - gesture actions (zoom/pan) will not work")
        }

        isStreaming = true
        binding.btnStartStop.text = getString(R.string.btn_stop_controller)
        binding.btnStartStop.setBackgroundColor(Color.parseColor("#B71C1C"))
        binding.tvStatus.text = getString(R.string.status_control_active)
        binding.tvStatus.setTextColor(Color.parseColor("#FF9800"))

        DebugLogger.success("🎮 Control Only mode starting...")

        try {
            // Start HandlebarOverlayService
            HandlebarOverlayService.startService(this)

            // Start MirrorService in control-only mode
            MirrorService.startControlOnlyService(this)
        } catch (e: Exception) {
            val errMsg = getString(R.string.log_service_start_failed, e.message ?: "")
            DebugLogger.error(errMsg)
            Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
            resetToStopped()
        }
    }

    private fun setupDisplayModeSpinner() {
        val options = listOf(
            getString(R.string.mode_center_crop),
            getString(R.string.mode_fit),
            getString(R.string.mode_stretch)
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item_compact, options)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_compact)
        binding.spDisplayMode.adapter = adapter

        val savedModeIdx = getSharedPreferences("kove_prefs", MODE_PRIVATE).getInt("display_mode", 0)
        binding.spDisplayMode.setSelection(savedModeIdx.coerceIn(0, options.size - 1))

        binding.spDisplayMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                getSharedPreferences("kove_prefs", MODE_PRIVATE).edit().putInt("display_mode", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupResolutionSpinner() {
        val options = tftPresets.map { getString(it.titleResId) }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_compact, options)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_compact)
        binding.spTftResolution.adapter = adapter

        val savedPresetIdx = getSharedPreferences("kove_prefs", MODE_PRIVATE).getInt("tft_preset", 0)
        binding.spTftResolution.setSelection(savedPresetIdx.coerceIn(0, options.size - 1))

        binding.spTftResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                getSharedPreferences("kove_prefs", MODE_PRIVATE).edit().putInt("tft_preset", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getPhoneAspectRatio(): Float {
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                Pair(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
            }
            w.toFloat() / h.toFloat()
        } catch (e: Exception) {
            0.45f
        }
    }

    private fun checkSecurityConstraints() {
        val isRooted = SecurityManager.isDeviceRooted()
        val isDebugged = SecurityManager.isDebuggerAttached(this)
        
        if (isRooted || isDebugged) {
            AlertDialog.Builder(this)
                .setTitle(R.string.security_warning_title)
                .setMessage(R.string.security_warning_msg)
                .setPositiveButton(R.string.btn_ok, null)
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
                    // Also start overlay service for handlebar controls during mirroring
                    if (Settings.canDrawOverlays(this)) {
                        HandlebarOverlayService.startService(this)
                    }
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
        } else if (requestCode == REQ_OVERLAY) {
            updatePermissionButtonStates()
        }
    }

    private fun stopService() {
        MirrorService.stopService(this)
        HandlebarOverlayService.stopService(this)
        DebugLogger.info(getString(R.string.log_stopped_by_user))
        resetToStopped()
    }

    private fun resetToStopped() {
        isStreaming = false
        if (currentAppMode == APP_MODE_CONTROL_ONLY) {
            binding.btnStartStop.text = getString(R.string.btn_start_controller)
        } else {
            binding.btnStartStop.text = getString(R.string.btn_start_mirroring)
        }
        binding.btnStartStop.setBackgroundColor(Color.parseColor("#2E7D32"))
        binding.tvStatus.text = getString(R.string.status_stopped)
        binding.tvStatus.setTextColor(Color.parseColor("#AAAAAA"))
    }

    // ─── Bluetooth Selector ───────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun setupBluetoothButton() {
        fun updateBtButtonText() {
            val savedMac = getSharedPreferences("kove_prefs", MODE_PRIVATE).getString("bt_mac", "")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            var displayName = getString(R.string.bt_device_none)

            if (!savedMac.isNullOrEmpty() && adapter != null && adapter.isEnabled) {
                try {
                    val bonded = adapter.bondedDevices
                    val dev = bonded?.firstOrNull { it.address == savedMac }
                    if (dev != null) {
                        displayName = dev.name ?: savedMac
                    }
                } catch (_: SecurityException) {}
            }
            binding.btnSelectBtDevice.text = getString(R.string.btn_select_bt_device, displayName)
        }

        updateBtButtonText()

        binding.btnSelectBtDevice.setOnClickListener {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Toast.makeText(this, getString(R.string.bt_not_supported), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!adapter.isEnabled) {
                Toast.makeText(this, getString(R.string.bt_off), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val bonded = try {
                adapter.bondedDevices?.toList() ?: emptyList()
            } catch (e: SecurityException) {
                DebugLogger.warning(getString(R.string.log_bt_permissions_missing))
                Toast.makeText(this, getString(R.string.bt_permission_missing), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (bonded.isEmpty()) {
                Toast.makeText(this, getString(R.string.bt_no_paired_devices), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val names = bonded.map { "${it.name} (${it.address})" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle(R.string.label_select_bt_device)
                .setItems(names) { _, which ->
                    val dev = bonded[which]
                    getSharedPreferences("kove_prefs", MODE_PRIVATE).edit().putString("bt_mac", dev.address).apply()
                    DebugLogger.info(getString(R.string.log_selected_bt, dev.name ?: "Unknown", dev.address))
                    updateBtButtonText()
                }
                .show()
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
                setupBluetoothButton()
            } else {
                DebugLogger.warning(getString(R.string.log_bt_permissions_denied))
            }
        }
    }
}
