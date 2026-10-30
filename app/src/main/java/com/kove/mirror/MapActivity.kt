package com.kove.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.MapView as MapLibreMapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MapActivity : AppCompatActivity() {

    companion object {
        private const val REQ_FILE_PICK = 200
        private const val REQ_LOCATION_PERM = 201
        private const val REQ_MAP_FILE_PICK = 202

        private const val LAYER_MAPS = 0
        private const val LAYER_TOPO = 1
        private const val LAYER_SATELLITE = 2
        private const val LAYER_OFFLINE = 3
        private const val LAYER_GOOGLE_MAPS = 4
        private const val LAYER_GOOGLE_SAT = 5
        private const val LAYER_GOOGLE_HYBRID = 6
        private const val LAYER_3D = 7

        private const val THREE_D_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val THREE_D_BUILDINGS_URL = "https://tiles.openfreemap.org/planet"
        private const val THREE_D_SATELLITE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        private const val THREE_D_GOOGLE_MAPS_URL =
            "https://mt0.google.com/vt/lyrs=m&x={x}&y={y}&z={z}"
        private const val THREE_D_GOOGLE_SAT_URL =
            "https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"
        private const val THREE_D_GOOGLE_HYBRID_URL =
            "https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"
    }

    private lateinit var mapView: MapView
    private lateinit var mapView3d: MapLibreMapView
    private var map3d: Map3dOverlays? = null
    private var maplibreMap: MapLibreMap? = null
    private var is3dStyleLoaded = false
    private var follow3d = false
    private var locationOverlay: MyLocationNewOverlay? = null
    private var isGpsEnabled = false
    private var currentLayer = LAYER_MAPS
    private var currentBaseLayer = LAYER_MAPS
    private var layerBefore3d = LAYER_MAPS
    private var lastDiagLine = ""

    // ─── Long Press Navigation ──────────────────────────────────
    private var selectedDestination: GeoPoint? = null
    private var destinationMarker: Marker? = null
    private var navigationPolyline: Polyline? = null
    private var isNavigating = false
    private var isRecalculatingRoute = false
    private var lastRecalculateTimeMs = 0L
    private var currentNavigationRoute: NavigationHelper.NavigationRoute? = null
    private var initialTotalDurationSeconds: Double = 0.0
    private var lastKnownSegmentIndex: Int = 0

    // ─── Route Weather Forecast ──────────────────────────────────
    private val weatherMarkers2d = mutableListOf<Marker>()
    private var showRouteWeather: Boolean = false
    private var lastWeatherFetchMs: Long = 0L

    // ─── Sharp Curve & Hairpin Warnings ──────────────────────────
    private var showCurveWarnings: Boolean = true
    private val curveMarkers2d = mutableListOf<Marker>()
    private var detectedCurvesList = listOf<CurveWarningHelper.CurvePoint>()

    // ─── GPX Track Recording ─────────────────────────────────────
    private var isRecordingTrack = false
    private val recordedTrackPoints = mutableListOf<TrackPoint>()
    private var recordedPolyline: Polyline? = null
    private var recordingColor = Color.parseColor("#EF4444") // Red default
    private var recordingStartTimeMs = 0L
    private val recordTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recordTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecordingTrack) {
                val btnRecord = findViewById<TextView>(R.id.btnRecordTrack)
                btnRecord?.text = "●"
                btnRecord?.setTextColor(Color.parseColor("#FF0000"))
                btnRecord?.setBackgroundColor(Color.parseColor("#CC333333"))
                recordTimerHandler.postDelayed(this, 1000)
            }
        }
    }

    // ─── Route Management ───────────────────────────────────────

    data class LoadedRoute(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val groupName: String = name,
        var points: List<GeoPoint>,
        var color: Int,
        var width: Float,
        var visible: Boolean = true,
        var showDirectionArrows: Boolean = true,
        var arrowColor: Int = color,
        var showDistanceMarkers: Boolean = true,
        var distanceIntervalKm: Int = 5,
        var polyline: Polyline? = null,
        val arrowMarkers: MutableList<Marker> = mutableListOf(),
        val distanceMarkers: MutableList<Marker> = mutableListOf()
    )

    private val loadedRoutes = mutableListOf<LoadedRoute>()
    private val expandedGroups = mutableSetOf<String>()
    private val kmlMarkers = mutableListOf<Marker>()
    private val kmlPolygons = mutableListOf<org.osmdroid.views.overlay.Polygon>()
    private val loadedKmlPlacemarks = mutableListOf<KmlPlacemark>()
    private var nextColorIndex = 0

    // ─── Tile Sources ───────────────────────────────────────────

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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            org.mapsforge.map.android.graphics.AndroidGraphicFactory.createInstance(application)
        } catch (_: Exception) {}

        // osmdroid configuration
        val osmConf = Configuration.getInstance()
        osmConf.userAgentValue = packageName
        osmConf.load(this, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))

        // MapLibre GL Native initialization (must happen before MapView creation)
        MapLibre.getInstance(applicationContext)

        setContentView(R.layout.activity_map)

        // Keep screen on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mapView = findViewById(R.id.mapView)
        mapView3d = findViewById(R.id.mapView3D)
        setupMap()
        setup3dMap()
        setupButtons()
        setupMapEvents()

        // Load saved settings, map layer & TFT black bar margins
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        recordingColor = prefs.getInt("map_recording_color", Color.parseColor("#EF4444"))
        val topPadding = prefs.getInt("map_top_padding_dp", 0)
        val bottomPadding = prefs.getInt("map_bottom_padding_dp", 0)
        applyTftPadding(topPadding, bottomPadding)

        val savedLayer = prefs.getInt("map_layer", LAYER_MAPS)
        val savedBaseLayer = prefs.getInt("map_base_layer", LAYER_MAPS)
        val savedLayerBefore3d = prefs.getInt("map_layer_before_3d", LAYER_MAPS)
        currentBaseLayer = savedBaseLayer
        layerBefore3d = savedLayerBefore3d
        switchLayer(savedLayer)

        // Load saved routes and KML documents from persistent local storage
        loadSavedRoutesFromStorage()

        // Restore active track recording if interrupted
        val tempRecording = GpxRecorderHelper.loadTempPoints(this)
        if (tempRecording != null && tempRecording.second.isNotEmpty()) {
            recordingColor = tempRecording.first
            recordedTrackPoints.clear()
            recordedTrackPoints.addAll(tempRecording.second)
            recordingStartTimeMs = tempRecording.second.first().timeMs
            isRecordingTrack = true
            updateRecordedPolylineOnMap()
            recordTimerHandler.post(recordTimerRunnable)
            val btnRecord = findViewById<TextView>(R.id.btnRecordTrack)
            btnRecord?.text = "●"
            btnRecord?.setTextColor(Color.parseColor("#FF0000"))
            btnRecord?.setBackgroundColor(Color.parseColor("#CC333333"))
        }
    }

    // ─── Map Setup ──────────────────────────────────────────────

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        val rotationGestureOverlay = org.osmdroid.views.overlay.gestures.RotationGestureOverlay(mapView)
        rotationGestureOverlay.isEnabled = true
        mapView.overlays.add(rotationGestureOverlay)
        @Suppress("DEPRECATION")
        mapView.setBuiltInZoomControls(false)

        val controller = mapView.controller
        controller.setZoom(6.0)
        controller.setCenter(GeoPoint(39.0, 35.0))

        // Load saved map position
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val savedLat = prefs.getFloat("map_lat", 39.0f).toDouble()
        val savedLon = prefs.getFloat("map_lon", 35.0f).toDouble()
        val savedZoom = prefs.getFloat("map_zoom", 6.0f).toDouble()
        controller.setZoom(savedZoom)
        controller.setCenter(GeoPoint(savedLat, savedLon))

        applyMapTheme()

        val savedScale = prefs.getFloat("map_tiles_scale_factor", 1.0f)
        applyMapTileScale(savedScale)

        mapView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            update2dCameraPadding()
        }
        mapView.post { update2dCameraPadding() }

        mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                val center = mapView.mapCenter
                MapStateHolder.updateCamera(
                    GeoPoint(center.latitude, center.longitude),
                    mapView.zoomLevelDouble,
                    mapView.mapOrientation
                )
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                val center = mapView.mapCenter
                MapStateHolder.updateCamera(
                    GeoPoint(center.latitude, center.longitude),
                    mapView.zoomLevelDouble,
                    mapView.mapOrientation
                )
                update2dPolylineWidths()
                return false
            }
        })
    }

    private fun getZoomScaledLineWidthPx(baseDpAtZoom15: Float): Float {
        val zoom = mapView.zoomLevelDouble
        val density = resources.displayMetrics.density
        val scale = Math.pow(1.25, (zoom - 15.0).coerceIn(-5.0, 5.0)).toFloat()
        return (baseDpAtZoom15 * density * scale).coerceIn(1.5f * density, 15f * density)
    }

    private fun update2dPolylineWidths() {
        val navWidth = getZoomScaledLineWidthPx(5.5f)
        val trackWidth = getZoomScaledLineWidthPx(5.5f)

        navigationPolyline?.let {
            it.outlinePaint.strokeWidth = navWidth
        }
        recordedPolyline?.let {
            it.outlinePaint.strokeWidth = trackWidth
        }
        for (route in loadedRoutes) {
            route.polyline?.let {
                it.outlinePaint.strokeWidth = getZoomScaledLineWidthPx(route.width)
            }
        }
        mapView.invalidate()
    }

    private fun update2dCameraPadding() {
        val h = mapView.height
        if (h > 0) {
            mapView.setMapCenterOffset(0, (h * 0.25f).toInt())
        }
    }

    // ─── 3D Map Setup (MapLibre GL Native) ─────────────────────

    private fun setup3dMap() {
        mapView3d.getMapAsync { map ->
            maplibreMap = map
            map3d = Map3dOverlays(map, resources.displayMetrics.density)
            update3dCameraPadding()
            refresh3dCursor()
            map3d?.setDestinationIcon(createDestinationPinBitmap())
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isTiltGesturesEnabled = true
            map.uiSettings.isRotateGesturesEnabled = true
            map.setMinZoomPreference(8.0)

            mapView3d.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                update3dCameraPadding()
            }
            mapView3d.post { update3dCameraPadding() }

            // Disable 3D GPS auto-follow when the user drags/rotates the camera manually
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    follow3d = false
                }
            }

            map.addOnCameraMoveListener {
                val cam = map.cameraPosition ?: return@addOnCameraMoveListener
                val target = cam.target ?: return@addOnCameraMoveListener
                MapStateHolder.updateCamera(
                    GeoPoint(target.latitude, target.longitude),
                    cam.zoom,
                    cam.bearing.toFloat()
                )
            }

