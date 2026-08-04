package com.kove.mirror

import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.views.MapView
import java.util.Calendar

object MapThemeHelper {

    const val THEME_DAY   = 0
    const val THEME_NIGHT = 1
    const val THEME_AUTO  = 2

    // Inverted high-contrast dark color matrix for map tiles
    private val NIGHT_COLOR_MATRIX = ColorMatrix(floatArrayOf(
        -0.8f,  0.0f,  0.0f, 0.0f, 255.0f,
         0.0f, -0.8f,  0.0f, 0.0f, 255.0f,
         0.0f,  0.0f, -0.8f, 0.0f, 255.0f,
         0.0f,  0.0f,  0.0f, 1.0f,   0.0f
    ))

    private val NIGHT_FILTER = ColorMatrixColorFilter(NIGHT_COLOR_MATRIX)

    fun applyTheme(context: Context, mapView: MapView, themeMode: Int) {
        val isNight = when (themeMode) {
            THEME_NIGHT -> true
            THEME_DAY -> false
            else -> isSystemOrTimeNightMode(context)
        }

        if (isNight) {
            mapView.overlayManager.tilesOverlay.setColorFilter(NIGHT_FILTER)
        } else {
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
        mapView.invalidate()
    }

    private fun isSystemOrTimeNightMode(context: Context): Boolean {
        // Check system dark mode first
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
            return true
        }
        // Fallback to time of day (19:00 to 06:00)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 19 || hour < 6
    }
}
