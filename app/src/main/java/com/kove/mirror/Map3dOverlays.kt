package com.kove.mirror

import android.graphics.Bitmap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Draws the 2D map overlays (imported routes, navigation route, recorded GPX
 * track, current location and destination marker) onto the MapLibre 3D view.
 */
class Map3dOverlays(private val map: MapLibreMap, private val density: Float) {

    data class RouteData(
        val name: String,
        val points: List<LatLng>,
        val color: Int,
        val width: Float,
        val visible: Boolean
    )

    private var style: Style? = null

    private var routes: List<RouteData> = emptyList()
    private var navPoints: List<LatLng>? = null
    private var trackPoints: List<LatLng> = emptyList()
    private var trackColor: Int = 0xFFEF4444.toInt()
    private var location: LatLng? = null
    private var locationBearing: Double? = null
    private var destination: LatLng? = null
    private var cursorBitmap: Bitmap? = null
    private var destPinBitmap: Bitmap? = null

    private var routesSource: GeoJsonSource? = null
    private var navSource: GeoJsonSource? = null
    private var trackSource: GeoJsonSource? = null
    private var locationSource: GeoJsonSource? = null
    private var destinationSource: GeoJsonSource? = null

    fun onStyleLoaded(style: Style) {
        this.style = style

        routesSource = GeoJsonSource("kove-routes-3d").also { style.addSource(it) }
        style.addLayer(
            LineLayer("kove-routes-line", "kove-routes-3d")
                .withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(Expression.get("width")),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
        )

        navSource = GeoJsonSource("kove-nav-3d").also { style.addSource(it) }
        style.addLayer(
            LineLayer("kove-nav-line", "kove-nav-3d")
                .withProperties(
                    PropertyFactory.lineColor("#2563EB"),
                    PropertyFactory.lineWidth(7f * density),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
        )

        trackSource = GeoJsonSource("kove-track-3d").also { style.addSource(it) }
        style.addLayer(
            LineLayer("kove-track-line", "kove-track-3d")
                .withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(8f * density),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
        )

        locationSource = GeoJsonSource("kove-loc-3d").also { style.addSource(it) }
        style.addLayer(
            SymbolLayer("kove-loc-arrow", "kove-loc-3d")
                .withProperties(
                    PropertyFactory.iconImage("location-cursor"),
                    PropertyFactory.iconSize(1f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconAllowOverlap(true)
                )
        )
        cursorBitmap?.let { style.addImage("location-cursor", it) }

        destinationSource = GeoJsonSource("kove-dest-3d").also { style.addSource(it) }
        destPinBitmap?.let { style.addImage("dest-pin", it) }
        style.addLayer(
            SymbolLayer("kove-dest-pin", "kove-dest-3d")
                .withProperties(
                    PropertyFactory.iconImage("dest-pin"),
                    PropertyFactory.iconSize(1f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true)
                )
        )

        refreshRoutes()
        refreshNav()
        refreshTrack()
        refreshLocation()
        refreshDestination()
    }

    fun isReady(): Boolean = style != null

    fun setRoutes(routes: List<RouteData>) {
        this.routes = routes
        refreshRoutes()
    }

    fun setNavigationRoute(points: List<LatLng>?) {
        navPoints = points
        refreshNav()
    }

    fun setTrack(points: List<LatLng>, color: Int) {
        trackPoints = points
        trackColor = color
        refreshTrack()
    }

    fun setLocation(latLng: LatLng?, bearing: Double? = null) {
        location = latLng
        locationBearing = bearing
        refreshLocation()
    }

    fun setDestination(latLng: LatLng?) {
        destination = latLng
        refreshDestination()
    }

    fun setCursorIcon(bitmap: Bitmap?) {
        cursorBitmap = bitmap
        style?.let { s ->
            if (bitmap != null) {
                s.addImage("location-cursor", bitmap)
            }
        }
    }

    fun setDestinationIcon(bitmap: Bitmap?) {
        destPinBitmap = bitmap
        style?.let { s ->
            if (bitmap != null) {
                s.addImage("dest-pin", bitmap)
            }
        }
    }

    private fun refreshRoutes() {
        val source = routesSource ?: return
        val features = routes.filter { it.visible }.map { r ->
            val feature = Feature.fromGeometry(lineString(r.points))
            feature.addStringProperty("color", colorToHex(r.color))
            feature.addNumberProperty("width", r.width * density)
            feature
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun refreshNav() {
        val source = navSource ?: return
        val points = navPoints
        val features = if (points == null || points.size < 2) {
            emptyList<Feature>()
        } else {
            listOf(Feature.fromGeometry(lineString(points)))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun refreshTrack() {
        val source = trackSource ?: return
        val points = trackPoints
        val features = if (points.size < 2) {
            emptyList<Feature>()
        } else {
            val feature = Feature.fromGeometry(lineString(points))
            feature.addStringProperty("color", colorToHex(trackColor))
            listOf(feature)
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun refreshLocation() {
        val source = locationSource ?: return
        val loc = location
        val features = if (loc == null) {
            emptyList<Feature>()
        } else {
            val feature = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
            feature.addNumberProperty("bearing", locationBearing ?: 0.0)
            listOf(feature)
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun refreshDestination() {
        val source = destinationSource ?: return
        val dest = destination
        val features = if (dest == null) {
            emptyList<Feature>()
        } else {
            listOf(Feature.fromGeometry(Point.fromLngLat(dest.longitude, dest.latitude)))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun lineString(points: List<LatLng>): LineString {
        val coords = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        return LineString.fromLngLats(coords)
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }
}