// Cap the pitch by zoom: below a threshold the tilted camera shows mostly
            // empty sky/horizon (looks blank), so there pitch is flat. On zoom-in we
            // restore the normal tilted 3D view so it does not stay stuck top-down.
            map.addOnCameraIdleListener {
                val cam = map.cameraPosition ?: return@addOnCameraIdleListener
                val maxTilt = maxPitchForZoom(cam.zoom)
                map.setMaxPitchPreference(maxTilt)
                if (cam.tilt > maxTilt) {
                    map.setCameraPosition(CameraPosition.Builder(cam).tilt(maxTilt).build())
                }
            }

            // On-screen diagnostics for the 3D map (debugging the blank-on-zoom-out issue)
            mapView3d.addOnCameraDidChangeListener { update3dDiag() }
            mapView3d.addOnDidFinishRenderingMapListener { fully ->
                lastDiagLine = if (fully) "render:FULL" else "render:PARTIAL"
                update3dDiag()
            }
            mapView3d.addOnDidFailLoadingMapListener { error ->
                lastDiagLine = "LOAD_FAIL:${error.take(80)}"
                update3dDiag()
            }

            // Long press on the 3D map drops a destination (same as 2D)
            map.addOnMapLongClickListener { point ->
                if (!isNavigating) {
                    onMapLongPressed(GeoPoint(point.latitude, point.longitude))
                }
                true
            }

            map.setStyle(styleFor3dBaseLayer()) { style ->
                is3dStyleLoaded = true
                add3dBuildingsLayer(style)
                map3d?.onStyleLoaded(style)

                // Re-push all current 2D overlay state onto the freshly loaded 3D map
                refresh3dRoutes()
                if (recordedTrackPoints.isNotEmpty()) {
                    map3d?.setTrack(recordedTrackPoints.map { LatLng(it.lat, it.lon) }, recordingColor)
                }
                currentNavigationRoute?.let { route ->
                    map3d?.setNavigationRoute(
                        route.geometryPoints.map { LatLng(it.latitude, it.longitude) }
                    )
                }
                selectedDestination?.let {
                    map3d?.setDestination(LatLng(it.latitude, it.longitude))
                }
                sync2dCameraTo3d()
            }

            // If 3D mode is already active (e.g. switchLayer ran before getMapAsync
            // returned), re-apply the correct base layer now that the map is ready.
            if (currentLayer == LAYER_3D) {
                apply3dBaseLayer()
            }
        }
    }

    // Builds the MapLibre style corresponding to the current base layer
    // (Maps/Topo -> OpenFreeMap liberty, Satellite -> Esri World Imagery raster).
    private fun styleFor3dBaseLayer(): Style.Builder {
        return when (currentBaseLayer) {
            LAYER_SATELLITE -> createRasterStyleBuilder("esri-sat", THREE_D_SATELLITE_URL)
            LAYER_GOOGLE_MAPS -> createRasterStyleBuilder("google-maps", THREE_D_GOOGLE_MAPS_URL)
            LAYER_GOOGLE_SAT -> createRasterStyleBuilder("google-sat", THREE_D_GOOGLE_SAT_URL)
            LAYER_GOOGLE_HYBRID -> createRasterStyleBuilder("google-hybrid", THREE_D_GOOGLE_HYBRID_URL)
            else -> Style.Builder().fromUri(THREE_D_STYLE_URL)
        }
    }

    private fun createRasterStyleBuilder(sourceId: String, tileUrl: String): Style.Builder {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val scale = prefs.getFloat("map_tiles_scale_factor", 1.0f)
        val tileSize = if (scale > 1.0f) (256.0 / scale).toInt().coerceIn(128, 256) else 256
        return Style.Builder()
            .withSource(RasterSource(sourceId, TileSet("2.1.0", tileUrl), tileSize))
            .withLayer(
                BackgroundLayer("$sourceId-bg").withProperties(
                    PropertyFactory.backgroundColor(Color.parseColor("#1a1a2e"))
                )
            )
            .withLayer(RasterLayer("$sourceId-layer", sourceId))
    }

    // (Re)loads the 3D style for the current base layer while staying in 3D mode.
    private fun apply3dBaseLayer() {
        val map = maplibreMap ?: return
        is3dStyleLoaded = false
        map.setStyle(styleFor3dBaseLayer()) { style ->
            is3dStyleLoaded = true
            add3dBuildingsLayer(style)
            map3d?.onStyleLoaded(style)
            refresh3dRoutes()
            if (recordedTrackPoints.isNotEmpty()) {
                map3d?.setTrack(recordedTrackPoints.map { LatLng(it.lat, it.lon) }, recordingColor)
            }
            currentNavigationRoute?.let { route ->
                map3d?.setNavigationRoute(route.geometryPoints.map { LatLng(it.latitude, it.longitude) })
            }
            selectedDestination?.let {
                map3d?.setDestination(LatLng(it.latitude, it.longitude))
            }
            sync2dCameraTo3d()
        }
    }

    private fun add3dBuildingsLayer(style: Style) {
        try {
            val source = VectorSource("ofm-planet-3d", THREE_D_BUILDINGS_URL)
            style.addSource(source)

            val buildings = FillExtrusionLayer("3d-buildings", "ofm-planet-3d")
                .withProperties(
                    PropertyFactory.fillExtrusionColor(Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(0, Expression.color(android.graphics.Color.parseColor("#d4d4d8"))),
                        Expression.stop(14, Expression.color(android.graphics.Color.parseColor("#d4d4d8"))),
                        Expression.stop(15, Expression.color(android.graphics.Color.parseColor("#9ca3af")))
                    )),
                    PropertyFactory.fillExtrusionHeight(Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(0, 0),
                        Expression.stop(14, 0),
                        Expression.stop(16, Expression.get("render_height"))
                    )),
                    PropertyFactory.fillExtrusionOpacity(0.75f)
                )
            buildings.setSourceLayer("building")
            style.addLayerAt(buildings, 0)
        } catch (e: Exception) {
            DebugLogger.error("❌ 3D buildings layer error: ${e.message}")
        }
    }

    private fun update3dLocationMarker(lat: Double, lon: Double, bearing: Double? = null) {
        if (currentLayer != LAYER_3D) return
        map3d?.setLocation(LatLng(lat, lon), bearing)
        if (follow3d || isNavigating) {
            val map = maplibreMap ?: return
            val zoom = map.cameraPosition?.zoom ?: 16.0
            val currentTilt = map.cameraPosition?.tilt ?: maxPitchForZoom(zoom)
            val targetTilt = currentTilt.coerceAtMost(maxPitchForZoom(zoom))
            map.setCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .bearing(bearing ?: map.cameraPosition?.bearing ?: 0.0)
                    .tilt(targetTilt)
                    .build()
            )
        }
    }

    private fun refresh3dCursor() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val shape = prefs.getInt("map_cursor_shape", LocationCursorHelper.SHAPE_NAV_ARROW)
        val color = prefs.getInt("map_cursor_color", LocationCursorHelper.COLOR_PRESETS[0])
        map3d?.setCursorIcon(LocationCursorHelper.createCursorBitmap(this, shape, color))
    }

    // Renders the same osmdroid default marker pin used on the 2D map for the 3D destination.
    private fun createDestinationPinBitmap(): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)
                ?: return null
            val targetH = (44 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val srcH = drawable.intrinsicHeight
            val srcW = drawable.intrinsicWidth
            if (srcH <= 0 || srcW <= 0) return null
            val scale = targetH.toFloat() / srcH
            val targetW = (srcW * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, targetW, targetH)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            DebugLogger.error("❌ Destination pin bitmap error: ${e.message}")
            null
        }
    }

    private fun sync2dCameraTo3d() {
        val map = maplibreMap ?: return
        val center = mapView.mapCenter
        val zoom = mapView.zoomLevelDouble
        val clampedZoom = zoom.coerceIn(8.0, 18.0)
        map.setMaxPitchPreference(maxPitchForZoom(clampedZoom))
        val position = CameraPosition.Builder()
            .target(LatLng(center.latitude, center.longitude))
            .zoom(clampedZoom)
            .tilt(maxPitchForZoom(clampedZoom))
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    // Maximum allowed pitch for a given zoom so the tilted camera never shows a blank horizon.
    private fun maxPitchForZoom(zoom: Double): Double = when {
        zoom < 12.0 -> 0.0
        zoom < 14.0 -> 20.0
        zoom < 16.0 -> 40.0
        else -> 60.0
    }

    private fun sync3dCameraTo2d() {
        val map = maplibreMap ?: return
        val target = map.cameraPosition?.target ?: return
        mapView.controller.setZoom(map.cameraPosition?.zoom ?: 6.0)
        mapView.controller.setCenter(GeoPoint(target.latitude, target.longitude))
        mapView.invalidate()
    }

    private fun enter3dMode() {
        mapView.visibility = View.GONE
        mapView3d.visibility = View.VISIBLE
        update3dCameraPadding()
        update3dDiag()
        if (!is3dStyleLoaded) {
            Toast.makeText(this, getString(R.string.map_3d_loading), Toast.LENGTH_SHORT).show()
        }
        sync2dCameraTo3d()
        locationOverlay?.myLocation?.let {
            update3dLocationMarker(it.latitude, it.longitude)
        }
    }

    private fun update3dCameraPadding() {
        val map = maplibreMap ?: return
        val h = mapView3d.height
        if (h > 0) {
            val topPadding = (h * 0.5f).toInt()
            map.setPadding(0, topPadding, 0, 0)
        }
    }

    private fun leave3dMode() {
        mapView3d.visibility = View.GONE
        mapView.visibility = View.VISIBLE
        findViewById<View>(R.id.diag3d).visibility = View.GONE
        sync3dCameraTo2d()
    }

    // Shows the current 3D camera state (zoom/tilt/bearing) + last render/load status
    // in the on-screen diagnostics overlay, to pinpoint the blank-on-zoom-out issue.
    private fun update3dDiag() {
        if (currentLayer != LAYER_3D) return
        val tv = findViewById<TextView>(R.id.diag3d) ?: return
        val cam = maplibreMap?.cameraPosition
        val zoom = cam?.zoom?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
        val tilt = cam?.tilt?.let { String.format(Locale.US, "%.0f", it) } ?: "-"
        val bearing = cam?.bearing?.let { String.format(Locale.US, "%.0f", it) } ?: "-"
        tv.visibility = View.VISIBLE
        tv.text = "z:$zoom tilt:$tilt b:$bearing\n$lastDiagLine"
    }

    // ─── Map Long Press Events ───────────────────────────────────

    private fun setupMapEvents() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null && !isNavigating) {
                    onMapLongPressed(p)
                    return true
                }
                return false
            }
        }
        val overlay = MapEventsOverlay(receiver)
        mapView.overlays.add(0, overlay)
    }

    private fun onMapLongPressed(point: GeoPoint) {
        selectDestinationPoint(point)
        GeocoderHelper.reverseGeocode(point.latitude, point.longitude) { address ->
            if (address.isNotEmpty() && selectedDestination == point) {
                findViewById<TextView>(R.id.tvDestCoords)?.text = address
                val title = address.split(",").firstOrNull()?.trim() ?: address
                destinationMarker?.title = title
            }
        }
    }

    private fun selectDestinationPoint(point: GeoPoint, labelName: String? = null) {
        selectedDestination = point

        if (destinationMarker == null) {
            destinationMarker = Marker(mapView).apply {
                title = labelName ?: getString(R.string.nav_destination_selected)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(destinationMarker)
        }
        destinationMarker?.position = point
        if (labelName != null) {
            destinationMarker?.title = labelName
        }
        mapView.invalidate()
        map3d?.setDestination(LatLng(point.latitude, point.longitude))

        val destCard = findViewById<LinearLayout>(R.id.destCard)
        val tvCoords = findViewById<TextView>(R.id.tvDestCoords)
        val tvDist = findViewById<TextView>(R.id.tvDestDist)

        if (labelName != null) {
            tvCoords.text = labelName
        } else {
            tvCoords.text = String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)
        }

        val myLoc = locationOverlay?.myLocation
        if (myLoc != null && myLoc.latitude != 0.0 && myLoc.longitude != 0.0) {
            val distMeters = myLoc.distanceToAsDouble(point)
            val distKm = distMeters / 1000.0
            tvDist.text = String.format(Locale.getDefault(), "Approx: %.1f km", distKm)
        } else {
            tvDist.text = getString(R.string.map_waiting_gps)
        }

        destCard.visibility = View.VISIBLE
        if (currentLayer == LAYER_3D) {
            maplibreMap?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(point.latitude, point.longitude)))
        } else {
            mapView.controller.animateTo(point)
        }
    }

    private fun cancelDestinationSelection() {
        selectedDestination = null
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = null
        map3d?.setDestination(null)
        findViewById<LinearLayout>(R.id.destCard).visibility = View.GONE
        mapView.invalidate()
    }

    // ─── Start / Stop Navigation ────────────────────────────────

    private fun startNavigation() {
        val dest = selectedDestination ?: return
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        if (!isGpsEnabled) {
            toggleGps()
        }

        val myLoc = locationOverlay?.myLocation
        if (myLoc == null || (myLoc.latitude == 0.0 && myLoc.longitude == 0.0)) {
            Toast.makeText(this, getString(R.string.map_waiting_gps), Toast.LENGTH_SHORT).show()
            return
        }

        // Hide dest card, show turn banner
        findViewById<LinearLayout>(R.id.destCard).visibility = View.GONE
        val navBanner = findViewById<LinearLayout>(R.id.navBanner)
        val tvInstruction = findViewById<TextView>(R.id.tvTurnInstruction)
        val tvTurnDist = findViewById<TextView>(R.id.tvTurnDistance)
        val tvTotalEta = findViewById<TextView>(R.id.tvTotalDistanceEta)

        navBanner.visibility = View.VISIBLE
        tvInstruction.text = getString(R.string.nav_calculating)
        tvTurnDist.text = ""
        tvTotalEta.text = ""

        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val avoidTolls = prefs.getBoolean("nav_avoid_tolls", false)
        val avoidHighways = prefs.getBoolean("nav_avoid_highways", false)
        val useShortest = prefs.getBoolean("nav_use_shortest", false)

        val btnTolls = findViewById<Button>(R.id.btnTollToggle)
        btnTolls?.visibility = View.VISIBLE
        updateTollButtonState(avoidTolls)

        NavigationHelper.fetchRoute(
            start = myLoc,
            destination = dest,
            avoidTolls = avoidTolls,
            avoidHighways = avoidHighways,
            useShortest = useShortest,
            onSuccess = { route ->
                runOnUiThread {
                    currentNavigationRoute = route
                    initialTotalDurationSeconds = route.totalDurationSeconds
                    isNavigating = true

                    MapStateHolder.isNavigating = true
                    MapStateHolder.activeNavigationRoute = route
                    MapStateHolder.selectedDestination = dest
                    MapStateHolder.notifyNavigationChanged()

                    map3d?.setNavigationRoute(
                        route.geometryPoints.map { LatLng(it.latitude, it.longitude) }
                    )

                    // Draw navigation polyline on the 2D map
                    navigationPolyline?.let { mapView.overlays.remove(it) }
                    navigationPolyline = Polyline().apply {
                        setPoints(route.geometryPoints)
                        outlinePaint.color = Color.parseColor("#2563EB") // Royal Blue
                        outlinePaint.strokeWidth = getZoomScaledLineWidthPx(5.5f)
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        outlinePaint.isAntiAlias = true
                    }
                    mapView.overlays.add(navigationPolyline)
                    mapView.invalidate()

                    // Enable auto-follow GPS
                    locationOverlay?.enableFollowLocation()
                    mapView.controller.setZoom(16.0)

                    if (currentLayer == LAYER_3D) {
                        follow3d = true
                        maplibreMap?.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(myLoc.latitude, myLoc.longitude), 16.0
                            )
                        )
                    }

                    updateNavigationUi(myLoc)
                    updateWeatherButtonVisibility()
                    if (showRouteWeather) {
                        refreshRouteWeatherForecast(force = true)
                    }
                    if (showCurveWarnings) {
                        refreshCurveWarnings()
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "Navigation error: $error", Toast.LENGTH_LONG).show()
                    stopNavigation()
                }
            }
        )
    }

    private fun stopNavigation() {
        isNavigating = false
        isRecalculatingRoute = false
        currentNavigationRoute = null
        selectedDestination = null

        MapStateHolder.isNavigating = false
        MapStateHolder.activeNavigationRoute = null
        MapStateHolder.selectedDestination = null
        MapStateHolder.notifyNavigationChanged()

        map3d?.setNavigationRoute(null)
        map3d?.setDestination(null)

        navigationPolyline?.let { mapView.overlays.remove(it) }
        navigationPolyline = null

        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = null

        findViewById<LinearLayout>(R.id.navBanner).visibility = View.GONE
        findViewById<View>(R.id.topBar).visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.destCard).visibility = View.GONE
        findViewById<Button>(R.id.btnTollToggle)?.visibility = View.GONE

        mapView.invalidate()
        updateWeatherButtonVisibility()

        if (loadedRoutes.isEmpty()) {
            clearRouteWeatherMarkers()
            clearCurveMarkers()
        } else {
            if (showRouteWeather) {
                refreshRouteWeatherForecast(force = true)
            }
            if (showCurveWarnings) {
                refreshCurveWarnings()
            }
        }
    }

    private fun updateNavigationUi(currentLocation: GeoPoint) {
        val route = currentNavigationRoute ?: return
        val dest = selectedDestination ?: return
        val pts = route.geometryPoints
        val hasValidLocation = currentLocation.latitude != 0.0 && currentLocation.longitude != 0.0

        // ── Single windowed segment scan (fixes #3 double scan & #4 O(n) perf) ──
        var nearestSegIndex = lastKnownSegmentIndex
        var minSegDist = Double.MAX_VALUE
        var remainingDistMeters = route.totalDistanceMeters

        if (hasValidLocation && pts.size > 1) {
            // Search window: from (last - 5) to (last + 50), clamped to valid range
            val searchStart = (lastKnownSegmentIndex - 5).coerceAtLeast(0)
            val searchEnd = (lastKnownSegmentIndex + 50).coerceAtMost(pts.size - 2)

            for (i in searchStart..searchEnd) {
                val segDist = NavigationHelper.distanceToSegmentMeters(
                    currentLocation, pts[i], pts[i + 1]
                )
                if (segDist < minSegDist) {
                    minSegDist = segDist
                    nearestSegIndex = i
                }
            }

            // If windowed search yielded a very large distance, do a full scan as fallback
            if (minSegDist > 200.0) {
                for (i in 0 until pts.size - 1) {
                    val segDist = NavigationHelper.distanceToSegmentMeters(
                        currentLocation, pts[i], pts[i + 1]
                    )
                    if (segDist < minSegDist) {
                        minSegDist = segDist
                        nearestSegIndex = i
                    }
                }
            }

            lastKnownSegmentIndex = nearestSegIndex

            // Calculate remaining distance from nearest segment to end of polyline
            var remMeters = currentLocation.distanceToAsDouble(pts[nearestSegIndex + 1])
            for (i in (nearestSegIndex + 1) until pts.size - 1) {
                remMeters += pts[i].distanceToAsDouble(pts[i + 1])
            }
            if (remMeters > 0.0 && remMeters <= route.totalDistanceMeters * 1.5) {
                remainingDistMeters = remMeters
            }
        }

        if (remainingDistMeters < 30.0) {
            Toast.makeText(this, getString(R.string.nav_arrived), Toast.LENGTH_LONG).show()
            stopNavigation()
            return
        }

        // Automatic rerouting if off-route (> 50m)
        val now = System.currentTimeMillis()
        if (minSegDist > 50.0 && !isRecalculatingRoute && (now - lastRecalculateTimeMs > 5000L)) {
            recalculateRoute(currentLocation, dest)
            return
        }

        // Find upcoming active step along the route
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

        // If active step is just "depart", advance to next turn if available
        if (activeStepIndex in route.steps.indices &&
            route.steps[activeStepIndex].type == "depart" &&
            activeStepIndex + 1 < route.steps.size
        ) {
            activeStepIndex++
        }

        val tvIcon = findViewById<TextView>(R.id.tvTurnIcon)
        val tvInstruction = findViewById<TextView>(R.id.tvTurnInstruction)
        val tvTurnDist = findViewById<TextView>(R.id.tvTurnDistance)
        val tvTotalEta = findViewById<TextView>(R.id.tvTotalDistanceEta)

        val activeStep = route.steps.getOrNull(activeStepIndex)
        if (activeStep != null) {
            tvInstruction.text = activeStep.instruction

            val targetIdx = activeStep.shapeIndex.coerceIn(0, (pts.size - 1).coerceAtLeast(0))
            var distToStepMeters = 0.0
            if (targetIdx > nearestSegIndex && pts.size > 1) {
                distToStepMeters = currentLocation.distanceToAsDouble(pts[(nearestSegIndex + 1).coerceAtMost(pts.size - 1)])
                for (i in (nearestSegIndex + 1) until targetIdx) {
                    distToStepMeters += pts[i].distanceToAsDouble(pts[i + 1])
                }
            } else {
                distToStepMeters = currentLocation.distanceToAsDouble(activeStep.location)
            }

            if (distToStepMeters < route.totalDistanceMeters * 1.5 && activeStep.location.latitude != 0.0) {
                tvTurnDist.text = if (distToStepMeters < 1000) {
                    "${distToStepMeters.toInt()} m"
                } else {
                    String.format(Locale.getDefault(), "%.1f km", distToStepMeters / 1000.0)
                }
            } else {
                tvTurnDist.text = ""
            }

            tvIcon.text = when {
                activeStep.type == "arrive" -> "📍"
                activeStep.modifier.contains("sharp left") -> "⬅️"
                activeStep.modifier.contains("sharp right") -> "➡️"
                activeStep.modifier.contains("slight left") -> "↖️"
                activeStep.modifier.contains("slight right") -> "↗️"
                activeStep.modifier.contains("left") -> "⬅️"
                activeStep.modifier.contains("right") -> "➡️"
                activeStep.modifier.contains("uturn") || activeStep.modifier.contains("u turn") ||
                        activeStep.type.contains("uturn") || activeStep.type.contains("u turn") -> "↩️"
                activeStep.type.contains("roundabout") || activeStep.type.contains("rotary") -> "🔄"
                else -> "⬆️"
            }
        }

        val remKm = remainingDistMeters / 1000.0
        val remDurationSeconds = if (route.totalDistanceMeters > 0) (remainingDistMeters / route.totalDistanceMeters) * route.totalDurationSeconds else 0.0
        val remMin = (remDurationSeconds / 60.0).toInt()
        val remStr = formatDurationText(remMin)
        tvTotalEta.text = getString(R.string.label_remaining_time, remStr, remKm)

        // Dynamically trim passed route polyline ahead of current position (uses single scan result)
        if (hasValidLocation && pts.size > 1) {
            val remainingPoints = mutableListOf(currentLocation)
            for (i in (nearestSegIndex + 1) until pts.size) {
                remainingPoints.add(pts[i])
            }

            navigationPolyline?.setPoints(remainingPoints)
            mapView.invalidate()
            map3d?.setNavigationRoute(remainingPoints.map { LatLng(it.latitude, it.longitude) })
        }
    }

    private fun formatDurationText(minutes: Int): String {
        val hrs = minutes / 60
        val mins = minutes % 60
        val unitH = getString(R.string.unit_hours)
        val unitM = getString(R.string.unit_minutes)

        return if (hrs > 0) {
            getString(R.string.duration_format_hours_mins, hrs, unitH, mins, unitM)
        } else {
            getString(R.string.duration_format_mins, mins, unitM)
        }
    }

    private fun recalculateRoute(start: GeoPoint, dest: GeoPoint) {
        isRecalculatingRoute = true
        lastRecalculateTimeMs = System.currentTimeMillis()

        val tvInstruction = findViewById<TextView>(R.id.tvTurnInstruction)
        tvInstruction?.text = getString(R.string.nav_recalculating)

        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val avoidTolls = prefs.getBoolean("nav_avoid_tolls", false)
        val avoidHighways = prefs.getBoolean("nav_avoid_highways", false)
        val useShortest = prefs.getBoolean("nav_use_shortest", false)

        NavigationHelper.fetchRoute(
            start = start,
            destination = dest,
            avoidTolls = avoidTolls,
            avoidHighways = avoidHighways,
            useShortest = useShortest,
            onSuccess = { newRoute ->
                runOnUiThread {
                    if (!isNavigating) {
                        isRecalculatingRoute = false
                        return@runOnUiThread
                    }
                    currentNavigationRoute = newRoute

                    map3d?.setNavigationRoute(
                        newRoute.geometryPoints.map { LatLng(it.latitude, it.longitude) }
                    )

                    navigationPolyline?.let { mapView.overlays.remove(it) }
                    navigationPolyline = Polyline().apply {
                        setPoints(newRoute.geometryPoints)
                        outlinePaint.color = Color.parseColor("#2563EB")
                        outlinePaint.strokeWidth = getZoomScaledLineWidthPx(5.5f)
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        outlinePaint.isAntiAlias = true
                    }
                    mapView.overlays.add(navigationPolyline)
                    mapView.invalidate()

                    lastKnownSegmentIndex = 0
                    isRecalculatingRoute = false
                    updateNavigationUi(start)
                }
            },
            onError = { error ->
                runOnUiThread {
                    isRecalculatingRoute = false
                    DebugLogger.error("❌ Reroute error: $error")
                }
            }
        )
    }

    private fun performZoomIn() {
        if (currentLayer == LAYER_3D) {
            val map = maplibreMap ?: return
            val curZoom = map.cameraPosition?.zoom ?: 16.0
            val target = if (isGpsEnabled && locationOverlay?.myLocation != null) {
                val loc = locationOverlay!!.myLocation
                LatLng(loc.latitude, loc.longitude)
            } else {
                map.cameraPosition?.target
            }
            if (target != null) {
                val newCamera = CameraPosition.Builder(map.cameraPosition)
                    .target(target)
                    .zoom((curZoom + 1.0).coerceAtMost(20.0))
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(newCamera))
            } else {
                map.animateCamera(CameraUpdateFactory.zoomIn())
            }
        } else {
            val myLoc = if (isGpsEnabled) locationOverlay?.myLocation else null
            if (myLoc != null) {
                val p = mapView.projection.toPixels(myLoc, null)
                mapView.controller.zoomInFixing(p.x, p.y)
            } else {
                mapView.controller.zoomIn()
            }
        }
    }

    private fun performZoomOut() {
        if (currentLayer == LAYER_3D) {
            val map = maplibreMap ?: return
            val curZoom = map.cameraPosition?.zoom ?: 16.0
            val target = if (isGpsEnabled && locationOverlay?.myLocation != null) {
                val loc = locationOverlay!!.myLocation
                LatLng(loc.latitude, loc.longitude)
            } else {
                map.cameraPosition?.target
            }
            if (target != null) {
                val newCamera = CameraPosition.Builder(map.cameraPosition)
                    .target(target)
                    .zoom((curZoom - 1.0).coerceAtLeast(2.0))
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(newCamera))
            } else {
                map.animateCamera(CameraUpdateFactory.zoomOut())
            }
        } else {
            val myLoc = if (isGpsEnabled) locationOverlay?.myLocation else null
            if (myLoc != null) {
                val p = mapView.projection.toPixels(myLoc, null)
                mapView.controller.zoomOutFixing(p.x, p.y)
            } else {
                mapView.controller.zoomOut()
            }
        }
    }

    // ─── Buttons Setup ──────────────────────────────────────────

    private fun setupButtons() {
        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            if (isRecordingTrack) {
                showExitRecordingWarningDialog()
            } else {
                finish()
            }
        }

        // Layer dropdown menu button
        findViewById<View>(R.id.btnLayerMenu)?.setOnClickListener { showLayerPopupMenu(it) }

        // 3D layer button
        findViewById<View>(R.id.btnLayer3D).setOnClickListener {
            if (currentLayer == LAYER_3D) switchLayer(layerBefore3d)
            else switchLayer(LAYER_3D)
        }

        // Zoom buttons
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { performZoomIn() }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { performZoomOut() }

        // My location button (center on GPS)
        findViewById<Button>(R.id.btnMyLocation).setOnClickListener { centerOnMyLocation() }

        // Destination Card Buttons
        findViewById<Button>(R.id.btnCancelDest).setOnClickListener { cancelDestinationSelection() }
        findViewById<Button>(R.id.btnSaveFavDest)?.setOnClickListener { saveCurrentDestinationAsFavorite() }
        findViewById<Button>(R.id.btnStartNav).setOnClickListener { startNavigation() }

        // Turn Banner Stop Nav Button
        findViewById<Button>(R.id.btnStopNav).setOnClickListener { stopNavigation() }

        // Import route
        findViewById<Button>(R.id.btnImportRoute).setOnClickListener { openFilePicker() }

        // Track record button
        findViewById<View>(R.id.btnRecordTrack).setOnClickListener { toggleTrackRecording() }

        // GPS toggle
        findViewById<Button>(R.id.btnGpsToggle).setOnClickListener { toggleGps() }

        // Favorites & Address Search
        findViewById<Button>(R.id.btnFavorites)?.setOnClickListener { showFavoritesAndSearchDialog() }

        // Route list toggle
        val btnRouteList = findViewById<Button>(R.id.btnRouteList)
        val routeListContainer = findViewById<LinearLayout>(R.id.routeListContainer)
        btnRouteList.setOnClickListener {
            routeListContainer.visibility = if (routeListContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Close route list
        findViewById<ImageView>(R.id.btnCloseRouteList).setOnClickListener {
            routeListContainer.visibility = View.GONE
        }

        // Clear all imported routes, POIs, and overlays
        findViewById<View>(R.id.btnClearAllRoutes)?.setOnClickListener {
            clearAllImportedOverlays()
        }

        // Map Settings Button (Theme & Cursor)
        findViewById<View>(R.id.btnMapSettings).setOnClickListener {
            showMapSettingsDialog()
        }

        setupTollButtonListener()
        setupWeatherButtonListener()
    }

    private fun updateWeatherButtonState(enabled: Boolean) {
        val btn = findViewById<Button>(R.id.btnWeatherToggle) ?: return
        if (enabled) {
            btn.setBackgroundColor(Color.parseColor("#0284C7")) // Sky Blue
            btn.alpha = 1.0f
        } else {
            btn.setBackgroundColor(Color.parseColor("#CC333333")) // Grey
            btn.alpha = 0.6f
        }
    }

    private fun updateWeatherButtonVisibility() {
        val hasRouteOrNav = isNavigating || loadedRoutes.isNotEmpty()
        findViewById<Button>(R.id.btnWeatherToggle)?.visibility = if (hasRouteOrNav) View.VISIBLE else View.GONE
    }

    private fun setupWeatherButtonListener() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        showRouteWeather = prefs.getBoolean("show_route_weather", true)
        updateWeatherButtonState(showRouteWeather)
        updateWeatherButtonVisibility()

        findViewById<Button>(R.id.btnWeatherToggle)?.setOnClickListener {
            showRouteWeather = !showRouteWeather
            prefs.edit().putBoolean("show_route_weather", showRouteWeather).apply()
            updateWeatherButtonState(showRouteWeather)

            if (showRouteWeather) {
                Toast.makeText(this, getString(R.string.toast_route_weather_on), Toast.LENGTH_SHORT).show()
                refreshRouteWeatherForecast(force = true)
            } else {
                Toast.makeText(this, getString(R.string.toast_route_weather_off), Toast.LENGTH_SHORT).show()
                clearRouteWeatherMarkers()
            }
        }
    }

    private fun refreshRouteWeatherForecast(force: Boolean = false) {
        if (!showRouteWeather) return

        val now = System.currentTimeMillis()
        if (!force && now - lastWeatherFetchMs < 60_000L) {
            return
        }

        val points = when {
            isNavigating && currentNavigationRoute != null -> currentNavigationRoute?.geometryPoints
            loadedRoutes.isNotEmpty() -> loadedRoutes.firstOrNull { it.visible }?.points
            else -> null
        }

        if (points.isNullOrEmpty() || points.size < 2) {
            clearRouteWeatherMarkers()
            return
        }

        val sampleCheckpoints = RouteWeatherHelper.sampleRoutePoints(points)
        if (sampleCheckpoints.isEmpty()) return

        lastWeatherFetchMs = now
        RouteWeatherHelper.fetchRouteWeather(sampleCheckpoints) { weatherPoints ->
            runOnUiThread {
                renderRouteWeatherMarkers(weatherPoints)
            }
        }
    }

    private fun renderRouteWeatherMarkers(weatherPoints: List<RouteWeatherHelper.RouteWeatherPoint>) {
        clearRouteWeatherMarkers()
        if (!showRouteWeather || weatherPoints.isEmpty()) return

        for (w in weatherPoints) {
            val badgeBitmap = RouteWeatherHelper.createWeatherBadgeBitmap(this, w)
            val marker = Marker(mapView).apply {
                position = w.point
                icon = android.graphics.drawable.BitmapDrawable(resources, badgeBitmap)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "${w.weatherEmoji} ${w.weatherDescription}"
                snippet = "${w.temperature.toInt()}°C | Yağış: %${w.precipitationProbability} (${w.precipitationMm} mm) | Rüzgar: ${w.windSpeedKmH.toInt()} km/s"
                setOnMarkerClickListener { _, _ ->
                    val msg = "${w.weatherEmoji} ${w.weatherDescription}\n" +
                            "${getString(R.string.weather_temperature, w.temperature)}\n" +
                            "${getString(R.string.weather_precipitation_prob, w.precipitationProbability, w.precipitationMm)}\n" +
                            "${getString(R.string.weather_wind, w.windSpeedKmH)}\n" +
                            getString(R.string.weather_distance_from_start, w.distanceFromStartKm)
                    Toast.makeText(this@MapActivity, msg, Toast.LENGTH_LONG).show()
                    true
                }
            }
            mapView.overlays.add(marker)
            weatherMarkers2d.add(marker)
        }
        mapView.invalidate()

        // 3D Map updates
        val weather3d = weatherPoints.map {
            Map3dOverlays.WeatherData(
                org.maplibre.android.geometry.LatLng(it.point.latitude, it.point.longitude),
                RouteWeatherHelper.createWeatherBadgeBitmap(this, it)
            )
        }
        map3d?.setWeatherPoints(weather3d)

        // Sync to MapStateHolder / Presentation
        MapStateHolder.updateWeatherPoints(weatherPoints)
    }

    private fun clearRouteWeatherMarkers() {
        for (m in weatherMarkers2d) {
            mapView.overlays.remove(m)
        }
        weatherMarkers2d.clear()
        map3d?.setWeatherPoints(emptyList())
        MapStateHolder.updateWeatherPoints(emptyList())
        mapView.invalidate()
    }

    // ─── Dangerous & Sharp Curve Warning System ──────────────────

    private fun refreshCurveWarnings() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        showCurveWarnings = prefs.getBoolean("show_curve_warnings", true)
        if (!showCurveWarnings) {
            clearCurveMarkers()
            return
        }

        val points = when {
            isNavigating && currentNavigationRoute != null -> currentNavigationRoute?.geometryPoints
            loadedRoutes.isNotEmpty() -> loadedRoutes.firstOrNull { it.visible }?.points
            else -> null
        }

        if (points.isNullOrEmpty() || points.size < 4) {
            clearCurveMarkers()
            return
        }

        detectedCurvesList = CurveWarningHelper.detectCurves(points)
        renderCurveMarkers(detectedCurvesList)
    }

    private fun renderCurveMarkers(curves: List<CurveWarningHelper.CurvePoint>) {
        for (m in curveMarkers2d) {
            mapView.overlays.remove(m)
        }
        curveMarkers2d.clear()

        if (!showCurveWarnings || curves.isEmpty()) {
            map3d?.setCurvePoints(emptyList())
            MapStateHolder.updateCurves(emptyList())
            mapView.invalidate()
            return
        }

        for (c in curves) {
            val badgeBitmap = CurveWarningHelper.createCurveBadgeBitmap(this, c)
            val localizedDesc = CurveWarningHelper.getLocalizedDescription(this, c)
            val marker = Marker(mapView).apply {
                position = c.point
                icon = android.graphics.drawable.BitmapDrawable(resources, badgeBitmap)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = localizedDesc
                setOnMarkerClickListener { _, _ ->
                    val msg = "⚠️ $localizedDesc\n" +
                            "${getString(R.string.curve_turn_angle, c.turnAngleDeg.toInt())}\n" +
                            getString(R.string.weather_distance_from_start, c.distanceFromStartMeters / 1000.0)
                    Toast.makeText(this@MapActivity, msg, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            mapView.overlays.add(marker)
            curveMarkers2d.add(marker)
        }
        mapView.invalidate()

        // 3D Map updates
        val curves3d = curves.map {
            Map3dOverlays.CurveData(
                org.maplibre.android.geometry.LatLng(it.point.latitude, it.point.longitude),
                CurveWarningHelper.createCurveBadgeBitmap(this, it)
            )
        }
        map3d?.setCurvePoints(curves3d)

        // Sync to MapStateHolder / Presentation
        MapStateHolder.updateCurves(curves)
    }

    private fun clearCurveMarkers() {
        for (m in curveMarkers2d) {
            mapView.overlays.remove(m)
        }
        curveMarkers2d.clear()
        detectedCurvesList = emptyList()
        map3d?.setCurvePoints(emptyList())
        MapStateHolder.updateCurves(emptyList())
        mapView.invalidate()
    }

    private fun updateTollButtonState(avoidTolls: Boolean) {
        val btnTolls = findViewById<Button>(R.id.btnTollToggle) ?: return
        if (avoidTolls) {
            btnTolls.setBackgroundColor(Color.parseColor("#CC333333"))
            btnTolls.alpha = 0.6f
        } else {
            btnTolls.setBackgroundColor(Color.parseColor("#CC16A34A"))
            btnTolls.alpha = 1.0f
        }
    }

    private fun setupTollButtonListener() {
        val btnTolls = findViewById<Button>(R.id.btnTollToggle) ?: return
        btnTolls.setOnClickListener {
            val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
            val currentAvoid = prefs.getBoolean("nav_avoid_tolls", false)
            val newAvoid = !currentAvoid
            prefs.edit().putBoolean("nav_avoid_tolls", newAvoid).apply()
            updateTollButtonState(newAvoid)

            val statusMsg = if (newAvoid) "🛣️ ${getString(R.string.nav_avoid_tolls)}" else "🛣️ ${getString(R.string.nav_toll_roads_toggle)}"
            Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()

            if (isNavigating) {
                val myLoc = locationOverlay?.myLocation
                val dest = selectedDestination
                if (myLoc != null && myLoc.latitude != 0.0 && myLoc.longitude != 0.0 && dest != null) {
                    recalculateRoute(myLoc, dest)
                }
            }
        }
    }

    // ─── TFT Black Bar Margin Adjustment ────────────────────────

    private fun applyTftPadding(topDp: Int, bottomDp: Int) {
        val density = resources.displayMetrics.density
        val topPx = (topDp * density).toInt()
        val bottomPx = (bottomDp * density).toInt()

        val topBarView = findViewById<View>(R.id.topBlackBar) ?: return
        val bottomBarView = findViewById<View>(R.id.bottomBlackBar) ?: return

        topBarView.layoutParams = topBarView.layoutParams.apply { height = topPx }
        bottomBarView.layoutParams = bottomBarView.layoutParams.apply { height = bottomPx }
    }

    private fun showTftPaddingDialog() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        var currentTop = prefs.getInt("map_top_padding_dp", 0)
        var currentBottom = prefs.getInt("map_bottom_padding_dp", 0)

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_tft_padding, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val tvTopLabel = view.findViewById<TextView>(R.id.tvTopLabel)
        val btnTopMinus = view.findViewById<Button>(R.id.btnTopMinus)
        val btnTopPlus = view.findViewById<Button>(R.id.btnTopPlus)
        val seekBarTop = view.findViewById<android.widget.SeekBar>(R.id.seekBarTop)

        val tvBottomLabel = view.findViewById<TextView>(R.id.tvBottomLabel)
        val btnBottomMinus = view.findViewById<Button>(R.id.btnBottomMinus)
        val btnBottomPlus = view.findViewById<Button>(R.id.btnBottomPlus)
        val seekBarBottom = view.findViewById<android.widget.SeekBar>(R.id.seekBarBottom)

        val btnReset = view.findViewById<Button>(R.id.btnResetPadding)
        val btnSave = view.findViewById<Button>(R.id.btnSavePadding)

        fun updateUI() {
            currentTop = currentTop.coerceIn(0, 200)
            currentBottom = currentBottom.coerceIn(0, 200)

            tvTopLabel.text = getString(R.string.tft_fit_top_label, currentTop)
            tvBottomLabel.text = getString(R.string.tft_fit_bottom_label, currentBottom)

            seekBarTop.progress = currentTop
            seekBarBottom.progress = currentBottom

            applyTftPadding(currentTop, currentBottom)
        }

        updateUI()

        btnTopMinus.setOnClickListener { currentTop -= 5; updateUI() }
        btnTopPlus.setOnClickListener { currentTop += 5; updateUI() }
        seekBarTop.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { currentTop = progress; updateUI() }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        btnBottomMinus.setOnClickListener { currentBottom -= 5; updateUI() }
        btnBottomPlus.setOnClickListener { currentBottom += 5; updateUI() }
        seekBarBottom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { currentBottom = progress; updateUI() }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        btnReset.setOnClickListener {
            currentTop = 0
            currentBottom = 0
            updateUI()
        }

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putInt("map_top_padding_dp", currentTop)
                putInt("map_bottom_padding_dp", currentBottom)
                apply()
            }
            Toast.makeText(this, getString(R.string.toast_tft_fit_saved), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ─── Layer Switching (3 modes) ──────────────────────────────

    private fun showLayerPopupMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add(0, LAYER_MAPS, 0, getString(R.string.map_layer_maps))
        popup.menu.add(0, LAYER_TOPO, 1, getString(R.string.map_layer_topo))
        popup.menu.add(0, LAYER_SATELLITE, 2, getString(R.string.map_layer_sat))
        popup.menu.add(0, LAYER_GOOGLE_MAPS, 3, getString(R.string.map_layer_google_maps))
        popup.menu.add(0, LAYER_GOOGLE_SAT, 4, getString(R.string.map_layer_google_sat))
        popup.menu.add(0, LAYER_GOOGLE_HYBRID, 5, getString(R.string.map_layer_google_hybrid))
        popup.menu.add(0, LAYER_OFFLINE, 6, getString(R.string.map_layer_offline))
        popup.menu.add(0, 999, 7, getString(R.string.map_label_scale_title))
        popup.menu.add(0, 998, 8, getString(R.string.nav_route_options))

        popup.setOnMenuItemClickListener { item ->
            val selectedLayer = item.itemId
            if (selectedLayer == 999) {
                showMapScaleDialog()
            } else if (selectedLayer == 998) {
                showRouteOptionsDialog()
            } else if (selectedLayer == LAYER_OFFLINE && currentLayer == LAYER_OFFLINE) {
                selectOfflineMapFile()
            } else {
                switchLayer(selectedLayer)
            }
            true
        }
        popup.show()
    }

    private fun switchLayer(layer: Int) {
        val prevLayer = currentLayer
        currentLayer = layer
        MapStateHolder.currentLayer = layer
        MapStateHolder.notifyLayerChanged()
        if (layer == LAYER_MAPS || layer == LAYER_TOPO || layer == LAYER_SATELLITE ||
            layer == LAYER_GOOGLE_MAPS || layer == LAYER_GOOGLE_SAT || layer == LAYER_GOOGLE_HYBRID ||
            layer == LAYER_OFFLINE
        ) {
            currentBaseLayer = layer
        }

        saveLayerPrefs()

        if (layer == LAYER_3D) {
            if (prevLayer != LAYER_3D) layerBefore3d = prevLayer
            saveLayerPrefs()
            enter3dMode()
            updateLayerButtons()
            apply3dBaseLayer()
            return
        }
        leave3dMode()

        if (layer != LAYER_OFFLINE && mapView.tileProvider !is org.osmdroid.tileprovider.MapTileProviderBasic) {
            mapView.tileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(this)
        }

        when (layer) {
            LAYER_MAPS -> mapView.setTileSource(TileSourceFactory.MAPNIK)
            LAYER_TOPO -> mapView.setTileSource(openTopoTileSource)
            LAYER_SATELLITE -> mapView.setTileSource(satelliteTileSource)
            LAYER_GOOGLE_MAPS -> mapView.setTileSource(googleMapsTileSource)
            LAYER_GOOGLE_SAT -> mapView.setTileSource(googleSatTileSource)
            LAYER_GOOGLE_HYBRID -> mapView.setTileSource(googleHybridTileSource)
            LAYER_OFFLINE -> {
                val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
                val mapPath = prefs.getString("offline_map_path", null)
                val mapFile = if (!mapPath.isNullOrEmpty()) java.io.File(mapPath) else null

                if (mapFile != null && mapFile.exists()) {
                    try {
                        val theme = org.mapsforge.map.rendertheme.InternalRenderTheme.DEFAULT
                        val fromFiles = org.osmdroid.mapsforge.MapsForgeTileSource.createFromFiles(
                            arrayOf(mapFile),
                            theme,
                            "DEFAULT"
                        )
                        val receiver = org.osmdroid.tileprovider.util.SimpleRegisterReceiver(this)
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
                    } catch (e: Exception) {
                        DebugLogger.error("❌ MapsForge tile source error: ${e.message}")
                        Toast.makeText(this, getString(R.string.error_invalid_map_file), Toast.LENGTH_SHORT).show()
                        selectOfflineMapFile()
                    }
                } else {
                    Toast.makeText(this, getString(R.string.toast_select_offline_map), Toast.LENGTH_LONG).show()
                    selectOfflineMapFile()
                }
            }
        }

        // MapsForge offline maps render vector tiles at device DPI, so osmdroid's
        // tilesScaleFactor would double-scale them.  Disable tile scaling for offline
        // maps and restore the user's preference for online raster layers.
        if (layer == LAYER_OFFLINE) {
            mapView.tilesScaleFactor = 1.0f
            mapView.isTilesScaledToDpi = false
        } else {
            val savedScale = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
                .getFloat("map_tiles_scale_factor", 1.0f)
            applyMapTileScale(savedScale)
        }

        applyMapTheme()
        updateLayerButtons()
        mapView.invalidate()
    }

    private fun updateLayerButtons() {
        val btnMenu = findViewById<TextView>(R.id.btnLayerMenu) ?: return
        val btn3D = findViewById<View>(R.id.btnLayer3D) ?: return

        val activeColor = Color.parseColor("#2979FF")
        val inactiveColor = Color.parseColor("#555555")

        val currentLabel = when (currentBaseLayer) {
            LAYER_TOPO -> getString(R.string.map_layer_topo)
            LAYER_SATELLITE -> getString(R.string.map_layer_sat)
            LAYER_GOOGLE_MAPS -> getString(R.string.map_layer_google_maps)
            LAYER_GOOGLE_SAT -> getString(R.string.map_layer_google_sat)
            LAYER_GOOGLE_HYBRID -> getString(R.string.map_layer_google_hybrid)
            LAYER_OFFLINE -> getString(R.string.map_layer_offline)
            else -> getString(R.string.map_layer_maps)
        }

        btnMenu.text = "$currentLabel ▼"
        btnMenu.setBackgroundColor(if (currentLayer != LAYER_3D) activeColor else inactiveColor)
        btn3D.setBackgroundColor(if (currentLayer == LAYER_3D) activeColor else inactiveColor)
    }

    // ─── Map Text & Label Scaling ───────────────────────────────

    private fun applyMapTileScale(scaleFactor: Float) {
        mapView.tilesScaleFactor = scaleFactor
        if (scaleFactor != 1.0f) {
            mapView.isTilesScaledToDpi = true
        }
        refreshPoiOverlays(scaleFactor)
        mapView.invalidate()

        if (currentLayer == LAYER_3D) {
            apply3dBaseLayer()
        }
    }

    private fun showMapScaleDialog() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val currentScale = prefs.getFloat("map_tiles_scale_factor", 1.0f)

        val scales = arrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = arrayOf(
            "%100 (Standart / Normal)",
            "%125 (Orta / Medium)",
            "%150 (Büyük / Large)",
            "%175 (Çok Büyük / Extra Large)",
            "%200 (Maksimum / 2x Large)"
        )

        val initialCheckedIndex = scales.indexOfFirst { kotlin.math.abs(it - currentScale) < 0.05f }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_label_scale_title)
            .setSingleChoiceItems(labels, initialCheckedIndex) { dialog, which ->
                val selectedScale = scales[which]
                prefs.edit().putFloat("map_tiles_scale_factor", selectedScale).apply()
                applyMapTileScale(selectedScale)
                Toast.makeText(this, "${getString(R.string.map_label_scale_title)}: ${(selectedScale * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.map_btn_cancel, null)
            .show()
    }

    // ─── Navigation Route Preferences (Toll-Free, No Highway, Shortest) ───

    private fun showRouteOptionsDialog() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val avoidTolls = prefs.getBoolean("nav_avoid_tolls", false)
        val avoidHighways = prefs.getBoolean("nav_avoid_highways", false)
        val useShortest = prefs.getBoolean("nav_use_shortest", false)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val cbTolls = androidx.appcompat.widget.AppCompatCheckBox(this).apply {
            text = getString(R.string.nav_avoid_tolls)
            isChecked = avoidTolls
            setTextColor(Color.WHITE)
        }
        val cbHighways = androidx.appcompat.widget.AppCompatCheckBox(this).apply {
            text = getString(R.string.nav_avoid_highways)
            isChecked = avoidHighways
            setTextColor(Color.WHITE)
        }

        val tvPrefLabel = TextView(this).apply {
            text = getString(R.string.nav_route_preference)
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            setPadding(0, 20, 0, 10)
        }

        val rgPref = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val rbFastest = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.nav_type_fastest)
            isChecked = !useShortest
            setTextColor(Color.WHITE)
        }
        val rbShortest = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.nav_type_shortest)
            isChecked = useShortest
            setTextColor(Color.WHITE)
        }
        rgPref.addView(rbFastest)
        rgPref.addView(rbShortest)

        layout.addView(cbTolls)
        layout.addView(cbHighways)
        layout.addView(tvPrefLabel)
        layout.addView(rgPref)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.nav_route_options)
            .setView(layout)
            .setPositiveButton(R.string.map_btn_apply) { _, _ ->
                val newAvoidTolls = cbTolls.isChecked
                val newAvoidHighways = cbHighways.isChecked
                val newUseShortest = rbShortest.isChecked

                prefs.edit()
                    .putBoolean("nav_avoid_tolls", newAvoidTolls)
                    .putBoolean("nav_avoid_highways", newAvoidHighways)
                    .putBoolean("nav_use_shortest", newUseShortest)
                    .apply()

                updateTollButtonState(newAvoidTolls)

                if (isNavigating) {
                    val myLoc = locationOverlay?.myLocation
                    val dest = selectedDestination
                    if (myLoc != null && myLoc.latitude != 0.0 && myLoc.longitude != 0.0 && dest != null) {
                        recalculateRoute(myLoc, dest)
                    }
                }

                Toast.makeText(this, "✅ ${getString(R.string.nav_route_options)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.map_btn_cancel, null)
            .show()
    }

    // ─── Favorites & Geocoding ─────────────────────────────────

    private fun saveCurrentDestinationAsFavorite() {
        val dest = selectedDestination ?: return
        val defaultName = destinationMarker?.title?.takeIf { it != getString(R.string.nav_destination_selected) }
            ?: String.format(Locale.US, "Location (%.4f, %.4f)", dest.latitude, dest.longitude)

        val input = EditText(this).apply {
            setText(defaultName)
            hint = getString(R.string.dialog_save_fav_prompt)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_save_fav_title)
            .setMessage(R.string.dialog_save_fav_prompt)
            .setView(input)
            .setPositiveButton(R.string.map_btn_apply) { _, _ ->
                val favName = input.text.toString().trim().ifEmpty { defaultName }
                val fav = FavoriteLocation(
                    name = favName,
                    address = String.format(Locale.US, "%.5f, %.5f", dest.latitude, dest.longitude),
                    latitude = dest.latitude,
                    longitude = dest.longitude
                )
                FavoritesManager.saveFavorite(this, fav)
                Toast.makeText(this, getString(R.string.toast_favorite_saved, favName), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.map_btn_cancel, null)
            .show()
    }

    private fun showFavoritesAndSearchDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_favorites_search, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()

        val etSearch = view.findViewById<EditText>(R.id.etAddressQuery)
        val btnSearch = view.findViewById<Button>(R.id.btnSearchAddress)
        val tvSectionTitle = view.findViewById<TextView>(R.id.tvSectionTitle)
        val container = view.findViewById<LinearLayout>(R.id.llFavoritesList)
        val btnClose = view.findViewById<Button>(R.id.btnCloseFavDialog)

        btnClose?.setOnClickListener { dialog.dismiss() }

        fun renderFavorites() {
            container.removeAllViews()
            tvSectionTitle.text = getString(R.string.label_saved_favorites)
            val favorites = FavoritesManager.getFavorites(this)
            if (favorites.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = getString(R.string.no_favorites_yet)
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12f
                    setPadding(16, 16, 16, 16)
                }
                container.addView(emptyTv)
                return
            }

            for (fav in favorites) {
                val item = LayoutInflater.from(this).inflate(R.layout.item_favorite_search, container, false)
                item.findViewById<TextView>(R.id.tvItemTitle).text = fav.name
                item.findViewById<TextView>(R.id.tvItemSubtitle).text = fav.address.ifEmpty { "%.5f, %.5f".format(fav.latitude, fav.longitude) }

                item.findViewById<Button>(R.id.btnNavToItem).setOnClickListener {
                    dialog.dismiss()
                    selectDestinationPoint(GeoPoint(fav.latitude, fav.longitude), fav.name)
                    startNavigation()
                }

                val btnAction = item.findViewById<Button>(R.id.btnActionItem)
                btnAction.text = "🗑️"
                btnAction.setOnClickListener {
                    FavoritesManager.deleteFavorite(this, fav.id)
                    Toast.makeText(this, getString(R.string.toast_favorite_deleted), Toast.LENGTH_SHORT).show()
                    renderFavorites()
                }

                container.addView(item)
            }
        }

        fun doSearch() {
            val query = etSearch.text.toString().trim()
            if (query.isEmpty()) {
                renderFavorites()
                return
            }

            tvSectionTitle.text = "${getString(R.string.label_search_results)}: \"$query\""
            container.removeAllViews()
            val loadingTv = TextView(this).apply {
                text = getString(R.string.nav_calculating)
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                setPadding(16, 16, 16, 16)
            }
            container.addView(loadingTv)

            GeocoderHelper.searchAddress(query) { results ->
                container.removeAllViews()
                if (results.isEmpty()) {
                    val noResultsTv = TextView(this).apply {
                        text = getString(R.string.no_results_found)
                        setTextColor(Color.parseColor("#EF4444"))
                        textSize = 12f
                        setPadding(16, 16, 16, 16)
                    }
                    container.addView(noResultsTv)
                    return@searchAddress
                }

                for (res in results) {
                    val item = LayoutInflater.from(this).inflate(R.layout.item_favorite_search, container, false)
                    item.findViewById<TextView>(R.id.tvItemTitle).text = res.name
                    item.findViewById<TextView>(R.id.tvItemSubtitle).text = res.address

                    item.findViewById<Button>(R.id.btnNavToItem).setOnClickListener {
                        dialog.dismiss()
                        selectDestinationPoint(GeoPoint(res.latitude, res.longitude), res.name)
                        startNavigation()
                    }

                    val btnAction = item.findViewById<Button>(R.id.btnActionItem)
                    btnAction.text = "⭐"
                    btnAction.setOnClickListener {
                        val fav = FavoriteLocation(
                            name = res.name,
                            address = res.address,
                            latitude = res.latitude,
                            longitude = res.longitude
                        )
                        FavoritesManager.saveFavorite(this, fav)
                        btnAction.text = "✅"
                        Toast.makeText(this, getString(R.string.toast_favorite_saved, res.name), Toast.LENGTH_SHORT).show()
                    }

                    container.addView(item)
                }
            }
        }

        btnSearch?.setOnClickListener { doSearch() }
        etSearch?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        renderFavorites()
        dialog.show()
    }



    private fun centerOnMyLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        if (!isGpsEnabled) {
            toggleGps()
        }

        locationOverlay?.myLocation?.let { loc ->
            if (currentLayer == LAYER_3D) {
                follow3d = true
                maplibreMap?.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16.0)
                )
            } else {
                mapView.controller.animateTo(loc)
            }
        } ?: run {
            Toast.makeText(this, getString(R.string.map_waiting_gps), Toast.LENGTH_SHORT).show()
            if (currentLayer != LAYER_3D) locationOverlay?.enableFollowLocation()
        }
    }

    // ─── GPS Tracking ───────────────────────────────────────────

    private fun toggleGps() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        val btnGps = findViewById<Button>(R.id.btnGpsToggle)

        if (isGpsEnabled) {
            locationOverlay?.disableMyLocation()
            locationOverlay?.disableFollowLocation()
            mapView.overlays.remove(locationOverlay)
            locationOverlay = null
            isGpsEnabled = false
            mapView.mapOrientation = 0f
            btnGps.text = "📡"
            btnGps.setBackgroundColor(Color.parseColor("#CC333333"))
            findViewById<View>(R.id.gpsInfoCard)?.visibility = View.GONE
        } else {
            val provider = GpsMyLocationProvider(this)
            provider.locationUpdateMinTime = 2000
            provider.locationUpdateMinDistance = 5f

            locationOverlay = object : MyLocationNewOverlay(provider, mapView) {
                override fun onLocationChanged(location: android.location.Location?, source: org.osmdroid.views.overlay.mylocation.IMyLocationProvider?) {
                    super.onLocationChanged(location, source)
                    if (location != null) {
                        if (location.hasBearing() && (location.speed > 0.5f || isNavigating)) {
                            runOnUiThread {
                                mapView.mapOrientation = -location.bearing
                            }
                        }
                        val speedKmH = (location.speed * 3.6f).toInt().coerceAtLeast(0)
                        val altText = if (location.hasAltitude()) "${location.altitude.toInt()} m" else "--- m"

                        MapStateHolder.updateLocation(
                            GeoPoint(location.latitude, location.longitude),
                            if (location.hasBearing()) location.bearing else 0f,
                            speedKmH,
                            if (location.hasAltitude()) location.altitude else 0.0
                        )

                        SpeedLimitHelper.getSpeedLimit(location.latitude, location.longitude) { speedLimit ->
                            val signLayout = findViewById<View>(R.id.layoutSpeedLimitSign)
                            val tvLimit = findViewById<TextView>(R.id.tvSpeedLimit)
                            val gpsCard = findViewById<View>(R.id.gpsInfoCard)
                            val tvSpeed = findViewById<TextView>(R.id.tvGpsSpeed)
                            val tvUnit = findViewById<TextView>(R.id.tvGpsSpeedUnit)

                            if (speedLimit != null && speedLimit > 0) {
                                signLayout?.visibility = View.VISIBLE
                                tvLimit?.text = "$speedLimit"
                                if (speedKmH > speedLimit) {
                                    gpsCard?.setBackgroundResource(R.drawable.bg_speedometer_alert)
                                    tvSpeed?.setTextColor(Color.WHITE)
                                    tvUnit?.setTextColor(Color.WHITE)
                                } else {
                                    gpsCard?.setBackgroundResource(R.drawable.bg_speedometer_card)
                                    tvSpeed?.setTextColor(Color.parseColor("#38BDF8"))
                                    tvUnit?.setTextColor(Color.parseColor("#94A3B8"))
                                }
                            } else {
                                signLayout?.visibility = View.GONE
                                gpsCard?.setBackgroundResource(R.drawable.bg_speedometer_card)
                                tvSpeed?.setTextColor(Color.parseColor("#38BDF8"))
                                tvUnit?.setTextColor(Color.parseColor("#94A3B8"))
                            }
                        }

                        runOnUiThread {
                            findViewById<TextView>(R.id.tvGpsSpeed)?.text = "$speedKmH"
                            findViewById<TextView>(R.id.tvGpsAltitude)?.text = altText
                        }
                        val myGeo = GeoPoint(location.latitude, location.longitude)

                        if (isNavigating) {
                            runOnUiThread {
                                updateNavigationUi(myGeo)
                            }
                        }
                        if (currentLayer == LAYER_3D) {
                            val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
                            update3dLocationMarker(location.latitude, location.longitude, bearing)
                        }
                        if (isRecordingTrack) {
                            val tp = TrackPoint(
                                lat = location.latitude,
                                lon = location.longitude,
                                ele = if (location.hasAltitude()) location.altitude else 0.0,
                                speed = if (location.hasSpeed()) location.speed else 0f,
                                timeMs = if (location.time > 0) location.time else System.currentTimeMillis()
                            )
                            recordedTrackPoints.add(tp)
                            GpxRecorderHelper.saveTempPoints(this@MapActivity, recordingColor, recordedTrackPoints)
                            runOnUiThread {
                                updateRecordedPolylineOnMap()
                            }
                        }
                    }
                }
            }.apply {
                enableMyLocation()
                enableFollowLocation()
            }
            applyLocationCursorStyle(locationOverlay)
            mapView.overlays.add(locationOverlay)
            isGpsEnabled = true
            btnGps.text = "🛰️"
            btnGps.setBackgroundColor(Color.parseColor("#2E7D32"))
            findViewById<View>(R.id.gpsInfoCard)?.visibility = View.VISIBLE
        }
        mapView.invalidate()
    }

    // ─── GPX Track Recording Logic ────────────────────────────────

    private fun updateRecordedPolylineOnMap() {
        recordedPolyline?.let { mapView.overlays.remove(it) }
        map3d?.setTrack(recordedTrackPoints.map { LatLng(it.lat, it.lon) }, recordingColor)
        if (recordedTrackPoints.isEmpty()) return

        val geoPoints = recordedTrackPoints.map { GeoPoint(it.lat, it.lon) }
        recordedPolyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = recordingColor
            outlinePaint.strokeWidth = getZoomScaledLineWidthPx(5.5f)
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
        }

        // Add at the VERY TOP of overlays so it renders on top of all imported routes
        mapView.overlays.add(recordedPolyline)
        mapView.invalidate()
    }

    private fun toggleTrackRecording() {
        if (isRecordingTrack) {
            showExportGpxDialog()
        } else {
            if (!isGpsEnabled) {
                toggleGps()
            }
            isRecordingTrack = true
            recordingStartTimeMs = System.currentTimeMillis()
            recordedTrackPoints.clear()
            recordTimerHandler.post(recordTimerRunnable)
            GpxRecorderHelper.saveTempPoints(this, recordingColor, recordedTrackPoints)
            val btnRecord = findViewById<TextView>(R.id.btnRecordTrack)
            btnRecord?.text = "●"
            btnRecord?.setTextColor(Color.parseColor("#FF0000"))
            btnRecord?.setBackgroundColor(Color.parseColor("#CC333333"))
            Toast.makeText(this, getString(R.string.map_btn_record_start), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportGpxDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_gpx_export, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        val tvStatsDistance = view.findViewById<TextView>(R.id.tvGpxStatsDistance)
        val tvStatsDuration = view.findViewById<TextView>(R.id.tvGpxStatsDuration)
        val tvStatsPoints = view.findViewById<TextView>(R.id.tvGpxStatsPoints)
        val etFileName = view.findViewById<EditText>(R.id.etGpxFileName)
        val btnSave = view.findViewById<Button>(R.id.btnSaveGpx)
        val btnDiscard = view.findViewById<Button>(R.id.btnDiscardGpx)

        var distMeters = 0.0
        for (i in 0 until recordedTrackPoints.size - 1) {
            val p1 = recordedTrackPoints[i]
            val p2 = recordedTrackPoints[i + 1]
            val results = FloatArray(1)
            android.location.Location.distanceBetween(p1.lat, p1.lon, p2.lat, p2.lon, results)
            distMeters += results[0]
        }
        val distKm = distMeters / 1000.0
        val elapsedSec = ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).coerceAtLeast(0)
        val hours = elapsedSec / 3600
        val mins = (elapsedSec % 3600) / 60
        val secs = elapsedSec % 60
        val durationStr = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

        tvStatsDistance.text = String.format(Locale.US, "Mesafe: %.2f km", distKm)
        tvStatsDuration.text = "Süre: $durationStr"
        tvStatsPoints.text = "Nokta Sayısı: ${recordedTrackPoints.size}"

        val defaultName = "Track_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        etFileName.setText(defaultName)

        var selectedLineColor = recordingColor
        val colorViews = listOf(
            view.findViewById<View>(R.id.gpxColorRed) to Color.parseColor("#EF4444"),
            view.findViewById<View>(R.id.gpxColorOrange) to Color.parseColor("#F97316"),
            view.findViewById<View>(R.id.gpxColorCyan) to Color.parseColor("#06B6D4"),
            view.findViewById<View>(R.id.gpxColorGreen) to Color.parseColor("#10B981"),
            view.findViewById<View>(R.id.gpxColorMagenta) to Color.parseColor("#D500F9"),
            view.findViewById<View>(R.id.gpxColorYellow) to Color.parseColor("#EAB308")
        )

        fun updateColorSelection() {
            colorViews.forEach { (v, col) ->
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    if (col == selectedLineColor) {
                        setStroke(6, Color.WHITE)
                    } else {
                        setStroke(2, Color.parseColor("#475569"))
                    }
                }
                v.background = drawable
            }
        }

        colorViews.forEach { (v, col) ->
            v.setOnClickListener {
                selectedLineColor = col
                recordingColor = col
                updateRecordedPolylineOnMap()
                updateColorSelection()
            }
        }
        updateColorSelection()

        btnSave.setOnClickListener {
            val inputName = etFileName.text.toString().trim().ifEmpty { defaultName }
            val gpxContent = GpxRecorderHelper.generateGpxString(inputName, recordedTrackPoints)
            val savedUri = GpxRecorderHelper.saveGpxToDownloads(this, inputName, gpxContent)

            if (savedUri != null) {
                Toast.makeText(this, getString(R.string.toast_gpx_saved, inputName), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to save GPX file", Toast.LENGTH_SHORT).show()
            }

            stopRecordingAndClear()
            dialog.dismiss()
        }

        btnDiscard.setOnClickListener {
            stopRecordingAndClear()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun stopRecordingAndClear() {
        isRecordingTrack = false
        recordTimerHandler.removeCallbacks(recordTimerRunnable)
        recordedPolyline?.let { mapView.overlays.remove(it) }
        recordedPolyline = null
        recordedTrackPoints.clear()
        GpxRecorderHelper.clearTempPoints(this)
        val btnRecord = findViewById<TextView>(R.id.btnRecordTrack)
        btnRecord?.text = "●"
        btnRecord?.setTextColor(Color.parseColor("#FFFFFF"))
        btnRecord?.setBackgroundColor(Color.parseColor("#CC333333"))
        mapView.invalidate()
    }

    private fun showExitRecordingWarningDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_exit_recording_title))
            .setMessage(getString(R.string.dialog_exit_recording_msg))
            .setPositiveButton(getString(R.string.btn_stop_and_save)) { _, _ ->
                showExportGpxDialog()
            }
            .setNegativeButton(getString(R.string.btn_keep_recording)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.btn_gpx_discard)) { _, _ ->
                stopRecordingAndClear()
                finish()
            }
            .show()
    }

    private fun applyMapTheme() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val themeMode = prefs.getInt("map_theme_mode", MapThemeHelper.THEME_AUTO)
        MapThemeHelper.applyTheme(this, mapView, themeMode)
    }

    private fun applyLocationCursorStyle(overlay: MyLocationNewOverlay? = locationOverlay) {
        val targetOverlay = overlay ?: return
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val shape = prefs.getInt("map_cursor_shape", LocationCursorHelper.SHAPE_NAV_ARROW)
        val color = prefs.getInt("map_cursor_color", LocationCursorHelper.COLOR_PRESETS[0])

        val cursorBmp = LocationCursorHelper.createCursorBitmap(this, shape, color)
        targetOverlay.setPersonIcon(cursorBmp)
        targetOverlay.setDirectionIcon(cursorBmp)
        targetOverlay.setPersonAnchor(0.5f, 0.5f)
        targetOverlay.setDirectionAnchor(0.5f, 0.5f)
        refresh3dCursor()
    }

    private fun showMapSettingsDialog() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        var selectedTheme = prefs.getInt("map_theme_mode", MapThemeHelper.THEME_AUTO)
        var selectedShape = prefs.getInt("map_cursor_shape", LocationCursorHelper.SHAPE_NAV_ARROW)
        var selectedColor = prefs.getInt("map_cursor_color", LocationCursorHelper.COLOR_PRESETS[0])
        var selectedTrackColor = prefs.getInt("map_recording_color", Color.parseColor("#EF4444"))

        val view = layoutInflater.inflate(R.layout.dialog_map_settings, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        // Hero Preview views
        val imgPreview = view.findViewById<ImageView>(R.id.imgCursorPreview)
        val viewHeroTrack = view.findViewById<View>(R.id.viewHeroDemoTrack)
        val previewMapBox = view.findViewById<View>(R.id.previewMapBox)
        val tvPreviewDetails = view.findViewById<TextView>(R.id.tvHeroPreviewDetails)

        // Close & Action buttons
        val btnClose = view.findViewById<View>(R.id.btnCloseSettings)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelMapSettings)
        val btnSave = view.findViewById<Button>(R.id.btnSaveMapSettings)

        // Theme Segmented Buttons
        val themeButtons = listOf(
            view.findViewById<Button>(R.id.btnThemeDay) to MapThemeHelper.THEME_DAY,
            view.findViewById<Button>(R.id.btnThemeNight) to MapThemeHelper.THEME_NIGHT,
            view.findViewById<Button>(R.id.btnThemeAuto) to MapThemeHelper.THEME_AUTO
        )

        fun updateThemeButtons() {
            themeButtons.forEach { (btn, theme) ->
                if (theme == selectedTheme) {
                    btn.setBackgroundResource(R.drawable.bg_settings_pill_active)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundResource(R.drawable.bg_settings_pill_inactive)
                    btn.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        fun updatePreview() {
            val bmp = LocationCursorHelper.createCursorBitmap(this, selectedShape, selectedColor, 52)
            imgPreview.setImageBitmap(bmp)
            viewHeroTrack.setBackgroundColor(selectedTrackColor)

            val isNightMode = when (selectedTheme) {
                MapThemeHelper.THEME_DAY -> false
                MapThemeHelper.THEME_NIGHT -> true
                else -> {
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    hour < 6 || hour >= 19
                }
            }
            previewMapBox.setBackgroundColor(if (isNightMode) Color.parseColor("#0F172A") else Color.parseColor("#CBD5E1"))

            val themeName = when (selectedTheme) {
                MapThemeHelper.THEME_DAY -> getString(R.string.map_theme_day)
                MapThemeHelper.THEME_NIGHT -> getString(R.string.map_theme_night)
                else -> getString(R.string.map_theme_auto)
            }
            val shapeName = when (selectedShape) {
                LocationCursorHelper.SHAPE_NAV_ARROW -> getString(R.string.cursor_shape_arrow)
                LocationCursorHelper.SHAPE_MOTORCYCLE -> getString(R.string.cursor_shape_motorcycle)
                LocationCursorHelper.SHAPE_CIRCLE_DOT -> getString(R.string.cursor_shape_circle)
                LocationCursorHelper.SHAPE_CROSSHAIR -> getString(R.string.cursor_shape_crosshair)
                else -> getString(R.string.cursor_shape_pin)
            }
            tvPreviewDetails.text = "$themeName • $shapeName"
        }

        themeButtons.forEach { (btn, theme) ->
            btn.setOnClickListener {
                selectedTheme = theme
                updateThemeButtons()
                updatePreview()
            }
        }

        // Shape Chips
        val shapeChips = listOf(
            view.findViewById<LinearLayout>(R.id.chipShapeArrow) to LocationCursorHelper.SHAPE_NAV_ARROW,
            view.findViewById<LinearLayout>(R.id.chipShapeMotorcycle) to LocationCursorHelper.SHAPE_MOTORCYCLE,
            view.findViewById<LinearLayout>(R.id.chipShapeCircle) to LocationCursorHelper.SHAPE_CIRCLE_DOT,
            view.findViewById<LinearLayout>(R.id.chipShapeCrosshair) to LocationCursorHelper.SHAPE_CROSSHAIR,
            view.findViewById<LinearLayout>(R.id.chipShapePin) to LocationCursorHelper.SHAPE_PIN
        )

        fun updateShapeChips() {
            shapeChips.forEach { (chip, shape) ->
                if (shape == selectedShape) {
                    chip.setBackgroundResource(R.drawable.bg_settings_chip_active)
                    (chip.getChildAt(1) as? TextView)?.setTextColor(Color.WHITE)
                } else {
                    chip.setBackgroundResource(R.drawable.bg_settings_chip_inactive)
                    (chip.getChildAt(1) as? TextView)?.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        shapeChips.forEach { (chip, shape) ->
            chip.setOnClickListener {
                selectedShape = shape
                updateShapeChips()
                updatePreview()
            }
        }

        // Cursor Color Circles
        val colorViews = listOf(
            view.findViewById<View>(R.id.colorCircleBlue) to LocationCursorHelper.COLOR_PRESETS[0],
            view.findViewById<View>(R.id.colorCircleRed) to LocationCursorHelper.COLOR_PRESETS[1],
            view.findViewById<View>(R.id.colorCircleGreen) to LocationCursorHelper.COLOR_PRESETS[2],
            view.findViewById<View>(R.id.colorCircleYellow) to LocationCursorHelper.COLOR_PRESETS[3],
            view.findViewById<View>(R.id.colorCirclePurple) to LocationCursorHelper.COLOR_PRESETS[4],
            view.findViewById<View>(R.id.colorCircleOrange) to LocationCursorHelper.COLOR_PRESETS[5],
            view.findViewById<View>(R.id.colorCircleWhite) to LocationCursorHelper.COLOR_PRESETS[6]
        )

        fun updateColorBorders() {
            colorViews.forEach { (v, color) ->
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selectedColor) {
                        setStroke(6, Color.WHITE)
                    } else {
                        setStroke(2, Color.parseColor("#475569"))
                    }
                }
                v.background = drawable
            }
        }

        colorViews.forEach { (v, color) ->
            v.setOnClickListener {
                selectedColor = color
                updateColorBorders()
                updatePreview()
            }
        }

        // Track Color Circles
        val trackColorViews = listOf(
            view.findViewById<View>(R.id.recColorRed) to Color.parseColor("#EF4444"),
            view.findViewById<View>(R.id.recColorOrange) to Color.parseColor("#F97316"),
            view.findViewById<View>(R.id.recColorCyan) to Color.parseColor("#06B6D4"),
            view.findViewById<View>(R.id.recColorGreen) to Color.parseColor("#10B981"),
            view.findViewById<View>(R.id.recColorMagenta) to Color.parseColor("#D500F9"),
            view.findViewById<View>(R.id.recColorYellow) to Color.parseColor("#EAB308")
        )

        fun updateTrackColorBorders() {
            trackColorViews.forEach { (v, color) ->
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selectedTrackColor) {
                        setStroke(6, Color.WHITE)
                    } else {
                        setStroke(2, Color.parseColor("#475569"))
                    }
                }
                v.background = drawable
            }
        }

        trackColorViews.forEach { (v, color) ->
            v.setOnClickListener {
                selectedTrackColor = color
                updateTrackColorBorders()
                updatePreview()
            }
        }

        // Driving Features Switches
        val swCurve = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swCurveWarnings)
        val initialCurveVal = prefs.getBoolean("show_curve_warnings", true)
        swCurve?.isChecked = initialCurveVal
        view.findViewById<View>(R.id.tileCurveWarnings)?.setOnClickListener {
            swCurve?.toggle()
        }

        val swWeather = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swRouteWeather)
        val initialWeatherVal = prefs.getBoolean("show_route_weather", true)
        swWeather?.isChecked = initialWeatherVal
        view.findViewById<View>(R.id.tileRouteWeather)?.setOnClickListener {
            swWeather?.toggle()
        }

        // TFT Padding Button
        view.findViewById<Button>(R.id.btnOpenTftPadding)?.setOnClickListener {
            showTftPaddingDialog()
        }

        // Initialize state
        updateThemeButtons()
        updateShapeChips()
        updateColorBorders()
        updateTrackColorBorders()
        updatePreview()

        btnClose?.setOnClickListener {
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newCurveVal = swCurve?.isChecked ?: true
            val newWeatherVal = swWeather?.isChecked ?: true

            prefs.edit().apply {
                putInt("map_theme_mode", selectedTheme)
                putInt("map_cursor_shape", selectedShape)
                putInt("map_cursor_color", selectedColor)
                putInt("map_recording_color", selectedTrackColor)
                putBoolean("show_curve_warnings", newCurveVal)
                putBoolean("show_route_weather", newWeatherVal)
                apply()
            }

            recordingColor = selectedTrackColor
            applyMapTheme()
            applyLocationCursorStyle()
            updateRecordedPolylineOnMap()

            showCurveWarnings = newCurveVal
            if (showCurveWarnings) {
                refreshCurveWarnings()
            } else {
                clearCurveMarkers()
            }

            showRouteWeather = newWeatherVal
            updateWeatherButtonState(showRouteWeather)
            if (showRouteWeather) {
                refreshRouteWeatherForecast(force = true)
            } else {
                clearRouteWeatherMarkers()
            }

            Toast.makeText(this, getString(R.string.toast_tft_fit_saved), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_LOCATION_PERM
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleGps()
            } else {
                Toast.makeText(this, getString(R.string.map_gps_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── File Picker ────────────────────────────────────────────

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/gpx+xml",
                "application/vnd.google-earth.kml+xml",
                "application/vnd.google-earth.kmz",
                "application/gpx",
                "application/kml",
                "application/kmz",
                "application/xml",
                "text/xml",
                "application/zip",
                "application/x-zip-compressed"
            ))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_FILE_PICK)
    }

    private fun selectOfflineMapFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/x-map",
                "application/octet-stream"
            ))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_MAP_FILE_PICK)
    }

    private fun importMapFileFromUri(uri: Uri) {
        Thread {
            try {
                val mapsDir = java.io.File(getExternalFilesDir(null), "maps").apply { mkdirs() }
                val fileName = getFileNameFromUri(uri) ?: "offline_map.map"
                val destFile = java.io.File(mapsDir, fileName)

                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                getSharedPreferences("kove_map_prefs", MODE_PRIVATE).edit()
                    .putString("offline_map_path", destFile.absolutePath)
                    .apply()

                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_offline_map_loaded, destFile.name), Toast.LENGTH_SHORT).show()
                    switchLayer(LAYER_OFFLINE)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_invalid_map_file), Toast.LENGTH_LONG).show()
                }
                DebugLogger.error("❌ Failed to import map file: ${e.message}")
            }
        }.start()
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_PICK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> showStyleDialogThenImport(uri) }
        } else if (requestCode == REQ_MAP_FILE_PICK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> importMapFileFromUri(uri) }
        }
    }

    // ─── Import with Style Selection ────────────────────────────

    private fun showStyleDialogThenImport(uri: Uri) {
        val fileName = getFileNameFromUri(uri) ?: ""
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext !in listOf("gpx", "kml", "kmz", "xml")) {
            Toast.makeText(this, getString(R.string.map_import_unsupported_format, ext), Toast.LENGTH_LONG).show()
            return
        }

        val defaultColor = RouteStyleDialog.PRESET_COLORS[nextColorIndex % RouteStyleDialog.PRESET_COLORS.size]

        val dialog = RouteStyleDialog(
            context = this,
            initialColor = defaultColor,
            initialWidth = 5f
        ) { color, width, showArrows, arrowColor, showDistance, intervalKm ->
            importRoute(uri, color, width, showArrows, arrowColor, showDistance, intervalKm)
        }
        dialog.show()
    }

    private fun importRoute(
        uri: Uri,
        color: Int,
        width: Float,
        showArrows: Boolean = true,
        arrowColor: Int = color,
        showDistance: Boolean = true,
        intervalKm: Int = 5
    ) {
        val scaleFactor = getSharedPreferences("kove_map_prefs", MODE_PRIVATE).getFloat("map_tiles_scale_factor", 1.0f)
        val fileName = getFileNameFromUri(uri)?.ifEmpty { "Imported Route" } ?: "Imported Route"

        Thread({
            try {
                val kmlDoc = RouteImportHelper.parseKmlDocument(this, uri)
                if (kmlDoc != null && kmlDoc.placemarks.isNotEmpty()) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        var importedCount = 0
                        val allPoints = mutableListOf<GeoPoint>()

                        for (pm in kmlDoc.placemarks) {
                            if (pm.geometryType == KmlGeometryType.POINT && pm.points.isNotEmpty()) {
                                loadedKmlPlacemarks.add(pm)
                                val pt = pm.points.first()
                                allPoints.add(pt)

                                val compositeBmp = PoiMarkerHelper.createCompositePoiBitmap(
                                    this,
                                    pm.name,
                                    pm.description,
                                    pm.iconBitmap,
                                    scaleFactor
                                )

                                val marker = Marker(mapView).apply {
                                    position = pt
                                    title = pm.name.ifEmpty { "POI" }
                                    snippet = pm.description
                                    icon = android.graphics.drawable.BitmapDrawable(resources, compositeBmp)
                                    setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_CENTER)

                                    setOnMarkerClickListener { m, _ ->
                                        m.showInfoWindow()
                                        if (pm.description.isNotEmpty()) {
                                            showPoiDescriptionDialog(pm.name, pm.description, pt)
                                        }
                                        true
                                    }
                                }
                                kmlMarkers.add(marker)
                                mapView.overlays.add(marker)
                                importedCount++
                            } else if (pm.polygons.isNotEmpty()) {
                                loadedKmlPlacemarks.add(pm)
                                for (ring in pm.polygons) {
                                    if (ring.size >= 3) {
                                        allPoints.addAll(ring)
                                        val polyColor = pm.inlineStyle?.polyColor ?: color
                                        val polygon = org.osmdroid.views.overlay.Polygon().apply {
                                            points = ring
                                            fillPaint.color = polyColor
                                            outlinePaint.color = pm.inlineStyle?.lineColor ?: color
                                            outlinePaint.strokeWidth = (pm.inlineStyle?.lineWidth ?: width) * resources.displayMetrics.density
                                        }
                                        kmlPolygons.add(polygon)
                                        mapView.overlays.add(polygon)
                                        importedCount++
                                    }
                                }
                            } else if (pm.points.size >= 2) {
                                allPoints.addAll(pm.points)
                                val lineColor = pm.inlineStyle?.lineColor ?: color
                                val lineWidth = pm.inlineStyle?.lineWidth ?: width

                                val route = LoadedRoute(
                                    name = pm.name.ifEmpty { "KML Track ${loadedRoutes.size + 1}" },
                                    groupName = fileName,
                                    points = pm.points,
                                    color = lineColor,
                                    width = lineWidth,
                                    visible = true,
                                    showDirectionArrows = showArrows,
                                    arrowColor = arrowColor,
                                    showDistanceMarkers = showDistance,
                                    distanceIntervalKm = intervalKm
                                )

                                updateRouteOverlays(route)
                                loadedRoutes.add(route)
                                importedCount++
                            }
                        }

                        expandedGroups.add(fileName)
                        saveCurrentRoutesToStorage()
                        mapView.invalidate()
                        refresh3dRoutes()
                        if (allPoints.isNotEmpty()) zoomToFitPoints(allPoints)

                        val btnRouteList = findViewById<Button>(R.id.btnRouteList)
                        btnRouteList.visibility = if (loadedRoutes.isNotEmpty()) View.VISIBLE else View.GONE
                        Toast.makeText(this, getString(R.string.map_import_success, importedCount), Toast.LENGTH_SHORT).show()
                        refreshRouteList()
                    }
                    return@Thread
                }

                // Fallback for standard GPX files
                val parsedRoutes = RouteImportHelper.parseUri(this, uri)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (parsedRoutes.isEmpty()) {
                        Toast.makeText(this, getString(R.string.map_import_no_routes), Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    for (parsed in parsedRoutes) {
                        val route = LoadedRoute(
                            name = parsed.name,
                            groupName = fileName,
                            points = parsed.points,
                            color = color,
                            width = width,
                            visible = true,
                            showDirectionArrows = showArrows,
                            arrowColor = arrowColor,
                            showDistanceMarkers = showDistance,
                            distanceIntervalKm = intervalKm
                        )

                        updateRouteOverlays(route)
                        loadedRoutes.add(route)
                        nextColorIndex++
                    }

                    expandedGroups.add(fileName)
                    saveCurrentRoutesToStorage()
                    mapView.invalidate()
                    refresh3dRoutes()

                    if (parsedRoutes.isNotEmpty()) {
                        val allPoints = parsedRoutes.flatMap { it.points }
                        zoomToFitPoints(allPoints)
                    }

                    val btnRouteList = findViewById<Button>(R.id.btnRouteList)
                    btnRouteList.visibility = if (loadedRoutes.isNotEmpty()) View.VISIBLE else View.GONE

                    Toast.makeText(
                        this,
                        getString(R.string.map_import_success, parsedRoutes.size),
                        Toast.LENGTH_SHORT
                    ).show()

                    refreshRouteList()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            getString(R.string.map_import_error, e.message ?: "Unknown"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }, "KoveMirror-RouteImport").start()
    }

    private fun showPoiDescriptionDialog(title: String, descriptionHtml: String, point: GeoPoint) {
        val formattedText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(descriptionHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(descriptionHtml)
        }

        val messageView = TextView(this).apply {
            text = formattedText
            setPadding(32, 16, 32, 16)
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📍 ${title.ifEmpty { "POI" }}")
            .setView(messageView)
            .setPositiveButton(R.string.btn_ok, null)
            .setNeutralButton(R.string.nav_start_navigation) { _, _ ->
                selectDestinationPoint(point, title)
            }
            .show()
    }

    private fun refreshPoiOverlays(scaleFactor: Float) {
        val pointPlacemarks = loadedKmlPlacemarks.filter { it.geometryType == KmlGeometryType.POINT && it.points.isNotEmpty() }
        if (pointPlacemarks.size == kmlMarkers.size) {
            for (i in pointPlacemarks.indices) {
                val pm = pointPlacemarks[i]
                val marker = kmlMarkers[i]
                val compositeBmp = PoiMarkerHelper.createCompositePoiBitmap(
                    this,
                    pm.name,
                    pm.description,
                    pm.iconBitmap,
                    scaleFactor
                )
                marker.icon = android.graphics.drawable.BitmapDrawable(resources, compositeBmp)
            }
            mapView.invalidate()
        }
    }

    private fun refresh3dRoutes() {
        val scaleFactor = getSharedPreferences("kove_map_prefs", MODE_PRIVATE).getFloat("map_tiles_scale_factor", 1.0f)
        val data = loadedRoutes.map {
            Map3dOverlays.RouteData(
                name = it.name,
                points = it.points.map { p -> LatLng(p.latitude, p.longitude) },
                color = it.color,
                width = it.width,
                visible = it.visible
            )
        }
        map3d?.setRoutes(data)

        val pois3d = loadedKmlPlacemarks.filter { it.geometryType == KmlGeometryType.POINT && it.points.isNotEmpty() }.map { pm ->
            val pt = pm.points.first()
            val compositeBmp = PoiMarkerHelper.createCompositePoiBitmap(this, pm.name, pm.description, pm.iconBitmap, scaleFactor)
            Map3dOverlays.PoiData(
                name = pm.name,
                description = pm.description,
                latLng = LatLng(pt.latitude, pt.longitude),
                bitmap = compositeBmp
            )
        }
        map3d?.setPois(pois3d)
    }

    private fun zoomToFitPoints(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(points[0])
            return
        }

        val north = points.maxOf { it.latitude }
        val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }
        val west = points.minOf { it.longitude }

        val center = GeoPoint((north + south) / 2.0, (east + west) / 2.0)
        mapView.controller.setCenter(center)

        val latSpan = north - south
        val lonSpan = east - west
        val maxSpan = maxOf(latSpan, lonSpan)
        val zoom = when {
            maxSpan > 10 -> 5.0
            maxSpan > 5 -> 7.0
            maxSpan > 2 -> 8.0
            maxSpan > 1 -> 9.0
            maxSpan > 0.5 -> 10.0
            maxSpan > 0.2 -> 11.0
            maxSpan > 0.1 -> 12.0
            maxSpan > 0.05 -> 13.0
            maxSpan > 0.01 -> 14.0
            else -> 15.0
        }
        mapView.controller.setZoom(zoom)
    }

    // ─── Route List UI ──────────────────────────────────────────

    private fun updateRouteOverlays(route: LoadedRoute) {
        route.polyline?.let { mapView.overlays.remove(it) }
        for (m in route.arrowMarkers) { mapView.overlays.remove(m) }
        route.arrowMarkers.clear()
        for (m in route.distanceMarkers) { mapView.overlays.remove(m) }
        route.distanceMarkers.clear()

        if (route.visible) {
            val polyline = Polyline().apply {
                setPoints(route.points)
                outlinePaint.color = route.color
                outlinePaint.strokeWidth = route.width * resources.displayMetrics.density
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            route.polyline = polyline
            mapView.overlays.add(polyline)

            if (route.showDirectionArrows && route.points.size >= 2) {
                val arrows = RouteOverlayHelper.createDirectionArrowOverlays(this, mapView, route.points, route.arrowColor)
                route.arrowMarkers.addAll(arrows)
                for (m in arrows) mapView.overlays.add(m)
            }

            if (route.showDistanceMarkers && route.points.size >= 2) {
                val distMarkers = RouteOverlayHelper.createDistanceMarkerOverlays(this, mapView, route.points, route.distanceIntervalKm)
                route.distanceMarkers.addAll(distMarkers)
                for (m in distMarkers) mapView.overlays.add(m)
            }
        }
    }

    private fun reverseRoute(index: Int) {
        if (index !in loadedRoutes.indices) return
        val route = loadedRoutes[index]
        route.points = route.points.reversed()
        updateRouteOverlays(route)
        saveCurrentRoutesToStorage()
        mapView.invalidate()
        refresh3dRoutes()
        refreshRouteList()
        Toast.makeText(this, getString(R.string.map_route_reversed_toast, route.name), Toast.LENGTH_SHORT).show()
    }

    private fun saveCurrentRoutesToStorage() {
        val dtoList = loadedRoutes.map { r ->
            SavedRouteDto(
                id = r.id,
                name = r.name,
                groupName = r.groupName,
                points = r.points.map { p -> SavedPointDto(p.latitude, p.longitude, p.altitude) },
                color = r.color,
                width = r.width,
                visible = r.visible,
                showDirectionArrows = r.showDirectionArrows,
                arrowColor = r.arrowColor,
                showDistanceMarkers = r.showDistanceMarkers,
                distanceIntervalKm = r.distanceIntervalKm,
                placemarks = emptyList()
            )
        }
        val placemarkDtos = loadedKmlPlacemarks.map { pm ->
            SavedPlacemarkDto(
                name = pm.name,
                description = pm.description,
                styleUrl = pm.styleUrl,
                geometryType = pm.geometryType.name,
                points = pm.points.map { p -> SavedPointDto(p.latitude, p.longitude, p.altitude) },
                polygons = pm.polygons.map { ring -> ring.map { p -> SavedPointDto(p.latitude, p.longitude, p.altitude) } },
                lineColor = pm.inlineStyle?.lineColor,
                lineWidth = pm.inlineStyle?.lineWidth,
                polyColor = pm.inlineStyle?.polyColor
            )
        }
        RouteStorageManager.saveRoutes(this, dtoList)
        RouteStorageManager.savePlacemarks(this, placemarkDtos)
        syncRoutesToStateHolder()
    }

    private fun syncRoutesToStateHolder() {
        MapStateHolder.loadedRoutes.clear()
        for (r in loadedRoutes) {
            MapStateHolder.loadedRoutes.add(
                MapStateHolder.SharedRoute(
                    id = r.id,
                    name = r.name,
                    points = r.points,
                    color = r.color,
                    width = r.width,
                    visible = r.visible,
                    showDirectionArrows = r.showDirectionArrows,
                    arrowColor = r.arrowColor,
                    showDistanceMarkers = r.showDistanceMarkers,
                    distanceIntervalKm = r.distanceIntervalKm
                )
            )
        }
        MapStateHolder.notifyRoutesChanged()
    }

    private fun loadSavedRoutesFromStorage() {
        val dtoList = RouteStorageManager.loadRoutes(this)
        val savedPlacemarks = RouteStorageManager.loadPlacemarks(this)
        if (dtoList.isEmpty() && savedPlacemarks.isEmpty()) return

        val scaleFactor = getSharedPreferences("kove_map_prefs", MODE_PRIVATE).getFloat("map_tiles_scale_factor", 1.0f)
        val allPointsToFit = mutableListOf<GeoPoint>()
        var hadLegacyEmbeddedPlacemarks = false

        for (dto in dtoList) {
            val pts = dto.points.map { GeoPoint(it.lat, it.lon, it.alt) }
            val route = LoadedRoute(
                id = dto.id,
                name = dto.name,
                groupName = dto.groupName,
                points = pts,
                color = dto.color,
                width = dto.width,
                visible = dto.visible,
                showDirectionArrows = dto.showDirectionArrows,
                arrowColor = dto.arrowColor,
                showDistanceMarkers = dto.showDistanceMarkers,
                distanceIntervalKm = dto.distanceIntervalKm
            )

            updateRouteOverlays(route)
            loadedRoutes.add(route)
            if (pts.isNotEmpty()) allPointsToFit.addAll(pts)

            if (dto.placemarks.isNotEmpty()) {
                hadLegacyEmbeddedPlacemarks = true
                savedPlacemarks.addAll(dto.placemarks)
            }
        }

        // Reconstruct unique KML placemarks once (deduplicating any legacy embedded copies)
        val seenPlacemarkKeys = HashSet<String>()
        val defaultColor = dtoList.firstOrNull()?.color ?: Color.RED
        val defaultWidth = dtoList.firstOrNull()?.width ?: 5f

        for (pmDto in savedPlacemarks) {
            val firstPt = pmDto.points.firstOrNull()
            val firstPolyPt = pmDto.polygons.firstOrNull()?.firstOrNull()
            val dedupKey = "${pmDto.geometryType}|${pmDto.name}|${pmDto.description}|${firstPt?.lat},${firstPt?.lon}|${firstPolyPt?.lat},${firstPolyPt?.lon}"
            if (!seenPlacemarkKeys.add(dedupKey)) continue

            val pmPts = pmDto.points.map { GeoPoint(it.lat, it.lon, it.alt) }
            val pmPolys = pmDto.polygons.map { ring -> ring.map { GeoPoint(it.lat, it.lon, it.alt) } }
            val geomType = try { KmlGeometryType.valueOf(pmDto.geometryType) } catch (_: Exception) { KmlGeometryType.LINESTRING }
            val inlineStyle = KmlStyle(
                lineColor = pmDto.lineColor,
                lineWidth = pmDto.lineWidth,
                polyColor = pmDto.polyColor
            )

            val placemark = KmlPlacemark(
                name = pmDto.name,
                description = pmDto.description,
                styleUrl = pmDto.styleUrl,
                inlineStyle = inlineStyle,
                geometryType = geomType,
                points = pmPts,
                polygons = pmPolys
            )
            loadedKmlPlacemarks.add(placemark)

            if (geomType == KmlGeometryType.POINT && pmPts.isNotEmpty()) {
                val pt = pmPts.first()
                val compositeBmp = PoiMarkerHelper.createCompositePoiBitmap(this, pmDto.name, pmDto.description, null, scaleFactor)
                val marker = Marker(mapView).apply {
                    position = pt
                    title = pmDto.name.ifEmpty { "POI" }
                    snippet = pmDto.description
                    icon = android.graphics.drawable.BitmapDrawable(resources, compositeBmp)
                    setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { m, _ ->
                        m.showInfoWindow()
                        if (pmDto.description.isNotEmpty()) {
                            showPoiDescriptionDialog(pmDto.name, pmDto.description, pt)
                        }
                        true
                    }
                }
                kmlMarkers.add(marker)
                mapView.overlays.add(marker)
            } else if (pmPolys.isNotEmpty()) {
                for (ring in pmPolys) {
                    if (ring.size >= 3) {
                        val polygon = org.osmdroid.views.overlay.Polygon().apply {
                            points = ring
                            fillPaint.color = pmDto.polyColor ?: defaultColor
                            outlinePaint.color = pmDto.lineColor ?: defaultColor
                            outlinePaint.strokeWidth = (pmDto.lineWidth ?: defaultWidth) * resources.displayMetrics.density
                        }
                        kmlPolygons.add(polygon)
                        mapView.overlays.add(polygon)
                    }
                }
            }
        }

        if (hadLegacyEmbeddedPlacemarks) {
            saveCurrentRoutesToStorage()
        }

        mapView.invalidate()
        refresh3dRoutes()
        refreshRouteList()
        syncRoutesToStateHolder()
        if (showRouteWeather && loadedRoutes.isNotEmpty()) {
            refreshRouteWeatherForecast()
        }
        if (showCurveWarnings && loadedRoutes.isNotEmpty()) {
            refreshCurveWarnings()
        }
        val btnRouteList = findViewById<Button>(R.id.btnRouteList)
        btnRouteList.visibility = if (loadedRoutes.isNotEmpty()) View.VISIBLE else View.GONE
    }

    @SuppressLint("InflateParams")
    private fun refreshRouteList() {
        val container = findViewById<LinearLayout>(R.id.routeListItems)
        container.removeAllViews()

        updateWeatherButtonVisibility()
        if (loadedRoutes.isEmpty()) return

        val grouped = loadedRoutes.groupBy { it.groupName }

        for ((groupName, routes) in grouped) {
            val isExpanded = expandedGroups.contains(groupName)
            val groupView = LayoutInflater.from(this).inflate(R.layout.item_route_group, container, false)

            val tvExpandArrow = groupView.findViewById<TextView>(R.id.tvGroupExpandArrow)
            tvExpandArrow.text = if (isExpanded) "▼" else "▶"

            groupView.findViewById<TextView>(R.id.tvGroupName).text = groupName

            val totalPoints = routes.sumOf { it.points.size }
            val trackCount = routes.size
            groupView.findViewById<TextView>(R.id.tvGroupSummary).text = "$trackCount track(s) • $totalPoints pts"

            val isGroupVisible = routes.any { it.visible }
            val btnVisibility = groupView.findViewById<ImageView>(R.id.btnGroupVisibility)
            btnVisibility.alpha = if (isGroupVisible) 1.0f else 0.3f

            val toggleExpandAction = View.OnClickListener {
                if (isExpanded) {
                    expandedGroups.remove(groupName)
                } else {
                    expandedGroups.add(groupName)
                }
                refreshRouteList()
            }

            groupView.findViewById<View>(R.id.layoutGroupHeader).setOnClickListener(toggleExpandAction)
            tvExpandArrow.setOnClickListener(toggleExpandAction)

            groupView.findViewById<ImageView>(R.id.btnGroupReverse).setOnClickListener {
                for (r in routes) {
                    r.points = r.points.reversed()
                    updateRouteOverlays(r)
                }
                saveCurrentRoutesToStorage()
                mapView.invalidate()
                refresh3dRoutes()
                refreshRouteList()
                Toast.makeText(this, "🔄 $groupName reversed", Toast.LENGTH_SHORT).show()
            }

            groupView.findViewById<ImageView>(R.id.btnGroupStyle).setOnClickListener {
                val sample = routes.first()
                val dialog = RouteStyleDialog(
                    context = this,
                    initialColor = sample.color,
                    initialWidth = sample.width,
                    initialShowArrows = sample.showDirectionArrows,
                    initialArrowColor = sample.arrowColor,
                    initialShowDistance = sample.showDistanceMarkers,
                    initialDistanceIntervalKm = sample.distanceIntervalKm
                ) { newColor, newWidth, newShowArrows, newArrowColor, newShowDistance, newIntervalKm ->
                    for (r in routes) {
                        r.color = newColor
                        r.width = newWidth
                        r.showDirectionArrows = newShowArrows
                        r.arrowColor = newArrowColor
                        r.showDistanceMarkers = newShowDistance
                        r.distanceIntervalKm = newIntervalKm
                        updateRouteOverlays(r)
                    }
                    saveCurrentRoutesToStorage()
                    mapView.invalidate()
                    refresh3dRoutes()
                    refreshRouteList()
                }
                dialog.show()
            }

            btnVisibility.setOnClickListener {
                val targetVisible = !isGroupVisible
                for (r in routes) {
                    r.visible = targetVisible
                    updateRouteOverlays(r)
                }
                saveCurrentRoutesToStorage()
                mapView.invalidate()
                refresh3dRoutes()
                refreshRouteList()
            }

            groupView.findViewById<ImageView>(R.id.btnGroupDelete).setOnClickListener {
                for (r in routes) {
                    r.polyline?.let { mapView.overlays.remove(it) }
                    for (m in r.arrowMarkers) { mapView.overlays.remove(m) }
                    for (m in r.distanceMarkers) { mapView.overlays.remove(m) }
                    loadedRoutes.remove(r)
                }
                expandedGroups.remove(groupName)
                saveCurrentRoutesToStorage()

                if (loadedRoutes.isEmpty()) {
                    clearAllImportedOverlays()
                } else {
                    mapView.invalidate()
                    refresh3dRoutes()
                    refreshRouteList()
                }
            }

            container.addView(groupView)

            // Render sub-items if expanded
            if (isExpanded) {
                for (route in routes) {
                    val subIndex = loadedRoutes.indexOf(route)
                    val itemView = LayoutInflater.from(this).inflate(R.layout.item_route, container, false)
                    itemView.setPadding((24 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt())

                    val colorView = itemView.findViewById<View>(R.id.viewRouteColor)
                    val colorDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(route.color)
                    }
                    colorView.background = colorDrawable

                    itemView.findViewById<TextView>(R.id.tvRouteName).text = "└ ${route.name}"
                    itemView.findViewById<TextView>(R.id.tvRoutePoints).text = "${route.points.size} pts"

                    itemView.findViewById<ImageView>(R.id.btnRouteReverse)?.setOnClickListener {
                        reverseRoute(subIndex)
                    }

                    itemView.findViewById<ImageView>(R.id.btnRouteStyle).setOnClickListener {
                        val dialog = RouteStyleDialog(
                            context = this,
                            initialColor = route.color,
                            initialWidth = route.width,
                            initialShowArrows = route.showDirectionArrows,
                            initialArrowColor = route.arrowColor,
                            initialShowDistance = route.showDistanceMarkers,
                            initialDistanceIntervalKm = route.distanceIntervalKm
                        ) { newColor, newWidth, newShowArrows, newArrowColor, newShowDistance, newIntervalKm ->
                            route.color = newColor
                            route.width = newWidth
                            route.showDirectionArrows = newShowArrows
                            route.arrowColor = newArrowColor
                            route.showDistanceMarkers = newShowDistance
                            route.distanceIntervalKm = newIntervalKm

                            updateRouteOverlays(route)
                            saveCurrentRoutesToStorage()
                            mapView.invalidate()
                            refresh3dRoutes()
                            refreshRouteList()
                        }
                        dialog.show()
                    }

                    val btnSubVisibility = itemView.findViewById<ImageView>(R.id.btnRouteVisibility)
                    btnSubVisibility.alpha = if (route.visible) 1.0f else 0.3f
                    btnSubVisibility.setOnClickListener {
                        route.visible = !route.visible
                        updateRouteOverlays(route)
                        saveCurrentRoutesToStorage()
                        mapView.invalidate()
                        refresh3dRoutes()
                        refreshRouteList()
                    }

                    itemView.findViewById<ImageView>(R.id.btnRouteDelete).setOnClickListener {
                        route.polyline?.let { mapView.overlays.remove(it) }
                        for (m in route.arrowMarkers) { mapView.overlays.remove(m) }
                        for (m in route.distanceMarkers) { mapView.overlays.remove(m) }
                        loadedRoutes.removeAt(subIndex)
                        saveCurrentRoutesToStorage()

                        if (loadedRoutes.isEmpty()) {
                            clearAllImportedOverlays()
                        } else {
                            mapView.invalidate()
                            refresh3dRoutes()
                            refreshRouteList()
                        }
                    }

                    container.addView(itemView)
                }
            }
        }
    }

    private fun clearAllImportedOverlays() {
        for (route in loadedRoutes) {
            route.polyline?.let { mapView.overlays.remove(it) }
            for (m in route.arrowMarkers) { mapView.overlays.remove(m) }
            for (m in route.distanceMarkers) { mapView.overlays.remove(m) }
        }
        loadedRoutes.clear()

        for (marker in kmlMarkers) {
            mapView.overlays.remove(marker)
        }
        kmlMarkers.clear()

        for (polygon in kmlPolygons) {
            mapView.overlays.remove(polygon)
        }
        kmlPolygons.clear()

        loadedKmlPlacemarks.clear()
        RouteStorageManager.saveRoutes(this, emptyList())
        RouteStorageManager.savePlacemarks(this, emptyList())

        clearRouteWeatherMarkers()
        clearCurveMarkers()

        mapView.invalidate()
        refresh3dRoutes()

        findViewById<Button>(R.id.btnRouteList).visibility = View.GONE
        findViewById<LinearLayout>(R.id.routeListContainer).visibility = View.GONE
        updateWeatherButtonVisibility()
        refreshRouteList()
    }

    // ─── Motorcycle Handlebar Buttons ────────────────────────────

    private val handlebarActionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                "com.kove.mirror.ACTION_MY_LOCATION" -> {
                    DebugLogger.info("📍 ACTION_MY_LOCATION received in MapActivity")
                    centerOnMyLocation()
                }
                "com.kove.mirror.ACTION_TOGGLE_3D" -> {
                    DebugLogger.info("🌐 ACTION_TOGGLE_3D received in MapActivity")
                    if (currentLayer == LAYER_3D) switchLayer(layerBefore3d)
                    else switchLayer(LAYER_3D)
                }
            }
        }
    }

    private val handlebarKeyListener: (HandlebarKey) -> Boolean = { key ->
        when (key) {
            HandlebarKey.ESC -> {
                if (isNavigating) {
                    stopNavigation()
                } else if (findViewById<LinearLayout>(R.id.destCard).visibility == View.VISIBLE) {
                    cancelDestinationSelection()
                } else {
                    finish()
                }
                true
            }
            else -> false // Let HandlebarOverlayService execute active mode (Zoom, Pan, Media, Volume, App Switch)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isRecordingTrack) {
            showExitRecordingWarningDialog()
        } else {
            super.onBackPressed()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (HandlebarKeyManager.processKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        val center = mapView.mapCenter
        MapStateHolder.isMapOpen = true
        MapStateHolder.currentLayer = currentLayer
        MapStateHolder.currentZoom = mapView.zoomLevelDouble
        MapStateHolder.currentCenter = GeoPoint(center.latitude, center.longitude)
        MapStateHolder.currentBearing = mapView.mapOrientation
        MapStateHolder.notifyCameraChanged()
        MapStateHolder.notifyLayerChanged()
        syncRoutesToStateHolder()
        updateWeatherButtonVisibility()
        if (showCurveWarnings) {
            refreshCurveWarnings()
        }
        mapView.onResume()
        mapView3d.onResume()
        HandlebarKeyManager.addListener(handlebarKeyListener)
        val filter = android.content.IntentFilter().apply {
            addAction("com.kove.mirror.ACTION_MY_LOCATION")
            addAction("com.kove.mirror.ACTION_TOGGLE_3D")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(handlebarActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(handlebarActionReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(handlebarActionReceiver) } catch (_: Exception) {}
        HandlebarKeyManager.removeListener(handlebarKeyListener)
        mapView.onPause()
        mapView3d.onPause()

        val center = mapView.mapCenter
        getSharedPreferences("kove_map_prefs", MODE_PRIVATE).edit().apply {
            putFloat("map_lat", center.latitude.toFloat())
            putFloat("map_lon", center.longitude.toFloat())
            putFloat("map_zoom", mapView.zoomLevelDouble.toFloat())
            putInt("map_layer", currentLayer)
            putInt("map_base_layer", currentBaseLayer)
            putInt("map_layer_before_3d", layerBefore3d)
            apply()
        }
    }

    private fun saveLayerPrefs() {
        getSharedPreferences("kove_map_prefs", MODE_PRIVATE).edit().apply {
            putInt("map_layer", currentLayer)
            putInt("map_base_layer", currentBaseLayer)
            putInt("map_layer_before_3d", layerBefore3d)
            apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MapStateHolder.isMapOpen = false
        locationOverlay?.disableMyLocation()
        locationOverlay?.disableFollowLocation()
        mapView3d.onDestroy()
    }
}
