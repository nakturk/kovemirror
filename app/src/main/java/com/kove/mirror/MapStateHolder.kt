package com.kove.mirror

import org.osmdroid.util.GeoPoint
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared state between MapActivity and KovePresentation.
 * Allows VirtualDisplay Presentation to render identical route/navigation
 * and map overlays when the phone screen turns off.
 */
object MapStateHolder {

    data class SharedRoute(
        val id: String,
        val name: String,
        val points: List<GeoPoint>,
        val color: Int,
        val width: Float,
        val visible: Boolean = true,
        val showDirectionArrows: Boolean = true,
        val arrowColor: Int = color,
        val showDistanceMarkers: Boolean = true,
        val distanceIntervalKm: Int = 5
    )

    @Volatile var isMapOpen: Boolean = false
    @Volatile var isNavigating: Boolean = false
    @Volatile var activeNavigationRoute: NavigationHelper.NavigationRoute? = null
    @Volatile var selectedDestination: GeoPoint? = null
    @Volatile var lastLocation: GeoPoint? = null
    @Volatile var lastBearing: Float = 0f
    @Volatile var lastSpeedKmH: Int = 0
    @Volatile var lastAltitude: Double = 0.0
    @Volatile var currentCenter: GeoPoint? = null
    @Volatile var currentBearing: Float = 0f
    @Volatile var currentLayer: Int = 0
    @Volatile var currentZoom: Double = 16.0
    val loadedRoutes = CopyOnWriteArrayList<SharedRoute>()
    val routeWeatherPoints = CopyOnWriteArrayList<RouteWeatherHelper.RouteWeatherPoint>()
    val detectedCurves = CopyOnWriteArrayList<CurveWarningHelper.CurvePoint>()
    val routeFuelStations = CopyOnWriteArrayList<RouteFuelHelper.FuelStation>()

    interface StateListener {
        fun onNavigationStateChanged(isNavigating: Boolean, route: NavigationHelper.NavigationRoute?)
        fun onRoutesChanged(routes: List<SharedRoute>)
        fun onLocationUpdated(location: GeoPoint, bearing: Float, speedKmH: Int, altitude: Double)
        fun onLayerChanged(layer: Int)
        fun onCameraChanged(center: GeoPoint, zoom: Double, bearing: Float)
        fun onWeatherUpdated(weatherPoints: List<RouteWeatherHelper.RouteWeatherPoint>)
        fun onCurvesUpdated(curves: List<CurveWarningHelper.CurvePoint>)
        fun onFuelStationsUpdated(fuelStations: List<RouteFuelHelper.FuelStation>) {}
    }

    private val listeners = CopyOnWriteArrayList<StateListener>()

    fun addListener(listener: StateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    fun notifyNavigationChanged() {
        for (l in listeners) {
            try {
                l.onNavigationStateChanged(isNavigating, activeNavigationRoute)
            } catch (_: Exception) {}
        }
    }

    fun notifyRoutesChanged() {
        for (l in listeners) {
            try {
                l.onRoutesChanged(loadedRoutes.toList())
            } catch (_: Exception) {}
        }
    }

    fun notifyLayerChanged() {
        for (l in listeners) {
            try {
                l.onLayerChanged(currentLayer)
            } catch (_: Exception) {}
        }
    }

    fun notifyCameraChanged() {
        val center = currentCenter ?: return
        for (l in listeners) {
            try {
                l.onCameraChanged(center, currentZoom, currentBearing)
            } catch (_: Exception) {}
        }
    }

    fun updateCamera(center: GeoPoint, zoom: Double, bearing: Float = 0f) {
        currentCenter = center
        currentZoom = zoom
        currentBearing = bearing
        notifyCameraChanged()
    }

    fun updateWeatherPoints(points: List<RouteWeatherHelper.RouteWeatherPoint>) {
        routeWeatherPoints.clear()
        routeWeatherPoints.addAll(points)
        for (l in listeners) {
            try {
                l.onWeatherUpdated(points)
            } catch (_: Exception) {}
        }
    }

    fun updateCurves(curves: List<CurveWarningHelper.CurvePoint>) {
        detectedCurves.clear()
        detectedCurves.addAll(curves)
        for (l in listeners) {
            try {
                l.onCurvesUpdated(curves)
            } catch (_: Exception) {}
        }
    }

    fun updateFuelStations(stations: List<RouteFuelHelper.FuelStation>) {
        routeFuelStations.clear()
        routeFuelStations.addAll(stations)
        for (l in listeners) {
            try {
                l.onFuelStationsUpdated(stations)
            } catch (_: Exception) {}
        }
    }

    fun updateLocation(location: GeoPoint, bearing: Float, speedKmH: Int, altitude: Double) {
        lastLocation = location
        lastBearing = bearing
        lastSpeedKmH = speedKmH
        lastAltitude = altitude
        for (l in listeners) {
            try {
                l.onLocationUpdated(location, bearing, speedKmH, altitude)
            } catch (_: Exception) {}
        }
    }
}
