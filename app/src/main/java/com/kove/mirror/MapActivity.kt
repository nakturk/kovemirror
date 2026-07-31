package com.kove.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapActivity : AppCompatActivity() {

    companion object {
        private const val REQ_FILE_PICK = 200
        private const val REQ_LOCATION_PERM = 201

        private const val LAYER_MAPS = 0
        private const val LAYER_TOPO = 1
        private const val LAYER_SATELLITE = 2
    }

    private lateinit var mapView: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private var isGpsEnabled = false
    private var currentLayer = LAYER_MAPS

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
    }

    // ─── Buttons ────────────────────────────────────────────────

    private fun setupButtons() {
        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // Layer buttons
        val btnMaps = findViewById<Button>(R.id.btnLayerMaps)
        val btnTopo = findViewById<Button>(R.id.btnLayerTopo)
        val btnSat = findViewById<Button>(R.id.btnLayerSatellite)

        btnMaps.setOnClickListener { switchLayer(LAYER_MAPS) }
        btnTopo.setOnClickListener { switchLayer(LAYER_TOPO) }
        btnSat.setOnClickListener { switchLayer(LAYER_SATELLITE) }

        // Zoom buttons
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener {
            mapView.controller.zoomIn()
        }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            mapView.controller.zoomOut()
        }

        // My location button (center on GPS)
        findViewById<Button>(R.id.btnMyLocation).setOnClickListener { centerOnMyLocation() }

        // Import route
        findViewById<Button>(R.id.btnImportRoute).setOnClickListener { openFilePicker() }

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
    }

    // ─── Layer Switching (3 modes) ──────────────────────────────

    private fun switchLayer(layer: Int) {
        currentLayer = layer
        val btnMaps = findViewById<Button>(R.id.btnLayerMaps)
        val btnTopo = findViewById<Button>(R.id.btnLayerTopo)
        val btnSat = findViewById<Button>(R.id.btnLayerSatellite)

        val activeColor = Color.parseColor("#2979FF")
        val inactiveColor = Color.parseColor("#555555")

        btnMaps.setBackgroundColor(if (layer == LAYER_MAPS) activeColor else inactiveColor)
        btnTopo.setBackgroundColor(if (layer == LAYER_TOPO) activeColor else inactiveColor)
        btnSat.setBackgroundColor(if (layer == LAYER_SATELLITE) activeColor else inactiveColor)

        when (layer) {
            LAYER_MAPS -> mapView.setTileSource(TileSourceFactory.MAPNIK)
            LAYER_TOPO -> mapView.setTileSource(openTopoTileSource)
            LAYER_SATELLITE -> mapView.setTileSource(satelliteTileSource)
        }
        mapView.invalidate()
    }

    // ─── Center on My Location ──────────────────────────────────

    private fun centerOnMyLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        // If GPS overlay is not enabled, enable it first
        if (!isGpsEnabled) {
            toggleGps()
        }

        // Center on current location
        locationOverlay?.myLocation?.let { loc ->
            mapView.controller.animateTo(loc)
            mapView.controller.setZoom(16.0)
        } ?: run {
            Toast.makeText(this, getString(R.string.map_waiting_gps), Toast.LENGTH_SHORT).show()
            // Enable follow mode so it auto-centers when fix is obtained
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
            btnGps.text = getString(R.string.map_btn_gps)
            btnGps.setBackgroundColor(Color.parseColor("#1565C0"))
        } else {
            val provider = GpsMyLocationProvider(this)
            provider.locationUpdateMinTime = 2000
            provider.locationUpdateMinDistance = 5f

            locationOverlay = MyLocationNewOverlay(provider, mapView).apply {
                enableMyLocation()
                enableFollowLocation()
            }
            mapView.overlays.add(locationOverlay)
            isGpsEnabled = true
            btnGps.text = getString(R.string.map_btn_gps_on)
            btnGps.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
        mapView.invalidate()
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_PICK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> showStyleDialogThenImport(uri) }
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

            // Zoom to fit all imported routes
            if (parsedRoutes.isNotEmpty()) {
                val allPoints = parsedRoutes.flatMap { it.points }
                zoomToFitPoints(allPoints)
            }

            // Show route list button
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

            // Color indicator
            val colorView = itemView.findViewById<View>(R.id.viewRouteColor)
            val colorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(route.color)
            }
            colorView.background = colorDrawable

            // Name and points
            itemView.findViewById<TextView>(R.id.tvRouteName).text = route.name
            itemView.findViewById<TextView>(R.id.tvRoutePoints).text = "${route.points.size} pts"

            // Style button
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

            // Visibility toggle
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

            // Delete button
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

    // ─── Lifecycle ──────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()

        // Save map position
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
