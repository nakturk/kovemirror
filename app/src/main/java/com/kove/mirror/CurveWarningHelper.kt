package com.kove.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometric analysis engine to detect sharp curves, hairpins, and calculate safe
 * motorcycle cornering entry speeds along any route polyline.
 * 100% offline with zero external API calls.
 */
object CurveWarningHelper {

    enum class CurveType {
        HAIRPIN, // U-Viraj / İğne deliği (>110° or R < 18m)
        SHARP,   // Sert Viraj (>65° or R < 35m)
        MEDIUM   // Orta Viraj (45° - 65°)
    }

    data class CurvePoint(
        val point: GeoPoint,
        val turnAngleDeg: Double,
        val turnDirection: String, // "LEFT", "RIGHT", "U_TURN"
        val curveType: CurveType,
        val curveRadiusMeters: Double,
        val recommendedSpeedKmH: Int,
        val distanceFromStartMeters: Double,
        val description: String
    )

    /**
     * Scans route points and detects sharp curves based on angular deflection and radius.
     */
    fun detectCurves(
        points: List<GeoPoint>,
        minAngleDeg: Double = 55.0
    ): List<CurvePoint> {
        if (points.size < 4) return emptyList()

        val detected = mutableListOf<CurvePoint>()
        var cumulativeDist = 0.0
        val dists = mutableListOf(0.0)

        for (i in 0 until points.size - 1) {
            cumulativeDist += points[i].distanceToAsDouble(points[i + 1])
            dists.add(cumulativeDist)
        }

        val n = points.size
        var i = 1
        while (i < n - 2) {
            // Check angular deflection across sliding window (lookahead 15-40m)
            var startIdx = i - 1
            while (startIdx > 0 && dists[i] - dists[startIdx] < 20.0) {
                startIdx--
            }

            var endIdx = i + 1
            while (endIdx < n - 1 && dists[endIdx] - dists[i] < 20.0) {
                endIdx++
            }

            val pStart = points[startIdx]
            val pMid = points[i]
            val pEnd = points[endIdx]

            val bIn = bearingDegrees(pStart, pMid)
            val bOut = bearingDegrees(pMid, pEnd)

            var diffAngle = bOut - bIn
            while (diffAngle > 180.0) diffAngle -= 360.0
            while (diffAngle < -180.0) diffAngle += 360.0

            val absAngle = abs(diffAngle)
            val arcLength = (dists[endIdx] - dists[startIdx]).coerceAtLeast(10.0)

            if (absAngle >= minAngleDeg) {
                val rad = Math.toRadians(absAngle)
                val radius = (arcLength / rad).coerceIn(6.0, 150.0)

                // Safe motorcycle speed formula: V = sqrt(mu * g * R) * 3.6
                // mu = 0.35 (comfortable lean margin for street riding)
                val safeSpeed = (sqrt(0.35 * 9.81 * radius) * 3.6).toInt().coerceIn(20, 80)

                val direction = when {
                    absAngle >= 120.0 -> "U_TURN"
                    diffAngle > 0 -> "RIGHT"
                    else -> "LEFT"
                }

                val type = when {
                    absAngle >= 110.0 || radius <= 16.0 -> CurveType.HAIRPIN
                    absAngle >= 65.0 || radius <= 38.0 -> CurveType.SHARP
                    else -> CurveType.MEDIUM
                }

                val desc = when (direction) {
                    "U_TURN" -> "U-Viraj"
                    "LEFT" -> if (type == CurveType.HAIRPIN) "U-Dönüş Sola" else "Sert Sol Viraj"
                    else -> if (type == CurveType.HAIRPIN) "U-Dönüş Sağa" else "Sert Sağ Viraj"
                }

                detected.add(
                    CurvePoint(
                        point = pMid,
                        turnAngleDeg = absAngle,
                        turnDirection = direction,
                        curveType = type,
                        curveRadiusMeters = radius,
                        recommendedSpeedKmH = safeSpeed,
                        distanceFromStartMeters = dists[i],
                        description = desc
                    )
                )

                // Skip ahead to avoid detecting duplicate points for the same curve
                var nextIdx = i + 1
                while (nextIdx < n - 1 && dists[nextIdx] - dists[i] < 45.0) {
                    nextIdx++
                }
                i = nextIdx
            } else {
                i++
            }
        }

        return detected
    }

