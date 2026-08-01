package com.kove.mirror

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

enum class HandlebarKey {
    ESC,     // status 0 / ESC / PAUSE / BACK
    ENTER,   // status 1 / PLAY / OK / SELECT
    UP,      // status 2 / PREVIOUS / ZOOM OUT
    DOWN     // status 3 / NEXT / ZOOM IN
}

object HandlebarKeyManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(HandlebarKey) -> Boolean>()

    fun addListener(listener: (HandlebarKey) -> Boolean) {
        listeners.add(listener)
    }

    fun removeListener(listener: (HandlebarKey) -> Boolean) {
        listeners.remove(listener)
    }

    fun dispatchKey(key: HandlebarKey): Boolean {
        DebugLogger.info("🎮 Handlebar key trigger: $key")
        var handled = false
        mainHandler.post {
            for (listener in listeners) {
                if (listener(key)) {
                    handled = true
                    break
                }
            }
        }
        return handled
    }

    /**
     * Parses JSON string received from TFT (via BLE notification or TCP Port 17818).
     * Format: {"msg_id": 27, "func": "MUSIC", "act": "control", "status": [0|1|2|3]}
     */
    fun processJson(text: String): Boolean {
        if (text.isBlank()) return false
        var handledAny = false

        // Extract JSON objects using regex
        val regex = Regex("""\{[^{}]*\}""")
        val matches = regex.findAll(text)

        for (match in matches) {
            try {
                val json = JSONObject(match.value)
                val func = json.optString("func")
                val act = json.optString("act")

                val isMusicOrKey = func.equals("MUSIC", ignoreCase = true) ||
                        func.equals("KEY", ignoreCase = true) ||
                        func.equals("MEDIA", ignoreCase = true)

                val isControlOrKey = act.equals("control", ignoreCase = true) ||
                        act.equals("key", ignoreCase = true) ||
                        act.isEmpty()

                if (isMusicOrKey && isControlOrKey) {
                    val status = json.optInt("status", json.optInt("key", json.optInt("button", -1)))
                    val key = when (status) {
                        0 -> HandlebarKey.ESC
                        1 -> HandlebarKey.ENTER
                        2 -> HandlebarKey.UP
                        3 -> HandlebarKey.DOWN
                        else -> null
                    }

                    if (key != null) {
                        dispatchKey(key)
                        handledAny = true
                    }
                }
            } catch (_: Exception) {}
        }
        return handledAny
    }

    /**
     * Handles hardware / Bluetooth / media key events from Android input system.
     */
    fun processKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> HandlebarKey.UP
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> HandlebarKey.DOWN
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> HandlebarKey.ENTER
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> HandlebarKey.ESC
            else -> null
        }

        return if (key != null) {
            dispatchKey(key)
        } else false
    }
}
