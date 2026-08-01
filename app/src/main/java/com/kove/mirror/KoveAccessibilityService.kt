package com.kove.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Android Accessibility Service that dispatches global gestures (zoom, pan, app switch)
 * based on handlebar key presses. Does NOT require root.
 *
 * User must enable this service in:
 *   Settings → Accessibility → KoveMirror Handlebar Controller
 */
class KoveAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: KoveAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expectedComponent = android.content.ComponentName(context, KoveAccessibilityService::class.java)
                val enabledServices = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val splitter = android.text.TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val componentNameString = splitter.next()
                    val enabledComponent = android.content.ComponentName.unflattenFromString(componentNameString)
                    if (enabledComponent != null && enabledComponent == expectedComponent) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }

        // Screen dimensions (updated on config change)
        private var screenWidth = 1080
        private var screenHeight = 1920

        fun updateScreenSize(w: Int, h: Int) {
            screenWidth = w
            screenHeight = h
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLogger.info("♿ KoveAccessibilityService connected")

        // Get screen size
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        DebugLogger.info("♿ Screen size: ${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used - we only need gesture dispatch capabilities
    }

    override fun onInterrupt() {
        DebugLogger.warning("♿ KoveAccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        DebugLogger.info("♿ KoveAccessibilityService destroyed")
        super.onDestroy()
    }

    // ─── Gesture Actions ───────────────────────────────────────

    /**
     * Simulate fine pinch-zoom gesture on screen center.
     * @param zoomIn true = zoom in (pinch outward), false = zoom out (pinch inward)
     */
    fun performZoom(zoomIn: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val startGap = 15f
        val endGap = (screenWidth / 22f).coerceIn(30f, 70f) // Small, fine pinch distance (~45px per finger)

        val path1 = Path()
        val path2 = Path()

        if (zoomIn) {
            // Pinch outward (zoom in): move fingers apart slightly
            path1.moveTo(cx - startGap, cy)
            path1.lineTo(cx - endGap, cy)
            path2.moveTo(cx + startGap, cy)
            path2.lineTo(cx + endGap, cy)
        } else {
            // Pinch inward (zoom out): move fingers together slightly
            path1.moveTo(cx - endGap, cy)
            path1.lineTo(cx - startGap, cy)
            path2.moveTo(cx + endGap, cy)
            path2.lineTo(cx + startGap, cy)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 100)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, 100)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        dispatchGesture(gesture, null, null)
    }

    /**
     * Simulate swipe gesture to pan map in small, smooth steps.
     * @param direction "up", "down", "left", "right"
     */
    fun performPan(direction: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val swipeDistance = (screenHeight / 16f).coerceIn(80f, 150f) // Small, fine pan distance (~100px)

        val path = Path()
        when (direction) {
            "up" -> {
                path.moveTo(cx, cy)
                path.lineTo(cx, cy - swipeDistance)
            }
            "down" -> {
                path.moveTo(cx, cy)
                path.lineTo(cx, cy + swipeDistance)
            }
            "left" -> {
                path.moveTo(cx, cy)
                path.lineTo(cx - swipeDistance, cy)
            }
            "right" -> {
                path.moveTo(cx, cy)
                path.lineTo(cx + swipeDistance, cy)
            }
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 120)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, null, null)
    }

    /**
     * Switch to recent apps (app switcher).
     */
    fun performAppSwitch(next: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    /**
     * Go to home screen / re-center (used for "Get back to my location" as a tap).
     */
    fun performTap(x: Float = screenWidth / 2f, y: Float = screenHeight / 2f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x, y)

        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, null, null)
    }

    // ─── Media & Volume Actions ─────────────────────────────────

    fun performMediaPlay() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun performMediaPause() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun performMediaPlayPause() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun performMediaNext() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun performMediaPrevious() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun performVolumeUp() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }

    fun performVolumeDown() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    private fun sendMediaKeyEvent(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        am.dispatchMediaKeyEvent(downEvent)
        am.dispatchMediaKeyEvent(upEvent)
    }
}
