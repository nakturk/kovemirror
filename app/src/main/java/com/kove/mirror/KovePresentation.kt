package com.kove.mirror

import android.app.Presentation
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Display
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Secondary Independent Display Presentation for Kove TFT Cluster.
 * Renders dedicated OSM Map + Motorcycle Instrument Cluster on VirtualDisplay.
 */
class KovePresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvHeadingValue: TextView
    private lateinit var tvAltValue: TextView
    private lateinit var tvGpsStatus: TextView

    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var locationManager: LocationManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = context.packageName
        setContentView(R.layout.layout_presentation_dashboard)

        mapView = findViewById(R.id.presentationMapView)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvHeadingValue = findViewById(R.id.tvHeadingValue)
        tvAltValue = findViewById(R.id.tvAltValue)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)

        setupMap()
        setupLocationTracking()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(GeoPoint(39.92077, 32.85411)) // Default Ankara

        val provider = GpsMyLocationProvider(context)
        myLocationOverlay = MyLocationNewOverlay(provider, mapView).apply {
            enableMyLocation()
            enableFollowLocation()
        }
        mapView.overlays.add(myLocationOverlay)
    }

    private fun setupLocationTracking() {
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                this
            )
        } catch (e: Exception) {
            DebugLogger.error("❌ Presentation Location init error: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmH = (location.speed * 3.6f).toInt()
        tvSpeedValue.text = speedKmH.toString()

        if (location.hasBearing()) {
            val bearing = location.bearing.toInt()
            val dir = getCompassDirection(bearing)
            tvHeadingValue.text = "$dir $bearing°"
        }

        if (location.hasAltitude()) {
            tvAltValue.text = "${location.altitude.toInt()} m"
        }

        tvGpsStatus.text = "GPS FIXED"
        tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))

        mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
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
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        mapView.onDetach()
        super.onStop()
    }
}
