package com.kove.mirror

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Secondary Independent Display Presentation for Kove TFT Cluster.
 * Implements the Thinkerride-style zero-black-bar VirtualDisplay architecture.
 * Renders dedicated OSM Map + Motorcycle Instrument Cluster + Navigation HUD.
 */
class KovePresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display), LocationListener, MapStateHolder.StateListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var mapView: MapView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvHeadingValue: TextView
    private lateinit var tvAltValue: TextView
    private lateinit var tvGpsStatus: TextView

    // Navigation Turn-by-Turn HUD
    private lateinit var navBanner: LinearLayout
    private lateinit var tvNavTurnIcon: TextView
    private lateinit var tvNavTurnInstruction: TextView
    private lateinit var tvNavTurnDistance: TextView
    private lateinit var tvNavEta: TextView

    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var locationManager: LocationManager? = null

    // Overlays
    private var navigationPolyline: Polyline? = null
    private var destinationMarker: Marker? = null
    private val loadedRoutePolylines = mutableListOf<Polyline>()

    private var lastKnownSegmentIndex = 0

    // Tile sources (matching MapActivity)
    private val openTopoTileSource = XYTileSource(
        "OpenTopoMap",
        0, 17, 256, ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/"
        )
    )

    private val satelliteTileSource = object : OnlineTileSourceBase(
        "EsriWorldImagery",
        0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) + "/" +
                    MapTileIndex.getX(pMapTileIndex)
        }
    }

    private val googleMapsTileSource = object : OnlineTileSourceBase(
        "GoogleMaps",
        0, 20, 256, ".png",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=m",
            "https://mt1.google.com/vt/lyrs=m",
            "https://mt2.google.com/vt/lyrs=m",
            "https://mt3.google.com/vt/lyrs=m"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl + "&x=" + MapTileIndex.getX(pMapTileIndex) +
                    "&y=" + MapTileIndex.getY(pMapTileIndex) +
                    "&z=" + MapTileIndex.getZoom(pMapTileIndex)
        }
    }

    private val googleSatTileSource = object : OnlineTileSourceBase(
        "GoogleSatellite",
        0, 20, 256, ".jpg",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=s",
            "https://mt1.google.com/vt/lyrs=s",
            "https://mt2.google.com/vt/lyrs=s",
            "https://mt3.google.com/vt/lyrs=s"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl + "&x=" + MapTileIndex.getX(pMapTileIndex) +
                    "&y=" + MapTileIndex.getY(pMapTileIndex) +
                    "&z=" + MapTileIndex.getZoom(pMapTileIndex)
        }
    }

    private val googleHybridTileSource = object : OnlineTileSourceBase(
        "GoogleHybrid",
        0, 20, 256, ".jpg",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=y",
            "https://mt1.google.com/vt/lyrs=y",
            "https://mt2.google.com/vt/lyrs=y",
            "https://mt3.google.com/vt/lyrs=y"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl + "&x=" + MapTileIndex.getX(pMapTileIndex) +
                    "&y=" + MapTileIndex.getY(pMapTileIndex) +
                    "&z=" + MapTileIndex.getZoom(pMapTileIndex)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = context.packageName
        
        // Window parameters: MATCH_PARENT x MATCH_PARENT without margin (Zero-black-bar)
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        setContentView(R.layout.layout_presentation_dashboard)

        // Apply saved top/bottom padding to fit motorcycle TFT cluster bezel
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        val mainPrefs = context.getSharedPreferences("kove_prefs", Context.MODE_PRIVATE)
        val fallbackTop = mainPrefs.getInt("tft_top_padding_dp", MirrorService.TFT_TOP_PADDING_DP)
        val fallbackBottom = mainPrefs.getInt("tft_bottom_padding_dp", MirrorService.TFT_BOTTOM_PADDING_DP)
        val topDp = prefs.getInt("map_top_padding_dp", fallbackTop)
        val bottomDp = prefs.getInt("map_bottom_padding_dp", fallbackBottom)
        val density = context.resources.displayMetrics.density
        val topPx = (topDp * density).toInt()
        val bottomPx = (bottomDp * density).toInt()

        val topCluster = findViewById<View>(R.id.topInstrumentCluster)
        topCluster?.setPadding(
            topCluster.paddingLeft,
            topCluster.paddingTop + topPx,
            topCluster.paddingRight,
            topCluster.paddingBottom
        )

        val bottomCluster = findViewById<View>(R.id.bottomWatermarkBanner)
        bottomCluster?.setPadding(
            bottomCluster.paddingLeft,
            bottomCluster.paddingTop,
            bottomCluster.paddingRight,
            bottomCluster.paddingBottom + bottomPx
        )

        mapView = findViewById(R.id.presentationMapView)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvHeadingValue = findViewById(R.id.tvHeadingValue)
        tvAltValue = findViewById(R.id.tvAltValue)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)

        navBanner = findViewById(R.id.presentationNavBanner)
        tvNavTurnIcon = findViewById(R.id.tvNavTurnIcon)
        tvNavTurnInstruction = findViewById(R.id.tvNavTurnInstruction)
        tvNavTurnDistance = findViewById(R.id.tvNavTurnDistance)
        tvNavEta = findViewById(R.id.tvNavEta)

        setupMap()
        mapView.onResume()
        setupLocationTracking()
        syncStateFromHolder()

        MapStateHolder.addListener(this)
        DebugLogger.info("🏍️ KovePresentation created for TFT display (margins: top=${topDp}dp, bottom=${bottomDp}dp)")
    }

    private fun setupMap() {
        setupTileSource(MapStateHolder.currentLayer)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(MapStateHolder.currentZoom.coerceAtLeast(6.0))

        val initialCenter = MapStateHolder.currentCenter ?: MapStateHolder.lastLocation ?: GeoPoint(39.92077, 32.85411)
        mapView.controller.setCenter(initialCenter)
        if (MapStateHolder.currentBearing != 0f) {
            mapView.mapOrientation = MapStateHolder.currentBearing
        }

        val provider = GpsMyLocationProvider(context)
        myLocationOverlay = object : MyLocationNewOverlay(provider, mapView) {
            override fun onLocationChanged(
                location: Location?,
                source: org.osmdroid.views.overlay.mylocation.IMyLocationProvider?
            ) {
                super.onLocationChanged(location, source)
                if (location != null && !MapStateHolder.isMapOpen) {
                    this@KovePresentation.onLocationChanged(location)
                }
            }
        }.apply {
            enableMyLocation()
            enableFollowLocation()
        }
        mapView.overlays.add(myLocationOverlay)
    }

    private fun setupTileSource(layer: Int) {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        val mapPath = prefs.getString("offline_map_path", null)
        val mapFile = if (!mapPath.isNullOrEmpty()) java.io.File(mapPath) else null

        // If offline layer selected OR if offline map file exists on device, prioritize MapsForge offline vector map
        if (layer == 3 || (mapFile != null && mapFile.exists() && (layer == 0 || layer == 3))) {
            if (mapFile != null && mapFile.exists()) {
                try {
                    org.mapsforge.map.android.graphics.AndroidGraphicFactory.createInstance(context.applicationContext as android.app.Application)
                } catch (_: Exception) {}
                try {
                    val theme = org.mapsforge.map.rendertheme.InternalRenderTheme.DEFAULT
                    val fromFiles = org.osmdroid.mapsforge.MapsForgeTileSource.createFromFiles(
                        arrayOf(mapFile),
                        theme,
                        "DEFAULT"
                    )
                    val receiver = org.osmdroid.tileprovider.util.SimpleRegisterReceiver(context)
                    val moduleProvider = org.osmdroid.mapsforge.MapsForgeTileModuleProvider(
                        receiver,
                        fromFiles,
                        null
                    )
                    val forgeProvider = org.osmdroid.tileprovider.MapTileProviderArray(
                        fromFiles,
                        receiver,
                        arrayOf(moduleProvider)
                    )
                    mapView.tileProvider = forgeProvider
                    mapView.setTileSource(fromFiles)
                    mapView.tilesScaleFactor = 1.0f
                    mapView.isTilesScaledToDpi = false
                    DebugLogger.success("🗺️ [TFT Presentation] Offline MapsForge vector map loaded: ${mapFile.name}")
                    return
                } catch (e: Exception) {
                    DebugLogger.error("❌ [TFT Presentation] Offline map load error: ${e.message}")
                }
            }
        }

        if (mapView.tileProvider !is org.osmdroid.tileprovider.MapTileProviderBasic) {
            mapView.tileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(context)
        }

        val tileSource = when (layer) {
            1 -> openTopoTileSource
            2 -> satelliteTileSource
            4 -> googleMapsTileSource
            5 -> googleSatTileSource
            6 -> googleHybridTileSource
            else -> TileSourceFactory.MAPNIK
        }
        mapView.setTileSource(tileSource)
        DebugLogger.info("🗺️ [TFT Presentation] Map tile source set: ${tileSource.name()}")
    }

    override fun onLayerChanged(layer: Int) {
        mainHandler.post {
            setupTileSource(layer)
            mapView.invalidate()
        }
    }

    override fun onCameraChanged(center: GeoPoint, zoom: Double, bearing: Float) {
        mainHandler.post {
            if (!MapStateHolder.isNavigating) {
                mapView.controller.setZoom(zoom.coerceAtLeast(3.0))
                mapView.controller.setCenter(center)
                if (bearing != 0f) {
                    mapView.mapOrientation = bearing
                }
                mapView.invalidate()
            }
        }
    }

    private fun setupLocationTracking() {
        // Location updates are already provided by MyLocationNewOverlay (GpsMyLocationProvider)
        // when MapActivity is closed, or by MapActivity via MapStateHolder when MapActivity is open.
        DebugLogger.info("📍 [TFT Presentation] Location tracking active via MyLocationNewOverlay / MapStateHolder")
    }

    private val weatherMarkers = mutableListOf<Marker>()
    private val curveMarkers = mutableListOf<Marker>()

    override fun onWeatherUpdated(weatherPoints: List<RouteWeatherHelper.RouteWeatherPoint>) {
        mainHandler.post {
            for (m in weatherMarkers) {
                mapView.overlays.remove(m)
            }
            weatherMarkers.clear()

            for (w in weatherPoints) {
                val badgeBitmap = RouteWeatherHelper.createWeatherBadgeBitmap(context, w)
                val marker = Marker(mapView).apply {
                    position = w.point
                    icon = android.graphics.drawable.BitmapDrawable(context.resources, badgeBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
                weatherMarkers.add(marker)
            }
            mapView.invalidate()
            DebugLogger.info("🌤️ [TFT Presentation] Displaying ${weatherPoints.size} route weather badge(s)")
        }
    }

    override fun onCurvesUpdated(curves: List<CurveWarningHelper.CurvePoint>) {
        mainHandler.post {
            for (m in curveMarkers) {
                mapView.overlays.remove(m)
            }
            curveMarkers.clear()

            for (c in curves) {
                val badgeBitmap = CurveWarningHelper.createCurveBadgeBitmap(context, c)
                val marker = Marker(mapView).apply {
                    position = c.point
                    icon = android.graphics.drawable.BitmapDrawable(context.resources, badgeBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
                curveMarkers.add(marker)
            }
            mapView.invalidate()
            DebugLogger.info("🏍️ [TFT Presentation] Displaying ${curves.size} curve marker(s)")
        }
    }

    private fun syncStateFromHolder() {
        DebugLogger.info("🔄 [TFT Presentation] Syncing state from MapStateHolder (layer=${MapStateHolder.currentLayer}, routes=${MapStateHolder.loadedRoutes.size}, navigating=${MapStateHolder.isNavigating})")
        // 1. Sync loaded routes
        onRoutesChanged(MapStateHolder.loadedRoutes.toList())

        // 2. Sync navigation state
        onNavigationStateChanged(MapStateHolder.isNavigating, MapStateHolder.activeNavigationRoute)

        // 3. Sync weather points
        onWeatherUpdated(MapStateHolder.routeWeatherPoints.toList())

        // 4. Sync detected curves
        onCurvesUpdated(MapStateHolder.detectedCurves.toList())

        // 5. Sync camera position (center / zoom)
        val center = MapStateHolder.currentCenter ?: MapStateHolder.lastLocation
        if (center != null) {
            mapView.controller.setZoom(MapStateHolder.currentZoom.coerceAtLeast(6.0))
            mapView.controller.setCenter(center)
            if (MapStateHolder.currentBearing != 0f) {
                mapView.mapOrientation = MapStateHolder.currentBearing
            }
        }

        // 6. Sync last known location & instrument metrics
        MapStateHolder.lastLocation?.let { loc ->
            updateLocationMetrics(loc, MapStateHolder.lastBearing, MapStateHolder.lastSpeedKmH, MapStateHolder.lastAltitude)
        }
    }

    override fun onNavigationStateChanged(isNavigating: Boolean, route: NavigationHelper.NavigationRoute?) {
        mainHandler.post {
            // Remove old navigation polyline & destination marker
            navigationPolyline?.let { mapView.overlays.remove(it) }
            navigationPolyline = null
            destinationMarker?.let { mapView.overlays.remove(it) }
            destinationMarker = null

            if (isNavigating && route != null && route.geometryPoints.isNotEmpty()) {
                navBanner.visibility = View.VISIBLE

                navigationPolyline = Polyline().apply {
                    setPoints(route.geometryPoints)
                    outlinePaint.color = Color.parseColor("#2563EB") // Royal Blue
                    outlinePaint.strokeWidth = 14f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                mapView.overlays.add(navigationPolyline)

                // Add destination marker at the end of route
                val lastPoint = route.geometryPoints.last()
                destinationMarker = Marker(mapView).apply {
                    position = lastPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Destination"
                }
                mapView.overlays.add(destinationMarker)

                DebugLogger.info("🧭 [TFT Presentation] Navigation Turn-by-Turn banner active (${route.geometryPoints.size} points, ${route.steps.size} steps)")

                MapStateHolder.lastLocation?.let { loc ->
                    updateNavigationBanner(loc, route)
                }
            } else {
                navBanner.visibility = View.GONE
            }
            mapView.invalidate()
        }
    }

    override fun onRoutesChanged(routes: List<MapStateHolder.SharedRoute>) {
        mainHandler.post {
            for (p in loadedRoutePolylines) {
                mapView.overlays.remove(p)
            }
            loadedRoutePolylines.clear()

            var visibleCount = 0
            for (r in routes) {
                if (!r.visible || r.points.isEmpty()) continue
                visibleCount++
                val poly = Polyline().apply {
                    setPoints(r.points)
                    outlinePaint.color = r.color
                    outlinePaint.strokeWidth = (r.width * 2.5f).coerceAtLeast(6f)
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                loadedRoutePolylines.add(poly)
                // Add below location overlay if possible
                mapView.overlays.add(0, poly)
            }
            DebugLogger.info("🗺️ [TFT Presentation] Rendered $visibleCount active route(s) on VirtualDisplay")
            mapView.invalidate()
        }
    }

    override fun onLocationUpdated(location: GeoPoint, bearing: Float, speedKmH: Int, altitude: Double) {
        mainHandler.post {
            updateLocationMetrics(location, bearing, speedKmH, altitude)
        }
    }

    private var lastMetricsLogMs = 0L

    private fun updateLocationMetrics(location: GeoPoint, bearing: Float, speedKmH: Int, altitude: Double) {
        tvSpeedValue.text = speedKmH.toString()

        val normalizedBearing = ((bearing % 360f) + 360f) % 360f
        val dir = getCompassDirection(normalizedBearing.toInt())
        if (bearing >= 0f) {
            tvHeadingValue.text = "$dir ${normalizedBearing.toInt()}°"
        }

        if (altitude != 0.0) {
            tvAltValue.text = "${altitude.toInt()} m"
        }

        tvGpsStatus.text = "GPS FIXED"
        tvGpsStatus.setTextColor(Color.parseColor("#10B981"))

        myLocationOverlay?.enableFollowLocation()
        mapView.controller.setCenter(location)

        val now = System.currentTimeMillis()
        if (now - lastMetricsLogMs >= 5000L) {
            lastMetricsLogMs = now
            DebugLogger.data("🏍️ [TFT Dashboard] Speed: ${speedKmH}km/h | Compass: $dir ${bearing.toInt()}° | Alt: ${altitude.toInt()}m | Pos: ${String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)}")
        }

        if (MapStateHolder.isNavigating) {
            MapStateHolder.activeNavigationRoute?.let { route ->
                updateNavigationBanner(location, route)
            }
        }
    }

    private fun updateNavigationBanner(currentLocation: GeoPoint, route: NavigationHelper.NavigationRoute) {
        val pts = route.geometryPoints
        if (pts.size < 2) return

        // Windowed search for nearest segment
        var nearestSegIndex = lastKnownSegmentIndex
        var minSegDist = Double.MAX_VALUE
        val searchStart = (lastKnownSegmentIndex - 5).coerceAtLeast(0)
        val searchEnd = (lastKnownSegmentIndex + 50).coerceAtMost(pts.size - 2)

        for (i in searchStart..searchEnd) {
            val segDist = NavigationHelper.distanceToSegmentMeters(currentLocation, pts[i], pts[i + 1])
            if (segDist < minSegDist) {
                minSegDist = segDist
                nearestSegIndex = i
            }
        }

        if (minSegDist > 200.0) {
            for (i in 0 until pts.size - 1) {
                val segDist = NavigationHelper.distanceToSegmentMeters(currentLocation, pts[i], pts[i + 1])
                if (segDist < minSegDist) {
                    minSegDist = segDist
                    nearestSegIndex = i
                }
            }
        }

        lastKnownSegmentIndex = nearestSegIndex

        // Calculate remaining distance
        var remMeters = currentLocation.distanceToAsDouble(pts[nearestSegIndex + 1])
        for (i in (nearestSegIndex + 1) until pts.size - 1) {
            remMeters += pts[i].distanceToAsDouble(pts[i + 1])
        }

        // Find upcoming active step
        var activeStepIndex = -1
        for (i in route.steps.indices) {
            val step = route.steps[i]
            if (step.type == "arrive") {
                if (activeStepIndex == -1) activeStepIndex = i
                break
            }
            if (step.shapeIndex > nearestSegIndex) {
                activeStepIndex = i
                break
            } else if (step.shapeIndex == nearestSegIndex) {
                val d = currentLocation.distanceToAsDouble(step.location)
                if (d > 15.0) {
                    activeStepIndex = i
                    break
                }
            }
        }

        if (activeStepIndex == -1) {
            activeStepIndex = (route.steps.size - 1).coerceAtLeast(0)
        }

        if (activeStepIndex in route.steps.indices &&
            route.steps[activeStepIndex].type == "depart" &&
            activeStepIndex + 1 < route.steps.size
        ) {
            activeStepIndex++
        }

        val activeStep = route.steps.getOrNull(activeStepIndex)
        if (activeStep != null) {
            tvNavTurnInstruction.text = activeStep.instruction

            val targetIdx = activeStep.shapeIndex.coerceIn(0, pts.size - 1)
            var distToStepMeters = 0.0
            if (targetIdx > nearestSegIndex && pts.size > 1) {
                distToStepMeters = currentLocation.distanceToAsDouble(pts[(nearestSegIndex + 1).coerceAtMost(pts.size - 1)])
                for (i in (nearestSegIndex + 1) until targetIdx) {
                    distToStepMeters += pts[i].distanceToAsDouble(pts[i + 1])
                }
            } else {
                distToStepMeters = currentLocation.distanceToAsDouble(activeStep.location)
            }

            tvNavTurnDistance.text = if (distToStepMeters < 1000) {
                "${distToStepMeters.toInt()} m"
            } else {
                String.format(Locale.getDefault(), "%.1f km", distToStepMeters / 1000.0)
            }

            tvNavTurnIcon.text = when {
                activeStep.type == "arrive" -> "📍"
                activeStep.modifier.contains("sharp left") -> "⬅️"
                activeStep.modifier.contains("sharp right") -> "➡️"
                activeStep.modifier.contains("slight left") -> "↖️"
                activeStep.modifier.contains("slight right") -> "↗️"
                activeStep.modifier.contains("left") -> "⬅️"
                activeStep.modifier.contains("right") -> "➡️"
                activeStep.modifier.contains("uturn") || activeStep.modifier.contains("u turn") -> "↩️"
                activeStep.type.contains("roundabout") || activeStep.type.contains("rotary") -> "🔄"
                else -> "⬆️"
            }
        }

        val remKm = remMeters / 1000.0
        val remDurationSeconds = if (route.totalDistanceMeters > 0) (remMeters / route.totalDistanceMeters) * route.totalDurationSeconds else 0.0
        val remMin = (remDurationSeconds / 60.0).toInt()
        val remHours = remMin / 60
        val remainingMinutes = remMin % 60
        val timeStr = if (remHours > 0) "${remHours}h ${remainingMinutes}m" else "${remainingMinutes}m"
        tvNavEta.text = "$timeStr (${String.format(Locale.getDefault(), "%.1f", remKm)} km)"

        // Dynamically trim polyline
        if (pts.size > 1) {
            val remainingPoints = mutableListOf(currentLocation)
            for (i in (nearestSegIndex + 1) until pts.size) {
                remainingPoints.add(pts[i])
            }
            navigationPolyline?.setPoints(remainingPoints)
            mapView.invalidate()
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmH = (location.speed * 3.6f).toInt()
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val altitude = if (location.hasAltitude()) location.altitude else 0.0
        val geoPoint = GeoPoint(location.latitude, location.longitude)

        MapStateHolder.updateLocation(geoPoint, bearing, speedKmH, altitude)
    }

    private fun getCompassDirection(bearing: Int): String {
        return when (((bearing + 22.5) / 45).toInt() % 8) {
            0 -> "N"
            1 -> "NE"
            2 -> "E"
            3 -> "SE"
            4 -> "S"
            5 -> "SW"
            6 -> "W"
            7 -> "NW"
            else -> "N"
        }
    }

    override fun onStop() {
        MapStateHolder.removeListener(this)
        try {
            myLocationOverlay?.disableMyLocation()
            myLocationOverlay?.disableFollowLocation()
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        try {
            mapView.onPause()
            mapView.onDetach()
        } catch (_: Exception) {}
        super.onStop()
        DebugLogger.info("🏍️ KovePresentation stopped")
    }
}