    /**
     * Returns a localized description for the curve.
     */
    fun getLocalizedDescription(context: Context, curve: CurvePoint): String {
        return when (curve.curveType) {
            CurveType.HAIRPIN -> context.getString(R.string.curve_hairpin)
            CurveType.SHARP -> if (curve.turnDirection == "LEFT") {
                context.getString(R.string.curve_sharp_left)
            } else {
                context.getString(R.string.curve_sharp_right)
            }
            CurveType.MEDIUM -> if (curve.turnDirection == "LEFT") {
                context.getString(R.string.curve_medium_left)
            } else {
                context.getString(R.string.curve_medium_right)
            }
        }
    }

    /**
     * Finds the nearest upcoming sharp curve in front of the rider within a proximity window.
     */
    fun findUpcomingCurve(
        currentLocation: GeoPoint,
        currentBearing: Float,
        curves: List<CurvePoint>,
        maxDistanceMeters: Double = 250.0
    ): Pair<CurvePoint, Double>? {
        if (curves.isEmpty()) return null

        var closestCurve: CurvePoint? = null
        var minDistance = Double.MAX_VALUE

        for (c in curves) {
            val dist = currentLocation.distanceToAsDouble(c.point)
            if (dist in 15.0..maxDistanceMeters) {
                // Check if curve is in front of rider (bearing alignment within 80 degrees)
                val bearingToCurve = bearingDegrees(currentLocation, c.point)
                var bearingDiff = abs(bearingToCurve - currentBearing)
                while (bearingDiff > 180f) bearingDiff -= 360f
                bearingDiff = abs(bearingDiff)

                if (currentBearing == 0f || bearingDiff < 85f) {
                    if (dist < minDistance) {
                        minDistance = dist
                        closestCurve = c
                    }
                }
            }
        }

        return if (closestCurve != null) Pair(closestCurve, minDistance) else null
    }

    /**
     * Creates a compact color-coded badge bitmap for the 2D/3D map without speed text.
     */
    fun createCurveBadgeBitmap(
        context: Context,
        curve: CurvePoint,
        scaleFactor: Float = 1.0f
    ): Bitmap {
        val density = context.resources.displayMetrics.density * scaleFactor

        val (icon, bgColor) = when (curve.curveType) {
            CurveType.HAIRPIN -> Pair("⚠️", Color.parseColor("#DC2626")) // Red (Hairpin / U-Turn)
            CurveType.SHARP -> Pair(
                if (curve.turnDirection == "LEFT") "⬅️" else "➡️",
                Color.parseColor("#EA580C") // Orange (Sharp)
            )
            CurveType.MEDIUM -> Pair(
                if (curve.turnDirection == "LEFT") "↖️" else "↗️",
                Color.parseColor("#EAB308") // Yellow / Amber (Medium)
            )
        }

        val paintIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * density
        }

        val iconWidth = paintIcon.measureText(icon)
        val padding = 5f * density
        val size = ((iconWidth + padding * 2).coerceAtLeast(26f * density)).toInt()

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
            alpha = 240
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            alpha = 220
        }

        val rect = RectF(
            borderPaint.strokeWidth,
            borderPaint.strokeWidth,
            size - borderPaint.strokeWidth,
            size - borderPaint.strokeWidth
        )
        val cornerRadius = size / 2f

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        val iconBaseline = (size / 2f) - ((paintIcon.descent() + paintIcon.ascent()) / 2f)
        val iconX = (size - iconWidth) / 2f

        canvas.drawText(icon, iconX, iconBaseline - 1f * density, paintIcon)

        return bitmap
    }

    private fun bearingDegrees(p1: GeoPoint, p2: GeoPoint): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var b = Math.toDegrees(atan2(y, x))
        return (b + 360.0) % 360.0
    }
}
