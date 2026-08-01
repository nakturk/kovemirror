package com.kove.mirror

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Floating system overlay service that renders the handlebar action mode selector.
 * Uses WindowManager with TYPE_APPLICATION_OVERLAY to draw over other apps.
 *
 * Requires: Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 */
class HandlebarOverlayService : Service() {

    companion object {
        @Volatile var instance: HandlebarOverlayService? = null
            private set

        private const val AUTO_HIDE_DELAY_MS = 5000L

        fun startService(context: Context) {
            val intent = Intent(context, HandlebarOverlayService::class.java)
            context.startService(intent)
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, HandlebarOverlayService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var menuContainer: LinearLayout? = null
    private var activeModeIndicator: TextView? = null
    private var indicatorView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideMenu() }
    private val allModes = HandlebarActionMode.entries.toList()
    private val menuItemViews = mutableListOf<TextView>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Register handlebar callbacks
        HandlebarKeyManager.onOverlayToggle = { show ->
            if (show) showMenu() else hideMenu()
        }
        HandlebarKeyManager.onHighlightChanged = { idx ->
            updateHighlight(idx)
            resetAutoHide()
        }
        HandlebarKeyManager.onModeActivated = { mode ->
            showActiveModeIndicator(mode)
        }
        HandlebarKeyManager.onActionDispatch = { mode, key ->
            executeAction(mode, key)
        }

        // Show persistent small active-mode indicator
        createIndicatorView()

        DebugLogger.info("🎮 HandlebarOverlayService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        HandlebarKeyManager.onOverlayToggle = null
        HandlebarKeyManager.onHighlightChanged = null
        HandlebarKeyManager.onModeActivated = null
        HandlebarKeyManager.onActionDispatch = null
        removeOverlayView()
        removeIndicatorView()
        instance = null
        DebugLogger.info("🎮 HandlebarOverlayService stopped")
        super.onDestroy()
    }

    // ─── Overlay Menu ─────────────────────────────────────────

    private fun showMenu() {
        mainHandler.post {
            if (overlayView != null) {
                // Already showing, just update
                updateHighlight(HandlebarKeyManager.highlightedIndex)
                overlayView?.visibility = View.VISIBLE
                resetAutoHide()
                return@post
            }
            createOverlayView()
            resetAutoHide()
        }
    }

    private fun hideMenu() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoHideRunnable)
            overlayView?.visibility = View.GONE
            HandlebarKeyManager.isOverlayMenuOpen.let {
                if (it) {
                    // Force close the overlay state
                    // (handlebar key manager also sets this, but just in case)
                }
            }
        }
    }

    private fun resetAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun createOverlayView() {
        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
        }

        // Root container
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1A1A2E"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Title
        val titleTv = TextView(this).apply {
            text = "🎮 Handlebar Mode"
            setTextColor(Color.parseColor("#AAAACC"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout.addView(titleTv)

        // Menu items
        menuItemViews.clear()
        for ((idx, mode) in allModes.withIndex()) {
            val itemTv = TextView(this).apply {
                text = "  ${mode.displayName}"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(Color.TRANSPARENT)
            }
            menuItemViews.add(itemTv)
            rootLayout.addView(itemTv)
        }

        // Active UP/DOWN mode indicator at bottom
        val activeModeTv = TextView(this).apply {
            text = "✅ Active ▲▼: ${HandlebarKeyManager.activeUpDownMode.displayName}"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }
        rootLayout.addView(activeModeTv)

        menuContainer = rootLayout

        // WindowManager params
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(70)
        }

        try {
            windowManager?.addView(rootLayout, params)
            overlayView = rootLayout
            updateHighlight(HandlebarKeyManager.highlightedIndex)
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to add overlay view: ${e.message}")
        }
    }

    private fun updateHighlight(index: Int) {
        mainHandler.post {
            for ((idx, tv) in menuItemViews.withIndex()) {
                val mode = allModes[idx]
                val badge = if (mode.isEntAction) " [ENT]" else ""
                if (idx == index) {
                    tv.setBackgroundColor(Color.parseColor("#3366FF"))
                    tv.setTextColor(Color.WHITE)
                    tv.typeface = Typeface.DEFAULT_BOLD
                    tv.text = "▶ ${mode.displayName}$badge"
                } else if (!mode.isEntAction && mode == HandlebarKeyManager.activeUpDownMode) {
                    tv.setBackgroundColor(Color.TRANSPARENT)
                    tv.setTextColor(Color.parseColor("#4CAF50"))
                    tv.typeface = Typeface.DEFAULT
                    tv.text = "  ${mode.displayName} ✓"
                } else {
                    tv.setBackgroundColor(Color.TRANSPARENT)
                    tv.setTextColor(Color.WHITE)
                    tv.typeface = Typeface.DEFAULT
                    tv.text = "  ${mode.displayName}$badge"
                }
            }
        }
    }

    private fun removeOverlayView() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        menuContainer = null
        menuItemViews.clear()
    }

    // ─── Small Active Mode Indicator (always visible) ─────────

    private fun createIndicatorView() {
        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
        }

        val tv = TextView(this).apply {
            text = "🎮 ▲▼: ${HandlebarKeyManager.activeUpDownMode.displayName}"
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 10f
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        activeModeIndicator = tv

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(4)
        }

        try {
            windowManager?.addView(tv, params)
            indicatorView = tv
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to add indicator: ${e.message}")
        }
    }

    private fun showActiveModeIndicator(mode: HandlebarActionMode) {
        mainHandler.post {
            activeModeIndicator?.text = "🎮 ▲▼: ${mode.displayName}"
            Toast.makeText(this, "✅ Gidon Tuşları: ${mode.displayNameTr}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeIndicatorView() {
        try {
            indicatorView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        indicatorView = null
        activeModeIndicator = null
    }

    // ─── Action Executor ────────────────────────────────────────

    private fun executeAction(mode: HandlebarActionMode, key: HandlebarKey) {
        val svc = KoveAccessibilityService.instance

        when (mode) {
            HandlebarActionMode.MY_LOCATION -> {
                DebugLogger.info("🎮 Action: Get back to my location")
                Toast.makeText(this, "📍 Konumuma Dön", Toast.LENGTH_SHORT).show()
                val intent = Intent("com.kove.mirror.ACTION_MY_LOCATION")
                sendBroadcast(intent)
            }
            HandlebarActionMode.ZOOM -> {
                when (key) {
                    HandlebarKey.UP -> {
                        DebugLogger.info("🎮 Action: Zoom In")
                        svc?.performZoom(zoomIn = true)
                    }
                    HandlebarKey.DOWN -> {
                        DebugLogger.info("🎮 Action: Zoom Out")
                        svc?.performZoom(zoomIn = false)
                    }
                    HandlebarKey.ENTER -> {
                        val intent = Intent("com.kove.mirror.ACTION_MY_LOCATION")
                        sendBroadcast(intent)
                    }
                    else -> {}
                }
            }
            HandlebarActionMode.PAN_VERTICAL -> {
                when (key) {
                    HandlebarKey.UP -> {
                        DebugLogger.info("🎮 Action: Pan Up")
                        svc?.performPan("up")
                    }
                    HandlebarKey.DOWN -> {
                        DebugLogger.info("🎮 Action: Pan Down")
                        svc?.performPan("down")
                    }
                    HandlebarKey.ENTER -> {
                        val intent = Intent("com.kove.mirror.ACTION_MY_LOCATION")
                        sendBroadcast(intent)
                    }
                    else -> {}
                }
            }
            HandlebarActionMode.PAN_HORIZONTAL -> {
                when (key) {
                    HandlebarKey.UP -> {
                        DebugLogger.info("🎮 Action: Pan Left")
                        svc?.performPan("left")
                    }
                    HandlebarKey.DOWN -> {
                        DebugLogger.info("🎮 Action: Pan Right")
                        svc?.performPan("right")
                    }
                    HandlebarKey.ENTER -> {
                        val intent = Intent("com.kove.mirror.ACTION_MY_LOCATION")
                        sendBroadcast(intent)
                    }
                    else -> {}
                }
            }
            HandlebarActionMode.MEDIA_TRACK -> {
                when (key) {
                    HandlebarKey.UP -> {
                        DebugLogger.info("🎮 Action: Next Track")
                        svc?.performMediaNext()
                    }
                    HandlebarKey.DOWN -> {
                        DebugLogger.info("🎮 Action: Previous Track")
                        svc?.performMediaPrevious()
                    }
                    HandlebarKey.ENTER -> {
                        DebugLogger.info("🎮 Action: Play/Pause Toggle")
                        svc?.performMediaPlayPause()
                    }
                    else -> {}
                }
            }
            HandlebarActionMode.VOLUME -> {
                when (key) {
                    HandlebarKey.UP -> {
                        DebugLogger.info("🎮 Action: Volume Up")
                        svc?.performVolumeUp()
                    }
                    HandlebarKey.DOWN -> {
                        DebugLogger.info("🎮 Action: Volume Down")
                        svc?.performVolumeDown()
                    }
                    HandlebarKey.ENTER -> {
                        DebugLogger.info("🎮 Action: Play/Pause Toggle")
                        svc?.performMediaPlayPause()
                    }
                    else -> {}
                }
            }
            HandlebarActionMode.PLAY -> {
                if (key == HandlebarKey.ENTER) {
                    DebugLogger.info("🎮 Action: Media Play")
                    svc?.performMediaPlay()
                }
            }
            HandlebarActionMode.PAUSE -> {
                if (key == HandlebarKey.ENTER) {
                    DebugLogger.info("🎮 Action: Media Pause")
                    svc?.performMediaPause()
                }
            }
            HandlebarActionMode.APP_SWITCH -> {
                when (key) {
                    HandlebarKey.UP -> {
                        DebugLogger.info("🎮 Action: Next App")
                        svc?.performAppSwitch(next = true)
                    }
                    HandlebarKey.DOWN -> {
                        DebugLogger.info("🎮 Action: Previous App")
                        svc?.performAppSwitch(next = false)
                    }
                    HandlebarKey.ENTER -> {
                        DebugLogger.info("🎮 Action: App Switcher")
                        svc?.performAppSwitch(next = true)
                    }
                    else -> {}
                }
            }
        }
    }
}
