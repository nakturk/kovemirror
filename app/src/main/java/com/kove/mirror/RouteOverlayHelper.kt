package com.kove.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.roundToInt

object RouteOverlayHelper {

    /**
     * Generates direction arrow markers along the route points at regular distance intervals.
     * The arrows lie flat on the map and rotate precisely with the segment bearing.
     */
    fun createDirectionArrowOverlays(
        context: Context,
        mapView: MapView,
        points: List<GeoPoint>,
        arrowColor: Int,
        intervalMeters: Double = 3000.0,
        scaleFactor: Float = 1.0f
    ): List<Marker> {
        if (points.size < 2) return emptyList()
        val markers = mutableListOf<Marker>()
        val density = context.resources.displayMetrics.density * scaleFactor
        val targetDpi = context.resources.displayMetrics.densityDpi

        val arrowBmp = createArrowBitmap(density, arrowColor, targetDpi)
        val arrowDrawable = BitmapDrawable(context.resources, arrowBmp)

        var accumulatedDist = 0.0
        var nextTargetDist = intervalMeters

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val segDist = p1.distanceToAsDouble(p2)
            if (segDist <= 0) continue

            val segBearing = p1.bearingTo(p2).toFloat()

            while (accumulatedDist + segDist >= nextTargetDist) {
                val fraction = (nextTargetDist - accumulatedDist) / segDist
                val interpLat = p1.latitude + (p2.latitude - p1.latitude) * fraction
                val interpLon = p1.longitude + (p2.longitude - p1.longitude) * fraction
                val pos = GeoPoint(interpLat, interpLon)

                val marker = Marker(mapView).apply {
                    position = pos
                    icon = arrowDrawable
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    isFlat = true
                    rotation = -segBearing
                    setInfoWindow(null)
                }
                markers.add(marker)
                nextTargetDist += intervalMeters
            }
            accumulatedDist += segDist
        }
        return markers
    }

    /**
     * Generates distance marker badges (e.g. 5 km, 10 km) along the route.
     */
    fun createDistanceMarkerOverlays(
        context: Context,
        mapView: MapView,
        points: List<GeoPoint>,
        intervalKm: Int = 5,
        scaleFactor: Float = 1.0f
    ): List<Marker> {
        if (points.size < 2 || intervalKm <= 0) return emptyList()
        val markers = mutableListOf<Marker>()
        val density = context.resources.displayMetrics.density * scaleFactor
        val targetDpi = context.resources.displayMetrics.densityDpi

        var accumulatedDist = 0.0
        var currentKmTarget = intervalKm

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val segDist = p1.distanceToAsDouble(p2)
            if (segDist <= 0) continue

            while (accumulatedDist + segDist >= currentKmTarget * 1000.0) {
                val targetM = currentKmTarget * 1000.0
                val fraction = (targetM - accumulatedDist) / segDist
                val interpLat = p1.latitude + (p2.latitude - p1.latitude) * fraction
                val interpLon = p1.longitude + (p2.longitude - p1.longitude) * fraction
                val pos = GeoPoint(interpLat, interpLon)

                val badgeText = "$currentKmTarget km"
                val badgeBmp = createDistanceBadgeBitmap(density, badgeText, targetDpi)

                val marker = Marker(mapView).apply {
                    position = pos
                    title = badgeText
                    icon = BitmapDrawable(context.resources, badgeBmp)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                }
                markers.add(marker)
                currentKmTarget += intervalKm
            }
            accumulatedDist += segDist
        }
        return markers
    }

    private fun createArrowBitmap(density: Float, color: Int, targetDpi: Int): Bitmap {
        val size = (24 * density).toInt().coerceAtLeast(20)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.density = targetDpi
        val canvas = Canvas(bmp)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            this.color = Color.parseColor("#000000")
        }

        val path = Path().apply {
            moveTo(size / 2f, size * 0.12f)
            lineTo(size * 0.82f, size * 0.85f)
            lineTo(size / 2f, size * 0.68f)
            lineTo(size * 0.18f, size * 0.85f)
            close()
        }

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        return bmp
    }

    private fun createDistanceBadgeBitmap(density: Float, text: String, targetDpi: Int): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * density
            color = Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        val paddingX = 8f * density
        val paddingY = 4f * density

        val width = (textWidth + paddingX * 2).roundToInt().coerceAtLeast(24)
        val height = (textHeight + paddingY * 2).roundToInt().coerceAtLeast(18)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.density = targetDpi
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E60F172A") // Slate Dark with 90% opacity
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8") // Sky Blue border
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val rx = 6f * density
        canvas.drawRoundRect(rect, rx, rx, bgPaint)
        canvas.drawRoundRect(rect, rx, rx, borderPaint)

        val x = (width - textWidth) / 2f
        val y = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, x, y, textPaint)

        return bmp
    }
}
