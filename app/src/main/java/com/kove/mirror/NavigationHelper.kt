package com.kove.mirror

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Helper to fetch turn-by-turn routes from OSRM (Open Source Routing Machine) API.
 */
object NavigationHelper {

    data class RouteStep(
        val instruction: String,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val location: GeoPoint,
        val modifier: String,
        val type: String
    )

    data class NavigationRoute(
        val geometryPoints: List<GeoPoint>,
        val totalDistanceMeters: Double,
        val totalDurationSeconds: Double,
        val steps: List<RouteStep>
    )

    fun fetchRoute(
        start: GeoPoint,
        destination: GeoPoint,
        onSuccess: (NavigationRoute) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                // OSRM Driving URL
                val urlString = "https://router.project-osrm.org/route/v1/driving/" +
                        "${start.longitude},${start.latitude};" +
                        "${destination.longitude},${destination.latitude}" +
                        "?overview=full&geometries=geojson&steps=true"

                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "KoveMirror/2.0")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val route = parseOsrmJson(response.toString())
                    if (route != null) {
                        onSuccess(route)
                    } else {
                        onError("Route response empty or invalid")
                    }
                } else {
                    onError("HTTP Error: ${conn.responseCode}")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Network error")
            }
        }
    }

    private fun parseOsrmJson(jsonStr: String): NavigationRoute? {
        val root = JSONObject(jsonStr)
        val code = root.optString("code")
        if (code != "Ok") return null

        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) return null

        val routeObj = routes.getJSONObject(0)
        val totalDistance = routeObj.optDouble("distance", 0.0)
        val totalDuration = routeObj.optDouble("duration", 0.0)

        // Parse Geometry (GeoJSON LineString)
        val geometryObj = routeObj.getJSONObject("geometry")
        val coordinates = geometryObj.getJSONArray("coordinates")
        val geoPoints = mutableListOf<GeoPoint>()

        for (i in 0 until coordinates.length()) {
            val pointArr = coordinates.getJSONArray(i)
            val lon = pointArr.getDouble(0)
            val lat = pointArr.getDouble(1)
            geoPoints.add(GeoPoint(lat, lon))
        }

        // Parse Turn-by-Turn Steps
        val steps = mutableListOf<RouteStep>()
        val legs = routeObj.getJSONArray("legs")
        if (legs.length() > 0) {
            val leg = legs.getJSONObject(0)
            val legSteps = leg.getJSONArray("steps")

            for (i in 0 until legSteps.length()) {
                val stepObj = legSteps.getJSONObject(i)
                val dist = stepObj.optDouble("distance", 0.0)
                val dur = stepObj.optDouble("duration", 0.0)
                val name = stepObj.optString("name", "")

                val maneuver = stepObj.optJSONObject("maneuver")
                val type = maneuver?.optString("type", "straight") ?: "straight"
                val modifier = maneuver?.optString("modifier", "") ?: ""
                val locationArr = maneuver?.optJSONArray("location")
                val locPoint = if (locationArr != null && locationArr.length() >= 2) {
                    GeoPoint(locationArr.getDouble(1), locationArr.getDouble(0))
                } else {
                    GeoPoint(0.0, 0.0)
                }

                val instruction = buildInstructionText(type, modifier, name)
                steps.add(RouteStep(instruction, dist, dur, locPoint, modifier, type))
            }
        }

        return NavigationRoute(geoPoints, totalDistance, totalDuration, steps)
    }

    private fun buildInstructionText(type: String, modifier: String, streetName: String): String {
        val street = if (streetName.isNotEmpty()) " -> $streetName" else ""
        val modText = when (modifier) {
            "left" -> "left"
            "right" -> "right"
            "slight left" -> "slight left"
            "slight right" -> "slight right"
            "sharp left" -> "sharp left"
            "sharp right" -> "sharp right"
            "straight" -> "straight"
            "uturn" -> "U-turn"
            else -> modifier
        }

        return when (type) {
            "turn" -> "Turn $modText$street"
            "new name" -> "Continue $modText$street"
            "depart" -> "Head $modText$street"
            "arrive" -> "Arrived at destination"
            "roundabout", "rotary" -> "Enter roundabout$street"
            "fork" -> "Take $modText fork$street"
            "off ramp", "on ramp" -> "Take ramp $modText$street"
            else -> "$modText$street".trim().capitalizeFirstLetter()
        }
    }

    private fun String.capitalizeFirstLetter(): String {
        return if (isNotEmpty()) this[0].uppercaseChar() + substring(1) else this
    }
}
