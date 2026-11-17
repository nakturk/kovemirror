package com.kove.mirror

import android.content.Context
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
    @Volatile var isCarSessionActive: Boolean = false
    @Volatile var isNavigating: Boolean = false
    @Volatile var showRouteWeather: Boolean = true
    @Volatile var showRouteFuel: Boolean = true
    @Volatile private var lastWeatherFetchMs: Long = 0L
    @Volatile private var lastFuelFetchMs: Long = 0L
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

    /**
     * Initializes MapStateHolder from persisted preferences and saved routes
     * when Android Auto starts independently while MapActivity is closed.
     */
    fun ensureInitializedFromStorage(context: Context) {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        showRouteWeather = prefs.getBoolean("show_route_weather", true)
        showRouteFuel = prefs.getBoolean("show_route_fuel", true)

        if (currentCenter == null) {
            val savedLayer = prefs.getInt("map_layer", 0)
            currentLayer = if (savedLayer == 7) prefs.getInt("map_base_layer", 0) else savedLayer
            val lat = prefs.getFloat("map_lat", 39.0f).toDouble()
            val lon = prefs.getFloat("map_lon", 35.0f).toDouble()
            currentCenter = GeoPoint(lat, lon)
            currentZoom = prefs.getFloat("map_zoom", 15.0f).toDouble().coerceIn(3.0, 20.0)
        }

        if (loadedRoutes.isEmpty()) {
            val dtoList = RouteStorageManager.loadRoutes(context)
            for (dto in dtoList) {
                loadedRoutes.add(
                    SharedRoute(
                        id = dto.id,
                        name = dto.name,
                        points = dto.points.map { GeoPoint(it.lat, it.lon, it.alt) },
                        color = dto.color,
                        width = dto.width,
                        visible = dto.visible,
                        showDirectionArrows = dto.showDirectionArrows,
                        arrowColor = dto.arrowColor,
                        showDistanceMarkers = dto.showDistanceMarkers,
                        distanceIntervalKm = dto.distanceIntervalKm
                    )
                )
            }
            if (loadedRoutes.isNotEmpty()) {
                recomputeCurvesFromVisibleRoutes(context)
                notifyRoutesChanged()
            }
        }

        if (loadedRoutes.any { it.visible } || isNavigating) {
            if (showRouteWeather && routeWeatherPoints.isEmpty()) {
                refreshWeatherFromState(context)
            }
            if (showRouteFuel && routeFuelStations.isEmpty()) {
                refreshFuelFromState(context)
            }
        }
    }

    /**
     * Toggles visibility of a loaded route from Android Auto, persists it,
     * recomputes curve warnings, weather, and fuel stations, and notifies listeners.
     */
    fun toggleRouteVisibility(context: Context, routeId: String) {
        val idx = loadedRoutes.indexOfFirst { it.id == routeId }
        if (idx < 0) return

        val current = loadedRoutes[idx]
        val newVisible = !current.visible
        loadedRoutes[idx] = current.copy(visible = newVisible)

        val dtoList = RouteStorageManager.loadRoutes(context)
        val updatedDtos = dtoList.map { dto ->
            if (dto.id == routeId) dto.copy(visible = newVisible) else dto
        }
        RouteStorageManager.saveRoutes(context, updatedDtos)

        recomputeCurvesFromVisibleRoutes(context)
        notifyRoutesChanged()
        refreshWeatherFromState(context, force = true)
        refreshFuelFromState(context, force = true)
    }

    fun toggleRouteWeather(context: Context): Boolean {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        showRouteWeather = !showRouteWeather
        prefs.edit().putBoolean("show_route_weather", showRouteWeather).apply()
        refreshWeatherFromState(context, force = true)
        return showRouteWeather
    }

    fun toggleRouteFuel(
        context: Context,
        onResult: ((count: Int, hasPoiFile: Boolean) -> Unit)? = null
    ): Boolean {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        showRouteFuel = !showRouteFuel
        prefs.edit().putBoolean("show_route_fuel", showRouteFuel).apply()
        refreshFuelFromState(context, force = true, onResult = onResult)
        return showRouteFuel
    }

    fun refreshWeatherFromState(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        showRouteWeather = prefs.getBoolean("show_route_weather", true)
        if (!showRouteWeather) {
            updateWeatherPoints(emptyList())
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastWeatherFetchMs < 60_000L && routeWeatherPoints.isNotEmpty()) {
            return
        }

        val points = when {
            isNavigating && activeNavigationRoute != null -> activeNavigationRoute?.geometryPoints
            else -> loadedRoutes.firstOrNull { it.visible && it.points.size >= 2 }?.points
        }

        if (points.isNullOrEmpty() || points.size < 2) {
            updateWeatherPoints(emptyList())
            return
        }

        val checkpoints = RouteWeatherHelper.sampleRoutePoints(points)
        if (checkpoints.isEmpty()) {
            updateWeatherPoints(emptyList())
            return
        }

        lastWeatherFetchMs = now
        RouteWeatherHelper.fetchRouteWeather(checkpoints) { weatherPoints ->
            if (showRouteWeather) {
                updateWeatherPoints(weatherPoints)
            } else {
                updateWeatherPoints(emptyList())
            }
        }
    }

    fun refreshFuelFromState(
        context: Context,
        force: Boolean = false,
        onResult: ((count: Int, hasPoiFile: Boolean) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        showRouteFuel = prefs.getBoolean("show_route_fuel", true)
        if (!showRouteFuel) {
            updateFuelStations(emptyList())
            onResult?.invoke(0, true)
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastFuelFetchMs < 60_000L && routeFuelStations.isNotEmpty()) {
            onResult?.invoke(routeFuelStations.size, true)
            return
        }

        val corridorMeters = prefs.getInt("fuel_corridor_meters", RouteFuelHelper.DEFAULT_CORRIDOR_METERS)
        val routePolylines: List<List<GeoPoint>>
        val navLocation: GeoPoint?
        if (isNavigating && activeNavigationRoute != null) {
            val navPts = activeNavigationRoute?.geometryPoints
            routePolylines = if (!navPts.isNullOrEmpty() && navPts.size >= 2) listOf(navPts) else emptyList()
            navLocation = lastLocation
        } else {
            routePolylines = loadedRoutes.filter { it.visible && it.points.size >= 2 }.map { it.points }
            navLocation = null
        }

        if (routePolylines.isEmpty()) {
            updateFuelStations(emptyList())
            onResult?.invoke(0, true)
            return
        }

        lastFuelFetchMs = now
        RouteFuelHelper.fetchFuelStationsAlongRoutes(
            context = context,
            routePolylines = routePolylines,
            currentLocation = navLocation,
            corridorMeters = corridorMeters
        ) { stations, hasPoiFile ->
            if (!hasPoiFile || !showRouteFuel) {
                updateFuelStations(emptyList())
                onResult?.invoke(0, hasPoiFile)
            } else {
                updateFuelStations(stations)
                onResult?.invoke(stations.size, true)
            }
        }
    }

    fun stopNavigationFromExternal(context: Context? = null) {
        isNavigating = false
        activeNavigationRoute = null
        selectedDestination = null
        notifyNavigationChanged()
        if (context != null) {
            recomputeCurvesFromVisibleRoutes(context)
            refreshWeatherFromState(context, force = true)
            refreshFuelFromState(context, force = true)
        } else if (loadedRoutes.none { it.visible }) {
            updateCurves(emptyList())
            updateWeatherPoints(emptyList())
            updateFuelStations(emptyList())
        }
    }

    private fun recomputeCurvesFromVisibleRoutes(context: Context) {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("show_curve_warnings", true)) {
            updateCurves(emptyList())
            return
        }
        val navPts = if (isNavigating) activeNavigationRoute?.geometryPoints else null
        if (!navPts.isNullOrEmpty() && navPts.size >= 4) {
            updateCurves(CurveWarningHelper.detectCurves(navPts))
            return
        }
        val curves = mutableListOf<CurveWarningHelper.CurvePoint>()
        for (r in loadedRoutes) {
            if (r.visible && r.points.size >= 4) {
                curves.addAll(CurveWarningHelper.detectCurves(r.points))
            }
        }
        updateCurves(curves)
    }
}
