package com.kove.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    }

    private lateinit var mapView: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private var isGpsEnabled = false
    private var currentLayer = LAYER_MAPS

    // ─── Long Press Navigation ──────────────────────────────────
    private var selectedDestination: GeoPoint? = null
    private var destinationMarker: Marker? = null
    private var navigationPolyline: Polyline? = null
    private var isNavigating = false
    private var currentNavigationRoute: NavigationHelper.NavigationRoute? = null

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
                val elapsedSec = ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).coerceAtLeast(0)
                val hours = elapsedSec / 3600
                val mins = (elapsedSec % 3600) / 60
                val secs = elapsedSec % 60
                val timeStr = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "%02d:%02d", mins, secs)
                }
                val btnRecord = findViewById<Button>(R.id.btnRecordTrack)
                btnRecord?.text = "⏹ REC ($timeStr)"
                btnRecord?.setBackgroundColor(Color.parseColor("#B71C1C"))
                recordTimerHandler.postDelayed(this, 1000)
            }
        }
    }

    // ─── Route Management ───────────────────────────────────────

    data class LoadedRoute(
        val name: String,
        val points: List<GeoPoint>,
        var color: Int,
        var width: Float,
        var visible: Boolean = true,
        var polyline: Polyline? = null
    )

    private val loadedRoutes = mutableListOf<LoadedRoute>()
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

    private val satelliteTileSource: OnlineTileSourceBase = object : XYTileSource(
        "EsriSatellite",
        0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}$zoom/$y/$x"
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

        setContentView(R.layout.activity_map)

        // Keep screen on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mapView = findViewById(R.id.mapView)
        setupMap()
        setupButtons()
        setupMapEvents()

        // Load saved TFT black bar margins
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        val topPadding = prefs.getInt("map_top_padding_dp", 0)
        val bottomPadding = prefs.getInt("map_bottom_padding_dp", 0)
        applyTftPadding(topPadding, bottomPadding)

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
        }
    }

    // ─── Map Setup ──────────────────────────────────────────────

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
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
        selectedDestination = point

        // Place or move marker
        if (destinationMarker == null) {
            destinationMarker = Marker(mapView).apply {
                title = getString(R.string.nav_destination_selected)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(destinationMarker)
        }
        destinationMarker?.position = point
        mapView.invalidate()

        // Show Destination Card
        val destCard = findViewById<LinearLayout>(R.id.destCard)
        val tvCoords = findViewById<TextView>(R.id.tvDestCoords)
        val tvDist = findViewById<TextView>(R.id.tvDestDist)

        tvCoords.text = String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)

        // Calculate approximate air distance from current location
        val myLoc = locationOverlay?.myLocation
        if (myLoc != null) {
            val distMeters = myLoc.distanceToAsDouble(point)
            val distKm = distMeters / 1000.0
            tvDist.text = String.format(Locale.getDefault(), "Approx: %.1f km", distKm)
        } else {
            tvDist.text = "GPS location unknown"
        }

        destCard.visibility = View.VISIBLE
    }

    private fun cancelDestinationSelection() {
        selectedDestination = null
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = null
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
        if (myLoc == null) {
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
        findViewById<View>(R.id.topBar).visibility = View.GONE
        tvInstruction.text = getString(R.string.nav_calculating)
        tvTurnDist.text = ""
        tvTotalEta.text = ""

        NavigationHelper.fetchRoute(
            start = myLoc,
            destination = dest,
            onSuccess = { route ->
                runOnUiThread {
                    currentNavigationRoute = route
                    isNavigating = true

                    // Draw navigation polyline
                    navigationPolyline?.let { mapView.overlays.remove(it) }
                    navigationPolyline = Polyline().apply {
                        setPoints(route.geometryPoints)
                        outlinePaint.color = Color.parseColor("#2563EB") // Royal Blue
                        outlinePaint.strokeWidth = 7f * resources.displayMetrics.density
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        outlinePaint.isAntiAlias = true
                    }
                    mapView.overlays.add(navigationPolyline)
                    mapView.invalidate()

                    // Enable auto-follow GPS
                    locationOverlay?.enableFollowLocation()
                    mapView.controller.setZoom(16.0)

                    updateNavigationUi(myLoc)
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
        currentNavigationRoute = null
        selectedDestination = null

        navigationPolyline?.let { mapView.overlays.remove(it) }
        navigationPolyline = null

        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = null

        findViewById<LinearLayout>(R.id.navBanner).visibility = View.GONE
        findViewById<View>(R.id.topBar).visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.destCard).visibility = View.GONE

        mapView.invalidate()
    }

    private fun updateNavigationUi(currentLocation: GeoPoint) {
        val route = currentNavigationRoute ?: return
        val dest = selectedDestination ?: return

        val remainingDistMeters = currentLocation.distanceToAsDouble(dest)
        if (remainingDistMeters < 30.0) {
            Toast.makeText(this, getString(R.string.nav_arrived), Toast.LENGTH_LONG).show()
            stopNavigation()
            return
        }

        // Find nearest step in route
        val nextStep = route.steps.firstOrNull { step ->
            step.location.latitude != 0.0 && currentLocation.distanceToAsDouble(step.location) < 300.0
        } ?: route.steps.firstOrNull()

        val tvIcon = findViewById<TextView>(R.id.tvTurnIcon)
        val tvInstruction = findViewById<TextView>(R.id.tvTurnInstruction)
        val tvTurnDist = findViewById<TextView>(R.id.tvTurnDistance)
        val tvTotalEta = findViewById<TextView>(R.id.tvTotalDistanceEta)

        if (nextStep != null) {
            val distToStep = currentLocation.distanceToAsDouble(nextStep.location)
            tvInstruction.text = nextStep.instruction
            tvTurnDist.text = if (distToStep < 1000) "${distToStep.toInt()} m" else String.format(Locale.getDefault(), "%.1f km", distToStep / 1000.0)

            tvIcon.text = when {
                nextStep.modifier.contains("left") -> "⬅️"
                nextStep.modifier.contains("right") -> "➡️"
                nextStep.modifier.contains("uturn") -> "↩️"
                else -> "⬆️"
            }
        }

        val remKm = remainingDistMeters / 1000.0
        val remMin = (route.totalDurationSeconds / 60.0).toInt()
        tvTotalEta.text = getString(R.string.nav_rem_format, remKm, remMin)
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

        // Layer buttons
        val btnMaps = findViewById<View>(R.id.btnLayerMaps)
        val btnTopo = findViewById<View>(R.id.btnLayerTopo)
        val btnSat = findViewById<View>(R.id.btnLayerSatellite)
        val btnOffline = findViewById<View>(R.id.btnLayerOffline)

        btnMaps.setOnClickListener { switchLayer(LAYER_MAPS) }
        btnTopo.setOnClickListener { switchLayer(LAYER_TOPO) }
        btnSat.setOnClickListener { switchLayer(LAYER_SATELLITE) }
        btnOffline.setOnClickListener {
            if (currentLayer == LAYER_OFFLINE) {
                selectOfflineMapFile()
            } else {
                switchLayer(LAYER_OFFLINE)
            }
        }
        btnOffline.setOnLongClickListener {
            selectOfflineMapFile()
            true
        }

        // Zoom buttons
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener {
            mapView.controller.zoomIn()
        }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            mapView.controller.zoomOut()
        }

        // My location button (center on GPS)
        findViewById<Button>(R.id.btnMyLocation).setOnClickListener { centerOnMyLocation() }

        // Destination Card Buttons
        findViewById<Button>(R.id.btnCancelDest).setOnClickListener { cancelDestinationSelection() }
        findViewById<Button>(R.id.btnStartNav).setOnClickListener { startNavigation() }

        // Turn Banner Stop Nav Button
        findViewById<Button>(R.id.btnStopNav).setOnClickListener { stopNavigation() }

        // Import route
        findViewById<Button>(R.id.btnImportRoute).setOnClickListener { openFilePicker() }

        // Track record button
        findViewById<Button>(R.id.btnRecordTrack).setOnClickListener { toggleTrackRecording() }

        // GPS toggle
        findViewById<Button>(R.id.btnGpsToggle).setOnClickListener { toggleGps() }

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

        // Map Settings Button (Theme & Cursor)
        findViewById<View>(R.id.btnMapSettings).setOnClickListener {
            showMapSettingsDialog()
        }

        // TFT Padding Adjustment Button
        findViewById<View>(R.id.btnTftFit).setOnClickListener {
            showTftPaddingDialog()
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

    private fun switchLayer(layer: Int) {
        currentLayer = layer
        val btnMaps = findViewById<View>(R.id.btnLayerMaps)
        val btnTopo = findViewById<View>(R.id.btnLayerTopo)
        val btnSat = findViewById<View>(R.id.btnLayerSatellite)
        val btnOffline = findViewById<View>(R.id.btnLayerOffline)

        val activeColor = Color.parseColor("#2979FF")
        val inactiveColor = Color.parseColor("#555555")

        btnMaps.setBackgroundColor(if (layer == LAYER_MAPS) activeColor else inactiveColor)
        btnTopo.setBackgroundColor(if (layer == LAYER_TOPO) activeColor else inactiveColor)
        btnSat.setBackgroundColor(if (layer == LAYER_SATELLITE) activeColor else inactiveColor)
        btnOffline.setBackgroundColor(if (layer == LAYER_OFFLINE) activeColor else inactiveColor)

        if (layer != LAYER_OFFLINE && mapView.tileProvider !is org.osmdroid.tileprovider.MapTileProviderBasic) {
            mapView.tileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(this)
        }

        when (layer) {
            LAYER_MAPS -> mapView.setTileSource(TileSourceFactory.MAPNIK)
            LAYER_TOPO -> mapView.setTileSource(openTopoTileSource)
            LAYER_SATELLITE -> mapView.setTileSource(satelliteTileSource)
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

                        // Keep current zoom level and map position when loading offline map
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
        applyMapTheme()
        mapView.invalidate()
    }

    // ─── Center on My Location ──────────────────────────────────

    private fun centerOnMyLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        if (!isGpsEnabled) {
            toggleGps()
        }

        locationOverlay?.myLocation?.let { loc ->
            mapView.controller.animateTo(loc)
        } ?: run {
            Toast.makeText(this, getString(R.string.map_waiting_gps), Toast.LENGTH_SHORT).show()
            locationOverlay?.enableFollowLocation()
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
            btnGps.text = getString(R.string.map_btn_gps)
            btnGps.setBackgroundColor(Color.parseColor("#1565C0"))
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
                        if (isNavigating) {
                            runOnUiThread {
                                updateNavigationUi(GeoPoint(location.latitude, location.longitude))
                            }
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
            btnGps.text = getString(R.string.map_btn_gps_on)
            btnGps.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
        mapView.invalidate()
    }

    // ─── GPX Track Recording Logic ────────────────────────────────

    private fun updateRecordedPolylineOnMap() {
        recordedPolyline?.let { mapView.overlays.remove(it) }
        if (recordedTrackPoints.isEmpty()) return

        val geoPoints = recordedTrackPoints.map { GeoPoint(it.lat, it.lon) }
        recordedPolyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = recordingColor
            outlinePaint.strokeWidth = 8f * resources.displayMetrics.density
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
        val btnRecord = findViewById<Button>(R.id.btnRecordTrack)
        btnRecord?.text = getString(R.string.map_btn_record_start)
        btnRecord?.setBackgroundColor(Color.parseColor("#D97706"))
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
    }

    private fun showMapSettingsDialog() {
        val prefs = getSharedPreferences("kove_map_prefs", MODE_PRIVATE)
        var selectedTheme = prefs.getInt("map_theme_mode", MapThemeHelper.THEME_AUTO)
        var selectedShape = prefs.getInt("map_cursor_shape", LocationCursorHelper.SHAPE_NAV_ARROW)
        var selectedColor = prefs.getInt("map_cursor_color", LocationCursorHelper.COLOR_PRESETS[0])

        val view = layoutInflater.inflate(R.layout.dialog_map_settings, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val rgTheme = view.findViewById<android.widget.RadioGroup>(R.id.rgMapTheme)
        val spShape = view.findViewById<android.widget.Spinner>(R.id.spCursorShape)
        val imgPreview = view.findViewById<ImageView>(R.id.imgCursorPreview)
        val btnReset = view.findViewById<Button>(R.id.btnResetMapSettings)
        val btnSave = view.findViewById<Button>(R.id.btnSaveMapSettings)

        when (selectedTheme) {
            MapThemeHelper.THEME_DAY -> view.findViewById<android.widget.RadioButton>(R.id.rbThemeDay).isChecked = true
            MapThemeHelper.THEME_NIGHT -> view.findViewById<android.widget.RadioButton>(R.id.rbThemeNight).isChecked = true
            else -> view.findViewById<android.widget.RadioButton>(R.id.rbThemeAuto).isChecked = true
        }

        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            selectedTheme = when (checkedId) {
                R.id.rbThemeDay -> MapThemeHelper.THEME_DAY
                R.id.rbThemeNight -> MapThemeHelper.THEME_NIGHT
                else -> MapThemeHelper.THEME_AUTO
            }
        }

        val shapeOptions = listOf(
            getString(R.string.cursor_shape_arrow),
            getString(R.string.cursor_shape_motorcycle),
            getString(R.string.cursor_shape_circle),
            getString(R.string.cursor_shape_crosshair),
            getString(R.string.cursor_shape_pin)
        )
        val adapter = android.widget.ArrayAdapter(this, R.layout.spinner_item_compact, shapeOptions)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_compact)
        spShape.adapter = adapter
        spShape.setSelection(selectedShape.coerceIn(0, shapeOptions.size - 1))

        fun updatePreview() {
            val bmp = LocationCursorHelper.createCursorBitmap(this, selectedShape, selectedColor, 44)
            imgPreview.setImageBitmap(bmp)
        }

        spShape.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedShape = position
                updatePreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

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

        updateColorBorders()
        updatePreview()

        btnReset.setOnClickListener {
            selectedTheme = MapThemeHelper.THEME_AUTO
            selectedShape = LocationCursorHelper.SHAPE_NAV_ARROW
            selectedColor = LocationCursorHelper.COLOR_PRESETS[0]

            view.findViewById<android.widget.RadioButton>(R.id.rbThemeAuto).isChecked = true
            spShape.setSelection(0)
            updateColorBorders()
            updatePreview()
        }

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putInt("map_theme_mode", selectedTheme)
                putInt("map_cursor_shape", selectedShape)
                putInt("map_cursor_color", selectedColor)
                apply()
            }

            applyMapTheme()
            applyLocationCursorStyle()

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
                "application/xml",
                "text/xml",
                "application/octet-stream"
            ))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_FILE_PICK)
    }

    private fun selectOfflineMapFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
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
        val defaultColor = RouteStyleDialog.PRESET_COLORS[nextColorIndex % RouteStyleDialog.PRESET_COLORS.size]

        val dialog = RouteStyleDialog(this, defaultColor, 5f) { color, width ->
            importRoute(uri, color, width)
        }
        dialog.show()
    }

    private fun importRoute(uri: Uri, color: Int, width: Float) {
        try {
            val parsedRoutes = RouteImportHelper.parseUri(this, uri)

            if (parsedRoutes.isEmpty()) {
                Toast.makeText(this, getString(R.string.map_import_no_routes), Toast.LENGTH_SHORT).show()
                return
            }

            for (parsed in parsedRoutes) {
                val polyline = Polyline().apply {
                    setPoints(parsed.points)
                    outlinePaint.color = color
                    outlinePaint.strokeWidth = width * resources.displayMetrics.density
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }

                val route = LoadedRoute(
                    name = parsed.name,
                    points = parsed.points,
                    color = color,
                    width = width,
                    polyline = polyline
                )

                loadedRoutes.add(route)
                mapView.overlays.add(polyline)
                nextColorIndex++
            }

            mapView.invalidate()

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

        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.map_import_error, e.message ?: "Unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
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

    @SuppressLint("InflateParams")
    private fun refreshRouteList() {
        val container = findViewById<LinearLayout>(R.id.routeListItems)
        container.removeAllViews()

        for ((index, route) in loadedRoutes.withIndex()) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_route, container, false)

            val colorView = itemView.findViewById<View>(R.id.viewRouteColor)
            val colorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(route.color)
            }
            colorView.background = colorDrawable

            itemView.findViewById<TextView>(R.id.tvRouteName).text = route.name
            itemView.findViewById<TextView>(R.id.tvRoutePoints).text = "${route.points.size} pts"

            itemView.findViewById<ImageView>(R.id.btnRouteStyle).setOnClickListener {
                val dialog = RouteStyleDialog(this, route.color, route.width) { newColor, newWidth ->
                    route.color = newColor
                    route.width = newWidth
                    route.polyline?.let { poly ->
                        poly.outlinePaint.color = newColor
                        poly.outlinePaint.strokeWidth = newWidth * resources.displayMetrics.density
                    }
                    mapView.invalidate()
                    refreshRouteList()
                }
                dialog.show()
            }

            val btnVisibility = itemView.findViewById<ImageView>(R.id.btnRouteVisibility)
            btnVisibility.alpha = if (route.visible) 1.0f else 0.3f
            btnVisibility.setOnClickListener {
                route.visible = !route.visible
                if (route.visible) {
                    route.polyline?.let { if (!mapView.overlays.contains(it)) mapView.overlays.add(it) }
                } else {
                    route.polyline?.let { mapView.overlays.remove(it) }
                }
                mapView.invalidate()
                refreshRouteList()
            }

            itemView.findViewById<ImageView>(R.id.btnRouteDelete).setOnClickListener {
                route.polyline?.let { mapView.overlays.remove(it) }
                loadedRoutes.removeAt(index)
                mapView.invalidate()
                refreshRouteList()

                if (loadedRoutes.isEmpty()) {
                    findViewById<Button>(R.id.btnRouteList).visibility = View.GONE
                    findViewById<LinearLayout>(R.id.routeListContainer).visibility = View.GONE
                }
            }

            container.addView(itemView)
        }
    }

    // ─── Motorcycle Handlebar Buttons ────────────────────────────

    private val myLocationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == "com.kove.mirror.ACTION_MY_LOCATION") {
                DebugLogger.info("📍 ACTION_MY_LOCATION received in MapActivity")
                centerOnMyLocation()
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
        mapView.onResume()
        HandlebarKeyManager.addListener(handlebarKeyListener)
        val filter = android.content.IntentFilter("com.kove.mirror.ACTION_MY_LOCATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(myLocationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(myLocationReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(myLocationReceiver) } catch (_: Exception) {}
        HandlebarKeyManager.removeListener(handlebarKeyListener)
        mapView.onPause()

        val center = mapView.mapCenter
        getSharedPreferences("kove_map_prefs", MODE_PRIVATE).edit().apply {
            putFloat("map_lat", center.latitude.toFloat())
            putFloat("map_lon", center.longitude.toFloat())
            putFloat("map_zoom", mapView.zoomLevelDouble.toFloat())
            apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationOverlay?.disableMyLocation()
        locationOverlay?.disableFollowLocation()
    }
}
