package com.kove.mirror.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kove.mirror.DebugLogger
import com.kove.mirror.MapStateHolder

/**
 * Android Auto session managing standalone map initialization,
 * surface rendering lifecycle, and the root navigation screen.
 */
class KoveCarSession : Session() {

    private val carMapRenderer: CarMapRenderer by lazy {
        CarMapRenderer(carContext)
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                DebugLogger.info("🚗 [Android Auto] KoveCarSession onCreate")
                MapStateHolder.isCarSessionActive = true
                MapStateHolder.ensureInitializedFromStorage(carContext)
                carMapRenderer.start()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                DebugLogger.info("🚗 [Android Auto] KoveCarSession onDestroy")
                carMapRenderer.stop()
                MapStateHolder.isCarSessionActive = false
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return CarNavigationScreen(carContext, carMapRenderer)
    }
}
