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

/**
 * Defines the action modes in the overlay menu.
 * @param isEntAction If true, this is a one-time ENT action (My Location, Play, Pause).
 *                    If false, this is a continuous UP/DOWN mode assignment (Zoom, Pan, Media, Volume, App Switch).
 */
enum class HandlebarActionMode(
    val displayName: String,
    val displayNameTr: String,
    val isEntAction: Boolean
) {
    MY_LOCATION("Get back to my location", "Konumuma Dön", isEntAction = true),
    ZOOM("Zoom In / Zoom Out", "Yakınlaştır / Uzaklaştır", isEntAction = false),
    PAN_VERTICAL("Up / Down Pan", "Yukarı / Aşağı Kaydır", isEntAction = false),
    PAN_HORIZONTAL("Left / Right Pan", "Sola / Sağa Kaydır", isEntAction = false),
    MEDIA_TRACK("Next Track / Prev Track", "Sonraki / Önceki Parça", isEntAction = false),
    VOLUME("Volume Up / Down", "Ses Aç / Kıs", isEntAction = false),
    PLAY("Play", "Oynat", isEntAction = true),
    PAUSE("Pause", "Duraklat", isEntAction = true),
    APP_SWITCH("Next App / Prev App", "Sonraki / Önceki Uygulama", isEntAction = false);
}

object HandlebarKeyManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(HandlebarKey) -> Boolean>()

    // ─── Overlay Menu & Active Mode State ───────────────────
    var isOverlayMenuOpen = false
        private set

    /** The active continuous mode assigned to UP/DOWN keys (defaults to ZOOM). */
    var activeUpDownMode: HandlebarActionMode = HandlebarActionMode.ZOOM
        private set

    /** Currently highlighted menu item index. ALWAYS resets to 0 (MY_LOCATION) when overlay opens. */
    var highlightedIndex: Int = 0
        private set

    // ─── Callbacks for Overlay & Actions ────────────────────
    var onOverlayToggle: ((Boolean) -> Unit)? = null
    var onHighlightChanged: ((Int) -> Unit)? = null
    var onModeActivated: ((HandlebarActionMode) -> Unit)? = null
    var onActionDispatch: ((HandlebarActionMode, HandlebarKey) -> Unit)? = null

    private val allModes = HandlebarActionMode.entries.toList()

    fun addListener(listener: (HandlebarKey) -> Boolean) {
        listeners.add(listener)
    }

    fun removeListener(listener: (HandlebarKey) -> Boolean) {
        listeners.remove(listener)
    }

    /**
     * Central key dispatch that handles overlay menu state and action routing.
     */
    fun dispatchKey(key: HandlebarKey): Boolean {
        DebugLogger.info("🎮 Handlebar key trigger: $key (overlay=${isOverlayMenuOpen}, activeMode=${activeUpDownMode.name})")

        mainHandler.post {
            when {
                // ─── Overlay Menu is OPEN ───────────────────────
                isOverlayMenuOpen -> handleOverlayOpenKey(key)
                // ─── Overlay Menu is CLOSED ─────────────────────
                else -> handleOverlayClosedKey(key)
            }
        }
        return true
    }

    private fun handleOverlayOpenKey(key: HandlebarKey) {
        when (key) {
            HandlebarKey.UP -> {
                highlightedIndex = (highlightedIndex - 1 + allModes.size) % allModes.size
                onHighlightChanged?.invoke(highlightedIndex)
            }
            HandlebarKey.DOWN -> {
                highlightedIndex = (highlightedIndex + 1) % allModes.size
                onHighlightChanged?.invoke(highlightedIndex)
            }
            HandlebarKey.ENTER -> {
                val selectedOption = allModes[highlightedIndex]

                if (selectedOption.isEntAction) {
                    // ENT Action (MY_LOCATION, PLAY, PAUSE): execute action once, do NOT change activeUpDownMode
                    DebugLogger.info("🎮 Executing ENT action: ${selectedOption.name}")
                    onActionDispatch?.invoke(selectedOption, HandlebarKey.ENTER)
                } else {
                    // Continuous UP/DOWN Mode Assignment (ZOOM, PAN, MEDIA, VOLUME, APP_SWITCH):
                    // Set as active UP/DOWN assignment!
                    activeUpDownMode = selectedOption
                    DebugLogger.info("🎮 Active UP/DOWN mode assigned to: ${activeUpDownMode.name}")
                    onModeActivated?.invoke(activeUpDownMode)
                }

                // Close overlay menu
                isOverlayMenuOpen = false
                onOverlayToggle?.invoke(false)
            }
            HandlebarKey.ESC -> {
                // Close overlay without action
                isOverlayMenuOpen = false
                onOverlayToggle?.invoke(false)
            }
        }
    }

    private fun handleOverlayClosedKey(key: HandlebarKey) {
        when (key) {
            HandlebarKey.UP, HandlebarKey.DOWN -> {
                // Dispatch UP/DOWN to current activeUpDownMode
                onActionDispatch?.invoke(activeUpDownMode, key)
                // Also propagate to legacy listeners
                for (listener in listeners) {
                    if (listener(key)) break
                }
            }
            HandlebarKey.ENTER -> {
                // Single ENT when overlay is closed → Open Overlay Menu
                // ALWAYS reset highlightedIndex to 0 ("Get back to my location")
                isOverlayMenuOpen = true
                highlightedIndex = 0
                onOverlayToggle?.invoke(true)
                onHighlightChanged?.invoke(highlightedIndex)
                DebugLogger.info("🎮 ENT → Opening Overlay Menu (Defaulting selection to 'Get back to my location')")
            }
            HandlebarKey.ESC -> {
                // Propagate ESC to legacy listeners
                for (listener in listeners) {
                    if (listener(key)) break
                }
            }
        }
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
