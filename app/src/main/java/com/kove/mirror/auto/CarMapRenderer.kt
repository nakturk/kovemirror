package com.kove.mirror.auto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import com.kove.mirror.DebugLogger
import com.kove.mirror.MapStateHolder
import org.osmdroid.util.GeoPoint

/**
 * Bridges Android Auto's SurfaceCallback to a VirtualDisplay + CarMapPresentation,
 * and supplies independent GPS updates into MapStateHolder even when MapActivity is closed.
 */
class CarMapRenderer(private val carContext: CarContext) : SurfaceCallback, LocationListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarMapPresentation? = null
    private var locationManager: LocationManager? = null
    private var lastVisibleArea: Rect? = null
    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        startLocationUpdates()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        stopLocationUpdates()
        mainHandler.post {
            releaseDisplayAndPresentation()
        }
    }

    fun zoomIn() {
        presentation?.zoomIn()
    }

    fun zoomOut() {
        presentation?.zoomOut()
    }

    fun recenterOnLocation() {
        presentation?.recenterOnLocation()
    }

    fun cycleMapLayer() {
        presentation?.cycleMapLayer()
    }

    // ─── SurfaceCallback ───────────────────────────────────────

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        if (width <= 0 || height <= 0) return

        val dpi = surfaceContainer.dpi.coerceAtLeast(120)
        DebugLogger.info("🚗 [Android Auto] onSurfaceAvailable: ${width}x${height} @ ${dpi}dpi")

        mainHandler.post {
            releaseDisplayAndPresentation()
            try {
                val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val vd = displayManager.createVirtualDisplay(
                    "KoveAutoVirtualDisplay",
                    width,
                    height,
                    dpi,
                    surface,
                    0
                )
                virtualDisplay = vd
                val display = vd.display ?: return@post
                presentation = CarMapPresentation(carContext, display).apply {
                    show()
                    lastVisibleArea?.let { updateVisibleArea(it) }
                }
            } catch (e: Exception) {
                DebugLogger.error("❌ [Android Auto] Failed to create VirtualDisplay: ${e.message}")
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        lastVisibleArea = Rect(visibleArea)
        presentation?.updateVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (lastVisibleArea == null) {
            lastVisibleArea = Rect(stableArea)
        }
        presentation?.updateVisibleArea(stableArea)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        DebugLogger.info("🚗 [Android Auto] onSurfaceDestroyed")
        mainHandler.post {
            releaseDisplayAndPresentation()
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        presentation?.scrollMap(distanceX, distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        presentation?.scaleMap(scaleFactor)
    }

    private fun releaseDisplayAndPresentation() {
        try {
            presentation?.dismiss()
        } catch (_: Exception) {}
        presentation = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null
    }

    // ─── Standalone Location Tracking ──────────────────────────

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            DebugLogger.warning("⚠️ [Android Auto] Location permission not granted")
            return
        }

        val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    2f,
                    this,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    10f,
                    this,
                    Looper.getMainLooper()
                )
            }

            if (MapStateHolder.lastLocation == null) {
                val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lastKnown?.let { publishLocation(it) }
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ [Android Auto] Failed to start location updates: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        locationManager = null
    }

    override fun onLocationChanged(location: Location) {
        publishLocation(location)
    }

    private fun publishLocation(location: Location) {
        val speedKmH = (location.speed * 3.6f).toInt().coerceAtLeast(0)
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val altitude = if (location.hasAltitude()) location.altitude else 0.0
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        MapStateHolder.updateLocation(geoPoint, bearing, speedKmH, altitude)
    }
}
