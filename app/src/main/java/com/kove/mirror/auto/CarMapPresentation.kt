package com.kove.mirror.auto

import android.app.Application
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.kove.mirror.CurveWarningHelper
import com.kove.mirror.DebugLogger
import com.kove.mirror.LocationCursorHelper
import com.kove.mirror.MapStateHolder
import com.kove.mirror.MapThemeHelper
import com.kove.mirror.NavigationHelper
import com.kove.mirror.R
import com.kove.mirror.RouteFuelHelper
import com.kove.mirror.RouteOverlayHelper
import com.kove.mirror.RouteWeatherHelper
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileModuleProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Android Auto VirtualDisplay Presentation rendering osmdroid map, routes,
 * curve/weather/fuel badges, and a compact speedometer inside the unobscured safe area.
 * Turn-by-turn banner and ETA are handled natively by Android Auto NavigationTemplate.
 */
class CarMapPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display), MapStateHolder.StateListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var mapView: MapView
    private lateinit var safeAreaContainer: View
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvLayerBadge: TextView
    private lateinit var tvCompassValue: TextView
    private lateinit var tvAltitudeValue: TextView
    private lateinit var tvSunTimesValue: TextView

    private var lastSunDayOfYear = -1
    private var lastSunLocation: GeoPoint? = null
    private var cachedSunTimesText = "🌅 --:--  🌇 --:--"

    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var isFollowing = true
    private var lastVisibleArea: Rect? = null
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f

    private var navigationPolyline: Polyline? = null
    private var destinationMarker: Marker? = null
    private val loadedRoutePolylines = mutableListOf<Polyline>()
    private val loadedRouteOverlayMarkers = mutableListOf<Marker>()
    private val weatherMarkers = mutableListOf<Marker>()
    private val curveMarkers = mutableListOf<Marker>()
    private val fuelMarkers = mutableListOf<Marker>()

    private var lastKnownSegmentIndex = 0

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

        try {
            (context.applicationContext as? Application)?.let {
                AndroidGraphicFactory.createInstance(it)
            }
        } catch (_: Exception) {}

        val osmConf = Configuration.getInstance()
        osmConf.userAgentValue = context.packageName
        osmConf.load(context, context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE))

        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        setContentView(R.layout.layout_car_presentation)

        mapView = findViewById(R.id.carMapView)
        safeAreaContainer = findViewById(R.id.carSafeAreaContainer)
        tvSpeedValue = findViewById(R.id.tvCarSpeedValue)
        tvLayerBadge = findViewById(R.id.tvCarLayerBadge)
        tvCompassValue = findViewById(R.id.tvCarCompassValue)
        tvAltitudeValue = findViewById(R.id.tvCarAltitudeValue)
        tvSunTimesValue = findViewById(R.id.tvCarSunTimesValue)

        setupMap()
        mapView.onResume()
        syncStateFromHolder()

        lastVisibleArea?.let { area ->
            safeAreaContainer.post { applyVisibleAreaPadding(area) }
        }

        MapStateHolder.addListener(this)
        DebugLogger.info("🚗 [Android Auto] CarMapPresentation created")
    }

    private fun setupMap() {
        val activeLayer = resolve2dLayer(MapStateHolder.currentLayer)
        setupTileSource(activeLayer)
        updateLayerBadge(activeLayer)

        mapView.setBuiltInZoomControls(false)
        mapView.setMultiTouchControls(false)
        mapView.controller.setZoom(MapStateHolder.currentZoom.coerceIn(3.0, 20.0))

        val initialCenter = MapStateHolder.lastLocation
            ?: MapStateHolder.currentCenter
            ?: GeoPoint(39.92077, 32.85411)
        mapView.controller.setCenter(initialCenter)
        if (MapStateHolder.currentBearing != 0f) {
            mapView.mapOrientation = MapStateHolder.currentBearing
        }

        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("map_theme_mode", MapThemeHelper.THEME_AUTO)
        MapThemeHelper.applyTheme(context, mapView, themeMode)

        val provider = GpsMyLocationProvider(context)
        val shape = prefs.getInt("map_cursor_shape", LocationCursorHelper.SHAPE_NAV_ARROW)
        val color = prefs.getInt("map_cursor_color", LocationCursorHelper.COLOR_PRESETS[0])
        val overlayScale = getCarOverlayScale()
        val cursorSizeDp = (48 * overlayScale).toInt().coerceIn(48, 96)
        val cursorBmp = LocationCursorHelper.createCursorBitmap(context, shape, color, cursorSizeDp).apply {
            density = context.resources.displayMetrics.densityDpi
        }

        myLocationOverlay = MyLocationNewOverlay(provider, mapView).apply {
            setPersonIcon(cursorBmp)
            setDirectionIcon(cursorBmp)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)
            enableMyLocation()
            if (isFollowing) {
                enableFollowLocation()
            }
        }
        mapView.overlays.add(myLocationOverlay)
    }

    private fun getCarOverlayScale(): Float {
        val rawDensity = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
        val userScale = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
            .getFloat("map_tiles_scale_factor", 1.0f)
        val targetDensity = 2.6f * userScale
        return (targetDensity / rawDensity).coerceIn(1.6f, 3.8f)
    }

    private fun resolve2dLayer(layer: Int): Int {
        if (layer != 7) return layer
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("map_base_layer", 0).takeIf { it != 7 } ?: 0
    }

    private fun setupTileSource(layer: Int) {
        val effectiveLayer = resolve2dLayer(layer)
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        val mapPath = prefs.getString("offline_map_path", null)
        val mapFile = if (!mapPath.isNullOrEmpty()) File(mapPath) else null

        if (effectiveLayer == 3 && mapFile != null && mapFile.exists()) {
            try {
                val theme = InternalRenderTheme.DEFAULT
                val fromFiles = MapsForgeTileSource.createFromFiles(
                    arrayOf(mapFile),
                    theme,
                    "DEFAULT"
                )
                val receiver = SimpleRegisterReceiver(context)
                val moduleProvider = MapsForgeTileModuleProvider(receiver, fromFiles, null)
                val forgeProvider = MapTileProviderArray(fromFiles, receiver, arrayOf(moduleProvider))
                mapView.tileProvider = forgeProvider
                mapView.setTileSource(fromFiles)
                mapView.tilesScaleFactor = 1.0f
                mapView.isTilesScaledToDpi = false
                updateLayerBadge(effectiveLayer)
                return
            } catch (e: Exception) {
                DebugLogger.error("❌ [AA Map] Offline map load error: ${e.message}")
            }
        }

        if (mapView.tileProvider !is MapTileProviderBasic) {
            mapView.tileProvider = MapTileProviderBasic(context)
        }

        val tileSource = when (effectiveLayer) {
            1 -> openTopoTileSource
            2 -> satelliteTileSource
            4 -> googleMapsTileSource
            5 -> googleSatTileSource
            6 -> googleHybridTileSource
            else -> TileSourceFactory.MAPNIK
        }
        mapView.setTileSource(tileSource)
        val savedScale = prefs.getFloat("map_tiles_scale_factor", 1.0f)
        val carTileScale = (savedScale * 1.45f).coerceIn(1.45f, 2.25f)
        mapView.tilesScaleFactor = carTileScale
        mapView.isTilesScaledToDpi = true
        val themeMode = prefs.getInt("map_theme_mode", MapThemeHelper.THEME_AUTO)
        MapThemeHelper.applyTheme(context, mapView, themeMode)
        updateLayerBadge(effectiveLayer)
    }

    private fun updateLayerBadge(layer: Int) {
        if (!::tvLayerBadge.isInitialized) return
        tvLayerBadge.text = when (layer) {
            1 -> "TOPO"
            2 -> "SAT"
            3 -> "OFFLINE"
            4 -> "GMAP"
            5 -> "GSAT"
            6 -> "GHYB"
            else -> "OSM"
        }
    }

    private fun syncStateFromHolder() {
        onRoutesChanged(MapStateHolder.loadedRoutes.toList())
        onNavigationStateChanged(MapStateHolder.isNavigating, MapStateHolder.activeNavigationRoute)
        onWeatherUpdated(MapStateHolder.routeWeatherPoints.toList())
        onCurvesUpdated(MapStateHolder.detectedCurves.toList())
        onFuelStationsUpdated(MapStateHolder.routeFuelStations.toList())

        val center = MapStateHolder.lastLocation ?: MapStateHolder.currentCenter
        if (center != null) {
            mapView.controller.setZoom(MapStateHolder.currentZoom.coerceIn(3.0, 20.0))
            mapView.controller.setCenter(center)
        }
        updateTelemetryHud(
            location = center,
            bearing = MapStateHolder.lastBearing,
            speedKmH = MapStateHolder.lastSpeedKmH,
            altitude = MapStateHolder.lastAltitude
        )
    }

    // ─── Car Surface Controls ──────────────────────────────────

    fun zoomIn() {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            mapView.controller.zoomIn()
            MapStateHolder.currentZoom = mapView.zoomLevelDouble
        }
    }

    fun zoomOut() {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            mapView.controller.zoomOut()
            MapStateHolder.currentZoom = mapView.zoomLevelDouble
        }
    }

    fun recenterOnLocation() {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            isFollowing = true
            myLocationOverlay?.enableFollowLocation()
            val loc = MapStateHolder.lastLocation ?: MapStateHolder.currentCenter
            if (loc != null) {
                mapView.controller.animateTo(loc)
                if (mapView.zoomLevelDouble < 15.0) {
                    mapView.controller.setZoom(16.0)
                }
            }
        }
    }

    fun cycleMapLayer() {
        mainHandler.post {
            val current = resolve2dLayer(MapStateHolder.currentLayer)
            val nextLayer = when (current) {
                0 -> 1
                1 -> 2
                2 -> 4
                else -> 0
            }
            context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE).edit().apply {
                putInt("map_layer", nextLayer)
                putInt("map_base_layer", nextLayer)
                apply()
            }
            MapStateHolder.currentLayer = nextLayer
            MapStateHolder.notifyLayerChanged()
            if (::mapView.isInitialized) {
                setupTileSource(nextLayer)
                mapView.invalidate()
            }
        }
    }

    fun scrollMap(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            if (!::mapView.isInitialized || mapView.width <= 0 || mapView.height <= 0) return@post
            isFollowing = false
            myLocationOverlay?.disableFollowLocation()

            scrollAccumX += distanceX
            scrollAccumY += distanceY
            val dx = scrollAccumX.roundToInt()
            val dy = scrollAccumY.roundToInt()
            if (dx == 0 && dy == 0) return@post

            scrollAccumX -= dx
            scrollAccumY -= dy

            val cx = mapView.width / 2
            val cy = mapView.height / 2
            val newCenter = mapView.projection.fromPixels(cx + dx, cy + dy)
            mapView.setExpectedCenter(newCenter)
            mapView.invalidate()
            MapStateHolder.currentCenter = GeoPoint(newCenter.latitude, newCenter.longitude)
        }
    }

    fun scaleMap(scaleFactor: Float) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            if (scaleFactor > 0f && !scaleFactor.isNaN()) {
                val zoomDelta = ln(scaleFactor.toDouble()) / ln(2.0)
                val targetZoom = (mapView.zoomLevelDouble + zoomDelta).coerceIn(3.0, 20.0)
                mapView.controller.setZoom(targetZoom)
                MapStateHolder.currentZoom = targetZoom
            }
        }
    }

    fun updateVisibleArea(visibleArea: Rect) {
        lastVisibleArea = Rect(visibleArea)
        mainHandler.post {
            applyVisibleAreaPadding(visibleArea)
        }
    }

    private fun applyVisibleAreaPadding(visibleArea: Rect) {
        if (!::safeAreaContainer.isInitialized || !::mapView.isInitialized) return
        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0 || visibleArea.width() <= 0 || visibleArea.height() <= 0) return

        val basePad = (12 * context.resources.displayMetrics.density).toInt()
        val padLeft = visibleArea.left.coerceAtLeast(0) + basePad
        val padTop = visibleArea.top.coerceAtLeast(0) + basePad
        val padRight = (w - visibleArea.right).coerceAtLeast(0) + basePad
        val padBottom = (h - visibleArea.bottom).coerceAtLeast(0) + basePad

        safeAreaContainer.setPadding(padLeft, padTop, padRight, padBottom)
    }

    // ─── MapStateHolder.StateListener ──────────────────────────

    override fun onLayerChanged(layer: Int) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            setupTileSource(layer)
            mapView.invalidate()
        }
    }

    override fun onCameraChanged(center: GeoPoint, zoom: Double, bearing: Float) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            if (!MapStateHolder.isNavigating && !isFollowing) {
                mapView.controller.setZoom(zoom.coerceIn(3.0, 20.0))
                mapView.controller.setCenter(center)
                if (bearing != 0f) {
                    mapView.mapOrientation = bearing
                }
                mapView.invalidate()
            }
        }
    }

    override fun onRoutesChanged(routes: List<MapStateHolder.SharedRoute>) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            for (p in loadedRoutePolylines) {
                mapView.overlays.remove(p)
            }
            loadedRoutePolylines.clear()
            for (m in loadedRouteOverlayMarkers) {
                mapView.overlays.remove(m)
            }
            loadedRouteOverlayMarkers.clear()

            val density = context.resources.displayMetrics.density
            val overlayScale = getCarOverlayScale()
            for (r in routes) {
                if (!r.visible || r.points.isEmpty()) continue
                val poly = Polyline().apply {
                    setPoints(r.points)
                    outlinePaint.color = r.color
                    outlinePaint.strokeWidth = (r.width * density * overlayScale * 0.65f).coerceAtLeast(8f)
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                loadedRoutePolylines.add(poly)
                mapView.overlays.add(0, poly)

                if (r.showDirectionArrows && r.points.size >= 2) {
                    val arrows = RouteOverlayHelper.createDirectionArrowOverlays(
                        context,
                        mapView,
                        r.points,
                        r.arrowColor,
                        scaleFactor = overlayScale
                    )
                    loadedRouteOverlayMarkers.addAll(arrows)
                    mapView.overlays.addAll(arrows)
                }

                if (r.showDistanceMarkers && r.points.size >= 2) {
                    val markers = RouteOverlayHelper.createDistanceMarkerOverlays(
                        context,
                        mapView,
                        r.points,
                        r.distanceIntervalKm,
                        scaleFactor = overlayScale
                    )
                    loadedRouteOverlayMarkers.addAll(markers)
                    mapView.overlays.addAll(markers)
                }
            }
            mapView.invalidate()
        }
    }

    override fun onNavigationStateChanged(isNavigating: Boolean, route: NavigationHelper.NavigationRoute?) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            lastKnownSegmentIndex = 0
            navigationPolyline?.let { mapView.overlays.remove(it) }
            navigationPolyline = null
            destinationMarker?.let { mapView.overlays.remove(it) }
            destinationMarker = null

            if (isNavigating && route != null && route.geometryPoints.isNotEmpty()) {
                isFollowing = true
                myLocationOverlay?.enableFollowLocation()

                navigationPolyline = Polyline().apply {
                    setPoints(route.geometryPoints)
                    outlinePaint.color = Color.parseColor("#2563EB")
                    outlinePaint.strokeWidth = 14f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                mapView.overlays.add(navigationPolyline)

                val lastPoint = route.geometryPoints.last()
                destinationMarker = Marker(mapView).apply {
                    position = lastPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Destination"
                }
                mapView.overlays.add(destinationMarker)

                MapStateHolder.lastLocation?.let { loc ->
                    trimNavigationPolyline(loc, route)
                }
            }
            mapView.invalidate()
        }
    }

    override fun onLocationUpdated(location: GeoPoint, bearing: Float, speedKmH: Int, altitude: Double) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            updateTelemetryHud(location, bearing, speedKmH, altitude)

            if (isFollowing) {
                mapView.controller.setCenter(location)
                if (bearing > 0f && (speedKmH > 2 || MapStateHolder.isNavigating)) {
                    mapView.mapOrientation = -bearing
                }
            }

            if (MapStateHolder.isNavigating) {
                MapStateHolder.activeNavigationRoute?.let { route ->
                    trimNavigationPolyline(location, route)
                }
            }
        }
    }

    override fun onWeatherUpdated(weatherPoints: List<RouteWeatherHelper.RouteWeatherPoint>) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            for (m in weatherMarkers) {
                mapView.overlays.remove(m)
            }
            weatherMarkers.clear()

            val overlayScale = getCarOverlayScale()
            for (w in weatherPoints) {
                val badgeBitmap = RouteWeatherHelper.createWeatherBadgeBitmap(context, w, overlayScale)
                val marker = Marker(mapView).apply {
                    position = w.point
                    icon = BitmapDrawable(context.resources, badgeBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
                weatherMarkers.add(marker)
            }
            mapView.invalidate()
        }
    }

    override fun onCurvesUpdated(curves: List<CurveWarningHelper.CurvePoint>) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            for (m in curveMarkers) {
                mapView.overlays.remove(m)
            }
            curveMarkers.clear()

            val overlayScale = getCarOverlayScale()
            for (c in curves) {
                val badgeBitmap = CurveWarningHelper.createCurveBadgeBitmap(context, c, overlayScale)
                val marker = Marker(mapView).apply {
                    position = c.point
                    icon = BitmapDrawable(context.resources, badgeBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
                curveMarkers.add(marker)
            }
            mapView.invalidate()
        }
    }

    override fun onFuelStationsUpdated(fuelStations: List<RouteFuelHelper.FuelStation>) {
        mainHandler.post {
            if (!::mapView.isInitialized) return@post
            for (m in fuelMarkers) {
                mapView.overlays.remove(m)
            }
            fuelMarkers.clear()

            val overlayScale = getCarOverlayScale()
            for (f in fuelStations) {
                val badgeBitmap = RouteFuelHelper.createFuelBadgeBitmap(context, f, overlayScale)
                val marker = Marker(mapView).apply {
                    position = f.point
                    icon = BitmapDrawable(context.resources, badgeBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
                fuelMarkers.add(marker)
            }
            mapView.invalidate()
        }
    }

    private fun trimNavigationPolyline(
        currentLocation: GeoPoint,
        route: NavigationHelper.NavigationRoute
    ) {
        val pts = route.geometryPoints
        if (pts.size < 2) return

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

        val remainingPoints = mutableListOf(currentLocation)
        for (i in (nearestSegIndex + 1) until pts.size) {
            remainingPoints.add(pts[i])
        }
        navigationPolyline?.setPoints(remainingPoints)
        mapView.invalidate()
    }

    private fun updateTelemetryHud(
        location: GeoPoint?,
        bearing: Float,
        speedKmH: Int,
        altitude: Double
    ) {
        tvSpeedValue.text = speedKmH.toString()

        val normalizedBearing = ((bearing % 360f) + 360f) % 360f
        val dir = getCompassDirection(normalizedBearing.toInt())
        tvCompassValue.text = "🧭 $dir ${normalizedBearing.toInt()}°"

        tvAltitudeValue.text = if (altitude != 0.0) {
            "⛰️ ${altitude.toInt()} m"
        } else {
            "⛰️ -- m"
        }

        val sunLoc = location ?: MapStateHolder.currentCenter
        if (sunLoc != null && (sunLoc.latitude != 0.0 || sunLoc.longitude != 0.0)) {
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_YEAR)
            val prevLoc = lastSunLocation
            val movedEnough = prevLoc == null || prevLoc.distanceToAsDouble(sunLoc) > 5000.0
            if (today != lastSunDayOfYear || movedEnough) {
                val sunrise = computeSunTime(sunLoc.latitude, sunLoc.longitude, isSunrise = true, calendar = cal)
                val sunset = computeSunTime(sunLoc.latitude, sunLoc.longitude, isSunrise = false, calendar = cal)
                cachedSunTimesText = "🌅 $sunrise  🌇 $sunset"
                lastSunDayOfYear = today
                lastSunLocation = GeoPoint(sunLoc.latitude, sunLoc.longitude)
            }
        }
        tvSunTimesValue.text = cachedSunTimesText
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

    /**
     * Offline NOAA/USNO astronomical sunrise & sunset calculator (official 90.833° zenith).
     */
    private fun computeSunTime(
        lat: Double,
        lon: Double,
        isSunrise: Boolean,
        calendar: Calendar = Calendar.getInstance()
    ): String {
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = if (isSunrise) {
            dayOfYear + ((6.0 - lngHour) / 24.0)
        } else {
            dayOfYear + ((18.0 - lngHour) / 24.0)
        }

        val m = (0.9856 * t) - 3.289
        var l = m + (1.916 * sin(Math.toRadians(m))) + (0.020 * sin(Math.toRadians(2.0 * m))) + 282.634
        l = ((l % 360.0) + 360.0) % 360.0

        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = ((ra % 360.0) + 360.0) % 360.0
        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra = (ra + (lQuadrant - raQuadrant)) / 15.0

        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))

        val cosH = (cos(Math.toRadians(90.833)) - (sinDec * sin(Math.toRadians(lat)))) /
            (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1.0 || cosH < -1.0) {
            return "--:--"
        }

        var h = if (isSunrise) {
            360.0 - Math.toDegrees(acos(cosH))
        } else {
            Math.toDegrees(acos(cosH))
        }
        h /= 15.0

        val localMeanTime = h + ra - (0.06571 * t) - 6.622
        var utc = localMeanTime - lngHour
        utc = ((utc % 24.0) + 24.0) % 24.0

        val tzOffsetHours = calendar.timeZone.getOffset(calendar.timeInMillis) / 3_600_000.0
        val localHours = ((utc + tzOffsetHours) % 24.0 + 24.0) % 24.0

        val totalMinutes = (localHours * 60.0).roundToInt() % (24 * 60)
        val hour = totalMinutes / 60
        val minute = totalMinutes % 60
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    override fun onStop() {
        MapStateHolder.removeListener(this)
        try {
            myLocationOverlay?.disableMyLocation()
            myLocationOverlay?.disableFollowLocation()
        } catch (_: Exception) {}
        try {
            if (::mapView.isInitialized) {
                mapView.onPause()
                mapView.onDetach()
            }
        } catch (_: Exception) {}
        super.onStop()
        DebugLogger.info("🚗 [Android Auto] CarMapPresentation stopped")
    }
}
