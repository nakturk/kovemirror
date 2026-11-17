package com.kove.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Helper to fetch, parse, and render live weather checkpoints along an active route
 * using the free Open-Meteo API (https://open-meteo.com/).
 */
object RouteWeatherHelper {

    private val executor = Executors.newSingleThreadExecutor()

    data class RouteWeatherPoint(
        val point: GeoPoint,
        val distanceFromStartKm: Double,
        val temperature: Double,
        val precipitationProbability: Int,
        val weatherCode: Int,
        val weatherDescription: String,
        val weatherEmoji: String,
        val windSpeedKmH: Double,
        val precipitationMm: Double
    )

    /**
     * Samples a given polyline at uniform distances to produce 3 to 7 strategic checkpoints.
     */
    fun sampleRoutePoints(points: List<GeoPoint>): List<Pair<GeoPoint, Double>> {
        if (points.isEmpty()) return emptyList()
        if (points.size <= 2) {
            val totalDistKm = if (points.size == 2) points[0].distanceToAsDouble(points[1]) / 1000.0 else 0.0
            return points.mapIndexed { idx, pt -> Pair(pt, if (idx == 0) 0.0 else totalDistKm) }
        }

        // Calculate cumulative distances
        val cumDistKm = mutableListOf(0.0)
        var totalDist = 0.0
        for (i in 0 until points.size - 1) {
            totalDist += points[i].distanceToAsDouble(points[i + 1]) / 1000.0
            cumDistKm.add(totalDist)
        }

        if (totalDist < 5.0) {
            // Very short route -> just start and end
            return listOf(Pair(points.first(), 0.0), Pair(points.last(), totalDist))
        }

        // Determine target count based on distance
        val targetCount = when {
            totalDist < 30.0 -> 3
            totalDist < 80.0 -> 4
            totalDist < 160.0 -> 5
            totalDist < 300.0 -> 6
            else -> 7
        }

        val stepDist = totalDist / (targetCount - 1)
        val result = mutableListOf<Pair<GeoPoint, Double>>()
        result.add(Pair(points.first(), 0.0))

        var currentTargetDist = stepDist
        for (i in 1 until points.size - 1) {
            if (result.size >= targetCount - 1) break
            if (cumDistKm[i] >= currentTargetDist) {
                result.add(Pair(points[i], cumDistKm[i]))
                currentTargetDist += stepDist
            }
        }

        result.add(Pair(points.last(), totalDist))
        return result
    }

    /**
     * Fetches weather from Open-Meteo API for multiple coordinates in parallel/batch.
     */
    fun fetchRouteWeather(
        checkpoints: List<Pair<GeoPoint, Double>>,
        callback: (List<RouteWeatherPoint>) -> Unit
    ) {
        if (checkpoints.isEmpty()) {
            callback(emptyList())
            return
        }

        executor.execute {
            try {
                val lats = checkpoints.joinToString(",") { String.format(Locale.US, "%.4f", it.first.latitude) }
                val lons = checkpoints.joinToString(",") { String.format(Locale.US, "%.4f", it.first.longitude) }

                val urlStr = "https://api.open-meteo.com/v1/forecast?" +
                        "latitude=$lats&longitude=$lons" +
                        "&current=temperature_2m,precipitation,precipitation_probability,weather_code,wind_speed_10m" +
                        "&wind_speed_unit=kmh"

                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "KoveMirror/2.0")
                }

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val weatherPoints = parseOpenMeteoResponse(responseText, checkpoints)
                    DebugLogger.success("🌤️ [RouteWeather] Fetched weather for ${weatherPoints.size} checkpoint(s)")
                    callback(weatherPoints)
                } else {
                    DebugLogger.warning("⚠️ [RouteWeather] HTTP ${conn.responseCode}: ${conn.responseMessage}")
                    callback(emptyList())
                }
            } catch (e: Exception) {
                DebugLogger.error("❌ [RouteWeather] Fetch error: ${e.message}")
                callback(emptyList())
            }
        }
    }

    private fun parseOpenMeteoResponse(
        jsonStr: String,
        checkpoints: List<Pair<GeoPoint, Double>>
    ): List<RouteWeatherPoint> {
        val result = mutableListOf<RouteWeatherPoint>()
        try {
            // If single location, response is JSONObject. If multiple locations, response is JSONArray of JSONObjects
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    if (i >= checkpoints.size) break
                    val item = array.getJSONObject(i)
                    parseSingleLocationWeather(item, checkpoints[i])?.let { result.add(it) }
                }
            } else {
                val obj = JSONObject(trimmed)
                if (checkpoints.isNotEmpty()) {
                    parseSingleLocationWeather(obj, checkpoints[0])?.let { result.add(it) }
                }
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ [RouteWeather] JSON Parse error: ${e.message}")
        }
        return result
    }

    private fun parseSingleLocationWeather(
        obj: JSONObject,
        checkpoint: Pair<GeoPoint, Double>
    ): RouteWeatherPoint? {
        val current = obj.optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m", 0.0)
        val precipProb = current.optInt("precipitation_probability", 0)
        val precipMm = current.optDouble("precipitation", 0.0)
        val weatherCode = current.optInt("weather_code", 0)
        val windSpeed = current.optDouble("wind_speed_10m", 0.0)

        val (emoji, desc) = getWeatherInfoFromWmoCode(weatherCode)

        return RouteWeatherPoint(
            point = checkpoint.first,
            distanceFromStartKm = checkpoint.second,
            temperature = temp,
            precipitationProbability = precipProb,
            weatherCode = weatherCode,
            weatherDescription = desc,
            weatherEmoji = emoji,
            windSpeedKmH = windSpeed,
            precipitationMm = precipMm
        )
    }

    /**
     * WMO Weather Interpretation Codes (WW) mapping
     */
    fun getWeatherInfoFromWmoCode(code: Int): Pair<String, String> {
        return when (code) {
            0 -> Pair("☀️", "Açık")
            1 -> Pair("🌤️", "Az Bulutlu")
            2 -> Pair("⛅", "Parçalı Bulutlu")
            3 -> Pair("☁️", "Kapalı / Bulutlu")
            45, 48 -> Pair("🌫️", "Sisli")
            51, 53, 55 -> Pair("🌦️", "Çisenti")
            56, 57 -> Pair("🌧️", "Dondurucu Çisenti")
            61 -> Pair("🌧️", "Hafif Yağmur")
            63 -> Pair("🌧️", "Orta Yağmur")
            65 -> Pair("🌧️", "Şiddetli Yağmur")
            66, 67 -> Pair("🌨️", "Dondurucu Yağmur")
            71 -> Pair("🌨️", "Hafif Kar")
            73 -> Pair("🌨️", "Orta Kar")
            75 -> Pair("❄️", "Yoğun Kar")
            77 -> Pair("❄️", "Kar Taneleri")
            80, 81 -> Pair("🌦️", "Sağanak Yağmur")
            82 -> Pair("⛈️", "Şiddetli Sağanak")
            85, 86 -> Pair("🌨️", "Kar Sağanağı")
            95 -> Pair("⛈️", "Gök Gürültülü Fırtına")
            96, 99 -> Pair("⛈️", "Dolu & Fırtına")
            else -> Pair("🌤️", "Hava Durumu")
        }
    }

    /**
     * Creates a motorcycle-optimized high-contrast pill badge bitmap for the map.
     */
    fun createWeatherBadgeBitmap(
        context: Context,
        weather: RouteWeatherPoint,
        scaleFactor: Float = 1.0f
    ): Bitmap {
        val density = context.resources.displayMetrics.density * scaleFactor

        // Colors based on precipitation risk
        val bgColor = when {
            weather.precipitationProbability >= 70 || weather.weatherCode in listOf(65, 82, 95, 96, 99) -> Color.parseColor("#E11D48") // Rose red alert
            weather.precipitationProbability >= 40 || weather.weatherCode in 61..63 -> Color.parseColor("#D97706") // Amber warning
            weather.temperature <= 2.0 -> Color.parseColor("#0284C7") // Ice blue
            else -> Color.parseColor("#0F172A") // Slate dark
        }

        val tempText = "${weather.temperature.toInt()}°C"
        val rainText = "${weather.precipitationProbability}%"
        val emojiText = weather.weatherEmoji

        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val paintEmoji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
        }

        val paintSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#93C5FD")
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val emojiWidth = paintEmoji.measureText(emojiText)
        val tempWidth = paintText.measureText(tempText)
        val rainIconWidth = paintEmoji.measureText("💧")
        val rainWidth = paintSub.measureText(rainText)

        val paddingH = 8f * density
        val paddingV = 6f * density
        val spacing = 4f * density

        val totalWidth = (paddingH * 2 + emojiWidth + spacing + tempWidth + spacing * 1.5f + rainIconWidth + spacing * 0.5f + rainWidth).toInt()
        val totalHeight = (32f * density).toInt()

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)

        // Background pill
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
            alpha = 240
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            alpha = 180
        }

        val rect = RectF(
            borderPaint.strokeWidth,
            borderPaint.strokeWidth,
            totalWidth - borderPaint.strokeWidth,
            totalHeight - borderPaint.strokeWidth
        )
        val cornerRadius = totalHeight / 2f

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Draw Texts
        val baseline = (totalHeight / 2f) - ((paintText.descent() + paintText.ascent()) / 2f)
        var curX = paddingH

        canvas.drawText(emojiText, curX, baseline - 1f * density, paintEmoji)
        curX += emojiWidth + spacing

        canvas.drawText(tempText, curX, baseline, paintText)
        curX += tempWidth + spacing * 1.5f

        canvas.drawText("💧", curX, baseline - 1f * density, paintEmoji)
        curX += rainIconWidth + spacing * 0.5f

        canvas.drawText(rainText, curX, baseline, paintSub)

        return bitmap
    }
}
