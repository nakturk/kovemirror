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
        val type: String,
        val shapeIndex: Int = 0
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
        avoidTolls: Boolean = false,
        avoidHighways: Boolean = false,
        useShortest: Boolean = false,
        onSuccess: (NavigationRoute) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                // Primary Engine: Valhalla API (strictly respects use_tolls=0.0 and use_highways=0.0)
                var route = fetchValhallaRoute(start, destination, avoidTolls, avoidHighways, useShortest)

                // Secondary Engine: OpenStreetMap Germany OSRM
                if (route == null) {
                    val excludes = mutableListOf<String>()
                    if (avoidTolls) excludes.add("toll")
                    if (avoidHighways) excludes.add("motorway")
                    val excludeParam = if (excludes.isNotEmpty()) "&exclude=" + excludes.joinToString(",") else ""

                    val primaryUrl = "https://routing.openstreetmap.de/routed-car/route/v1/driving/" +
                            "${start.longitude},${start.latitude};" +
                            "${destination.longitude},${destination.latitude}" +
                            "?overview=full&geometries=geojson&steps=true&alternatives=3$excludeParam"
                    route = queryOsmEndpoint(primaryUrl, avoidTolls, avoidHighways, useShortest)
                }

                // Tertiary Engine: Standard OSRM demo server fallback
                if (route == null) {
                    val fallbackUrl = "https://router.project-osrm.org/route/v1/driving/" +
                            "${start.longitude},${start.latitude};" +
                            "${destination.longitude},${destination.latitude}" +
                            "?overview=full&geometries=geojson&steps=true&alternatives=3"
                    route = queryOsmEndpoint(fallbackUrl, avoidTolls, avoidHighways, useShortest)
                }

                if (route != null) {
                    onSuccess(route)
                } else {
                    onError("Route unavailable or failed")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Network error")
            }
        }
    }

    private fun fetchValhallaRoute(
        start: GeoPoint,
        destination: GeoPoint,
        avoidTolls: Boolean,
        avoidHighways: Boolean,
        useShortest: Boolean
    ): NavigationRoute? {
        try {
            val useTollsVal = if (avoidTolls) 0.0 else 1.0
            val useHighwaysVal = if (avoidHighways) 0.0 else 1.0

            val jsonBody = JSONObject().apply {
                put("locations", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("lat", start.latitude); put("lon", start.longitude) })
                    put(JSONObject().apply { put("lat", destination.latitude); put("lon", destination.longitude) })
                })
                put("costing", "auto")
                put("costing_options", JSONObject().apply {
                    put("auto", JSONObject().apply {
                        put("use_tolls", useTollsVal)
                        put("use_highways", useHighwaysVal)
                        if (useShortest) put("shortest", true)
                    })
                })
                put("directions_options", JSONObject().apply {
                    put("units", "km")
                    put("language", getValhallaLanguageTag())
                })
            }

            val url = URL("https://valhalla1.openstreetmap.de/route")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "KoveMirror/2.0")
            conn.doOutput = true

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseValhallaJson(responseStr)
                if (parsed != null && parsed.geometryPoints.isNotEmpty()) {
                    DebugLogger.info("✅ Route calculated via Valhalla (avoidTolls=$avoidTolls, avoidHighways=$avoidHighways, useShortest=$useShortest)")
                    return parsed
                }
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ Valhalla routing error: ${e.message}")
        }
        return null
    }

    private fun parseValhallaJson(jsonStr: String): NavigationRoute? {
        val root = JSONObject(jsonStr)
        val trip = root.optJSONObject("trip") ?: return null
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null

        val leg = legs.getJSONObject(0)
        val shapeStr = leg.optString("shape", "")
        val geoPoints = decodePolyline6(shapeStr)
        if (geoPoints.isEmpty()) return null

        val summary = leg.optJSONObject("summary")
        val totalDistanceMeters = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0
        val totalDurationSeconds = summary?.optDouble("time", 0.0) ?: 0.0

        val steps = mutableListOf<RouteStep>()
        val maneuvers = leg.optJSONArray("maneuvers")
        if (maneuvers != null) {
            for (i in 0 until maneuvers.length()) {
                val mObj = maneuvers.getJSONObject(i)
                val instruction = mObj.optString("instruction", "")
                val distMeters = mObj.optDouble("length", 0.0) * 1000.0
                val durSeconds = mObj.optDouble("time", 0.0)
                val beginIdx = mObj.optInt("begin_shape_index", 0)
                val stepLoc = if (beginIdx in geoPoints.indices) geoPoints[beginIdx] else (geoPoints.firstOrNull() ?: GeoPoint(0.0, 0.0))
                val mTypeInt = mObj.optInt("type", 1)

                val (typeStr, modifierStr) = mapValhallaManeuverType(mTypeInt)
                steps.add(
                    RouteStep(
                        instruction = instruction,
                        distanceMeters = distMeters,
                        durationSeconds = durSeconds,
                        location = stepLoc,
                        modifier = modifierStr,
                        type = typeStr,
                        shapeIndex = beginIdx
                    )
                )
            }
        }

        return NavigationRoute(geoPoints, totalDistanceMeters, totalDurationSeconds, steps)
    }

    private fun mapValhallaManeuverType(type: Int): Pair<String, String> {
        return when (type) {
            1 -> Pair("straight", "")
            2 -> Pair("turn", "slight right")
            3 -> Pair("turn", "right")
            4 -> Pair("turn", "sharp right")
            5, 6 -> Pair("u turn", "")
            7 -> Pair("turn", "sharp left")
            8 -> Pair("turn", "left")
            9 -> Pair("turn", "slight left")
            15 -> Pair("turn", "slight left")
            16 -> Pair("turn", "slight right")
            17, 18 -> Pair("roundabout", "")
            22 -> Pair("arrive", "")
            else -> Pair("straight", "")
        }
    }

    private fun decodePolyline6(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(GeoPoint(lat / 1e6, lng / 1e6))
        }
        return poly
    }

    private fun queryOsmEndpoint(
        urlString: String,
        avoidTolls: Boolean,
        avoidHighways: Boolean,
        useShortest: Boolean
    ): NavigationRoute? {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "KoveMirror/2.0")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                return parseOsrmJson(response.toString(), avoidTolls, avoidHighways, useShortest)
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ OSRM endpoint error ($urlString): ${e.message}")
        }
        return null
    }

    private fun parseOsrmJson(
        jsonStr: String,
        avoidTolls: Boolean = false,
        avoidHighways: Boolean = false,
        useShortest: Boolean = false
    ): NavigationRoute? {
        val root = JSONObject(jsonStr)
        val code = root.optString("code")
        if (code != "Ok") return null

        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) return null

        var bestIndex = 0
        if (routes.length() > 1) {
            var minScore = Double.MAX_VALUE
            for (i in 0 until routes.length()) {
                val rObj = routes.getJSONObject(i)
                val dist = rObj.optDouble("distance", 0.0)
                val dur = rObj.optDouble("duration", 0.0)

                var highwayPenalty = 0.0
                if (avoidHighways || avoidTolls) {
                    val legs = rObj.optJSONArray("legs")
                    if (legs != null && legs.length() > 0) {
                        val steps = legs.getJSONObject(0).optJSONArray("steps")
                        if (steps != null) {
                            for (s in 0 until steps.length()) {
                                val step = steps.getJSONObject(s)
                                val name = step.optString("name", "").lowercase()
                                val ref = step.optString("ref", "").lowercase()
                                val mode = step.optString("mode", "").lowercase()
                                val isHighway = mode.contains("motorway") || mode.contains("trunk") ||
                                        name.contains("otoyol") || name.contains("motorway") || name.contains("autobahn") || name.contains("autostrada") ||
                                        ref.startsWith("o-") || ref.matches(Regex("o\\d+")) || ref.startsWith("a-")
                                val isToll = isHighway || name.contains("ücretli") || name.contains("toll") || name.contains("gişe")
                                if (avoidHighways && isHighway) highwayPenalty += step.optDouble("distance", 1000.0) * 1000.0
                                if (avoidTolls && isToll) highwayPenalty += step.optDouble("distance", 1000.0) * 1000.0
                            }
                        }
                    }
                }

                val score = highwayPenalty + (if (useShortest) dist else dur)
                if (score < minScore) {
                    minScore = score
                    bestIndex = i
                }
            }
        }

        val routeObj = routes.getJSONObject(bestIndex)
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

                // Find closest geometry point index for this maneuver
                var shapeIdx = 0
                var minStepDist = Double.MAX_VALUE
                for (g in geoPoints.indices) {
                    val d = locPoint.distanceToAsDouble(geoPoints[g])
                    if (d < minStepDist) {
                        minStepDist = d
                        shapeIdx = g
                    }
                }

                val instruction = buildInstructionText(type, modifier, name)
                steps.add(RouteStep(instruction, dist, dur, locPoint, modifier, type, shapeIdx))
            }
        }

        return NavigationRoute(geoPoints, totalDistance, totalDuration, steps)
    }

    private fun getValhallaLanguageTag(): String {
        val lang = java.util.Locale.getDefault().language.lowercase()
        return when (lang) {
            "tr" -> "tr-TR"
            "el" -> "el-GR"
            "es" -> "es-ES"
            "it" -> "it-IT"
            "fr" -> "fr-FR"
            else -> "en-US"
        }
    }

    private fun buildInstructionText(type: String, modifier: String, streetName: String): String {
        val lang = java.util.Locale.getDefault().language.lowercase()
        val street = if (streetName.isNotEmpty()) " -> $streetName" else ""

        return when (lang) {
            "tr" -> {
                val modText = when (modifier) {
                    "left" -> "sola"
                    "right" -> "sağa"
                    "slight left" -> "hafif sola"
                    "slight right" -> "hafif sağa"
                    "sharp left" -> "keskin sola"
                    "sharp right" -> "keskin sağa"
                    "straight" -> "düz"
                    "uturn" -> "U dönüşü"
                    else -> modifier
                }
                when (type) {
                    "turn" -> "$modText dönün$street"
                    "new name", "straight" -> "Düz devam edin$street"
                    "depart" -> "İlerleme başlatın$street"
                    "arrive" -> "Hedefe ulaştınız"
                    "roundabout", "rotary" -> "Döner kavşağa girin$street"
                    "fork" -> "$modText yol ayrımına girin$street"
                    "off ramp", "on ramp" -> "$modText rampaya girin$street"
                    else -> "$modText$street".trim().capitalizeFirstLetter()
                }
            }
            "el" -> {
                val modText = when (modifier) {
                    "left" -> "αριστερά"
                    "right" -> "δεξιά"
                    "slight left" -> "ελαφρώς αριστερά"
                    "slight right" -> "ελαφρώς δεξιά"
                    "sharp left" -> "απότομα αριστερά"
                    "sharp right" -> "απότομα δεξιά"
                    "straight" -> "ευθεία"
                    "uturn" -> "αναστροφή"
                    else -> modifier
                }
                when (type) {
                    "turn" -> "Στρίψτε $modText$street"
                    "new name", "straight" -> "Συνεχίστε $modText$street"
                    "depart" -> "Ξεκινήστε $modText$street"
                    "arrive" -> "Φτάσατε στον προορισμό"
                    "roundabout", "rotary" -> "Μπείτε στον κυκλικό κόμβο$street"
                    "fork" -> "Πάρτε τη διχάλα $modText$street"
                    "off ramp", "on ramp" -> "Πάρτε τη ράμπα $modText$street"
                    else -> "$modText$street".trim().capitalizeFirstLetter()
                }
            }
            "es" -> {
                val modText = when (modifier) {
                    "left" -> "a la izquierda"
                    "right" -> "a la derecha"
                    "slight left" -> "ligeramente a la izquierda"
                    "slight right" -> "ligeramente a la derecha"
                    "sharp left" -> "marcadamente a la izquierda"
                    "sharp right" -> "marcadamente a la derecha"
                    "straight" -> "recto"
                    "uturn" -> "gire en U"
                    else -> modifier
                }
                when (type) {
                    "turn" -> "Gire $modText$street"
                    "new name", "straight" -> "Continúe $modText$street"
                    "depart" -> "Avance $modText$street"
                    "arrive" -> "Llegó al destino"
                    "roundabout", "rotary" -> "Entre en la rotonda$street"
                    "fork" -> "Tome la bifurcación $modText$street"
                    "off ramp", "on ramp" -> "Tome la rampa $modText$street"
                    else -> "$modText$street".trim().capitalizeFirstLetter()
                }
            }
            "it" -> {
                val modText = when (modifier) {
                    "left" -> "a sinistra"
                    "right" -> "a destra"
                    "slight left" -> "leggermente a sinistra"
                    "slight right" -> "leggermente a destra"
                    "sharp left" -> "a sinistra deciso"
                    "sharp right" -> "a destra deciso"
                    "straight" -> "dritto"
                    "uturn" -> "fai inversione a U"
                    else -> modifier
                }
                when (type) {
                    "turn" -> "Svolta $modText$street"
                    "new name", "straight" -> "Continua $modText$street"
                    "depart" -> "Procedi $modText$street"
                    "arrive" -> "Arrivato a destinazione"
                    "roundabout", "rotary" -> "Entra nella rotatoria$street"
                    "fork" -> "Prendi il bivio $modText$street"
                    "off ramp", "on ramp" -> "Prendi la rampa $modText$street"
                    else -> "$modText$street".trim().capitalizeFirstLetter()
                }
            }
            "fr" -> {
                val modText = when (modifier) {
                    "left" -> "à gauche"
                    "right" -> "à droite"
                    "slight left" -> "légèrement à gauche"
                    "slight right" -> "légèrement à droite"
                    "sharp left" -> "fortement à gauche"
                    "sharp right" -> "fortement à droite"
                    "straight" -> "tout droit"
                    "uturn" -> "faire demi-tour"
                    else -> modifier
                }
                when (type) {
                    "turn" -> "Tournez $modText$street"
                    "new name", "straight" -> "Continuez $modText$street"
                    "depart" -> "Démarrez $modText$street"
                    "arrive" -> "Arrivé à destination"
                    "roundabout", "rotary" -> "Entrez dans le rond-point$street"
                    "fork" -> "Prenez la bifurcation $modText$street"
                    "off ramp", "on ramp" -> "Prenez la bretelle $modText$street"
                    else -> "$modText$street".trim().capitalizeFirstLetter()
                }
            }
            else -> {
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
                when (type) {
                    "turn" -> "Turn $modText$street"
                    "new name", "straight" -> "Continue $modText$street"
                    "depart" -> "Head $modText$street"
                    "arrive" -> "Arrived at destination"
                    "roundabout", "rotary" -> "Enter roundabout$street"
                    "fork" -> "Take $modText fork$street"
                    "off ramp", "on ramp" -> "Take ramp $modText$street"
                    else -> "$modText$street".trim().capitalizeFirstLetter()
                }
            }
        }
    }

    private fun String.capitalizeFirstLetter(): String {
        return if (isNotEmpty()) this[0].uppercaseChar() + substring(1) else this
    }

    /**
     * Calculates the minimum perpendicular distance in meters from a GeoPoint
     * to a polyline represented as a list of GeoPoints.
     */
    fun distanceToPolylineMeters(point: GeoPoint, polyline: List<GeoPoint>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return point.distanceToAsDouble(polyline[0])

        var minDist = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val dist = distanceToSegmentMeters(point, polyline[i], polyline[i + 1])
            if (dist < minDist) {
                minDist = dist
            }
        }
        return minDist
    }

    /**
     * Calculates distance in meters from point P to line segment AB.
     */
    fun distanceToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val cosLat = Math.cos(Math.toRadians(a.latitude))
        val bx = (b.longitude - a.longitude) * 111320.0 * cosLat
        val by = (b.latitude - a.latitude) * 111320.0
        val px = (p.longitude - a.longitude) * 111320.0 * cosLat
        val py = (p.latitude - a.latitude) * 111320.0

        val ab2 = bx * bx + by * by
        if (ab2 == 0.0) {
            return Math.hypot(px, py)
        }

        var t = (px * bx + py * by) / ab2
        t = t.coerceIn(0.0, 1.0)

        val projX = t * bx
        val projY = t * by

        return Math.hypot(px - projX, py - projY)
    }

    /**
     * Calculates remaining distance along the polyline from current location to end of polyline.
     */
    fun calculateRemainingRouteDistanceMeters(currentLocation: GeoPoint, polyline: List<GeoPoint>): Double {
        if (polyline.isEmpty()) return 0.0
        if (polyline.size == 1) return currentLocation.distanceToAsDouble(polyline[0])
        if (currentLocation.latitude == 0.0 && currentLocation.longitude == 0.0) return 0.0

        var minSegIndex = 0
        var minDist = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val dist = distanceToSegmentMeters(currentLocation, polyline[i], polyline[i + 1])
            if (dist < minDist) {
                minDist = dist
                minSegIndex = i
            }
        }

        var remainingMeters = currentLocation.distanceToAsDouble(polyline[minSegIndex + 1])
        for (i in minSegIndex + 1 until polyline.size - 1) {
            remainingMeters += polyline[i].distanceToAsDouble(polyline[i + 1])
        }

        return remainingMeters
    }
}
