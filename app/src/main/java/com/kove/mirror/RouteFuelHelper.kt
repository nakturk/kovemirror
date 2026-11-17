package com.kove.mirror

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.osmdroid.util.GeoPoint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * Helper to find, filter, and render fuel/gas stations along an active navigation
 * or loaded GPX/KML route within a user-configurable corridor width,
 * reading 100% offline from an OpenAndroMaps / Mapsforge `.poi` SQLite file.
 */
object RouteFuelHelper {

    private val executor = Executors.newSingleThreadExecutor()

    val CORRIDOR_OPTIONS_METERS = listOf(500, 1000, 2000, 5000, 10000, 50000)
    const val DEFAULT_CORRIDOR_METERS = 1000

    data class FuelStation(
        val id: String,
        val name: String,
        val brand: String,
        val operator: String = "",
        val openingHours: String = "",
        val point: GeoPoint,
        val distanceAlongRouteKm: Double,
        val offRouteMeters: Double
    ) {
        val displayName: String
            get() = when {
                brand.isNotBlank() && name.isNotBlank() && !name.contains(brand, ignoreCase = true) -> "$brand ($name)"
                brand.isNotBlank() -> brand
                name.isNotBlank() -> name
                operator.isNotBlank() -> operator
                else -> "Fuel Station"
            }

        val badgeLabel: String
            get() {
                val raw = when {
                    brand.isNotBlank() -> brand
                    name.isNotBlank() -> name
                    operator.isNotBlank() -> operator
                    else -> "Fuel"
                }.trim()
                return if (raw.length > 12) raw.take(11).trimEnd() + "…" else raw
            }
    }

    fun formatCorridorDistance(meters: Int): String {
        return if (meters < 1000) {
            "${meters} m"
        } else {
            "${meters / 1000} km"
        }
    }

    /**
     * Resolves the offline OpenAndroMaps `.poi` file:
     * 1. Checks saved `offline_poi_path` in `kove_map_prefs`.
     * 2. Checks for a sibling `.poi` file matching `offline_map_path` (e.g. `Turkey.map` -> `Turkey.poi`).
     * 3. Scans the app's `maps/` directory for any `.poi` file.
     */
    fun findOfflinePoiFile(context: Context): File? {
        val prefs = context.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        val savedPoiPath = prefs.getString("offline_poi_path", null)
        if (!savedPoiPath.isNullOrEmpty()) {
            val f = File(savedPoiPath)
            if (f.exists() && f.isFile && f.length() > 0L) {
                return f
            }
        }

        val savedMapPath = prefs.getString("offline_map_path", null)
        if (!savedMapPath.isNullOrEmpty()) {
            val mapFile = File(savedMapPath)
            val baseName = mapFile.nameWithoutExtension
            val parent = mapFile.parentFile
            if (parent != null && parent.exists()) {
                val sibling = File(parent, "$baseName.poi")
                if (sibling.exists() && sibling.isFile && sibling.length() > 0L) {
                    prefs.edit().putString("offline_poi_path", sibling.absolutePath).apply()
                    return sibling
                }
            }
        }

        val mapsDir = File(context.getExternalFilesDir(null), "maps")
        if (mapsDir.exists()) {
            val anyPoi = mapsDir.listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("poi", ignoreCase = true) && it.length() > 0L
            }
            if (anyPoi != null) {
                prefs.edit().putString("offline_poi_path", anyPoi.absolutePath).apply()
                return anyPoi
            }
        }

        return null
    }

    /**
     * Extracts the upcoming portion of the route ahead of [currentLocation] (if near the route).
     */
    fun extractAheadRoutePoints(
        routePoints: List<GeoPoint>,
        currentLocation: GeoPoint?,
        corridorMeters: Int
    ): List<GeoPoint> {
        if (routePoints.size < 2 || currentLocation == null) return routePoints
        if (currentLocation.latitude == 0.0 && currentLocation.longitude == 0.0) return routePoints

        var nearestSegIdx = 0
        var minSegDist = Double.MAX_VALUE
        for (i in 0 until routePoints.size - 1) {
            val d = NavigationHelper.distanceToSegmentMeters(currentLocation, routePoints[i], routePoints[i + 1])
            if (d < minSegDist) {
                minSegDist = d
                nearestSegIdx = i
            }
        }

        val snapThreshold = maxOf(corridorMeters.toDouble(), 5000.0)
        return if (minSegDist <= snapThreshold && nearestSegIdx < routePoints.size - 1) {
            val remaining = ArrayList<GeoPoint>(routePoints.size - nearestSegIdx)
            remaining.add(currentLocation)
            for (i in (nearestSegIdx + 1) until routePoints.size) {
                remaining.add(routePoints[i])
            }
            remaining
        } else {
            routePoints
        }
    }

    private data class BBox(val south: Double, val west: Double, val north: Double, val east: Double)

    private fun buildRouteBoundingBoxes(polylines: List<List<GeoPoint>>, corridorMeters: Int): List<BBox> {
        val boxes = mutableListOf<BBox>()
        var totalRouteMeters = 0.0
        for (poly in polylines) {
            for (i in 0 until poly.size - 1) {
                totalRouteMeters += poly[i].distanceToAsDouble(poly[i + 1])
            }
        }
        // Build tight ~15-20 km chunks (capped at 24 chunks total) so SQLite bounding boxes hug the route
        val targetChunkMeters = maxOf(15_000.0, totalRouteMeters / 24.0)

        for (points in polylines) {
            if (points.size < 2) continue
            var startIdx = 0
            var accMeters = 0.0
            for (i in 0 until points.size - 1) {
                accMeters += points[i].distanceToAsDouble(points[i + 1])
                val isLastSeg = (i == points.size - 2)
                if (accMeters >= targetChunkMeters || isLastSeg) {
                    val sub = points.subList(startIdx, i + 2)
                    var minLat = Double.MAX_VALUE
                    var maxLat = -Double.MAX_VALUE
                    var minLon = Double.MAX_VALUE
                    var maxLon = -Double.MAX_VALUE
                    for (p in sub) {
                        if (p.latitude < minLat) minLat = p.latitude
                        if (p.latitude > maxLat) maxLat = p.latitude
                        if (p.longitude < minLon) minLon = p.longitude
                        if (p.longitude > maxLon) maxLon = p.longitude
                    }
                    val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
                    val cosLat = cos(midLatRad).coerceAtLeast(0.2)
                    val latPad = corridorMeters / 111_320.0
                    val lonPad = corridorMeters / (111_320.0 * cosLat)

                    boxes.add(
                        BBox(
                            south = (minLat - latPad).coerceIn(-85.0, 85.0),
                            west = (minLon - lonPad).coerceIn(-180.0, 180.0),
                            north = (maxLat + latPad).coerceIn(-85.0, 85.0),
                            east = (maxLon + lonPad).coerceIn(-180.0, 180.0)
                        )
                    )
                    startIdx = i + 1
                    accMeters = 0.0
                }
            }
        }
        return boxes
    }

    /**
     * Queries the local OpenAndroMaps `.poi` SQLite database for fuel stations along [routePoints]
     * within [corridorMeters].
     */
    fun fetchFuelStationsAlongRoute(
        context: Context,
        routePoints: List<GeoPoint>,
        currentLocation: GeoPoint?,
        corridorMeters: Int,
        callback: (List<FuelStation>, Boolean) -> Unit
    ) {
        fetchFuelStationsAlongRoutes(context, listOf(routePoints), currentLocation, corridorMeters, callback)
    }

    /**
     * Queries the local OpenAndroMaps `.poi` SQLite database for fuel stations along one or more
     * [routePolylines] within [corridorMeters].
     * When [currentLocation] is non-null (active navigation), trims the route ahead of the rider.
     * When [currentLocation] is null (loaded GPX/KML view), scans the full polylines start to end.
     * @param callback receives `(stations, hasPoiFile)`.
     */
    fun fetchFuelStationsAlongRoutes(
        context: Context,
        routePolylines: List<List<GeoPoint>>,
        currentLocation: GeoPoint?,
        corridorMeters: Int,
        callback: (List<FuelStation>, Boolean) -> Unit
    ) {
        val appContext = context.applicationContext
        val poiFile = findOfflinePoiFile(appContext)
        if (poiFile == null) {
            DebugLogger.warning("⚠️ [RouteFuel] Offline .poi file not configured")
            callback(emptyList(), false)
            return
        }

        val validPolylines = routePolylines.filter { it.size >= 2 }
        if (validPolylines.isEmpty()) {
            callback(emptyList(), true)
            return
        }

        executor.execute {
            var db: SQLiteDatabase? = null
            try {
                val radius = corridorMeters.coerceIn(250, 50_000)
                val targetPolylines = if (currentLocation != null) {
                    validPolylines.map { extractAheadRoutePoints(it, currentLocation, radius) }.filter { it.size >= 2 }
                } else {
                    validPolylines
                }
                if (targetPolylines.isEmpty()) {
                    callback(emptyList(), true)
                    return@execute
                }

                db = SQLiteDatabase.openDatabase(
                    poiFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )

                val stations = queryFuelStationsFromPoiDb(db, targetPolylines, radius)
                DebugLogger.success("⛽ [RouteFuel] Loaded ${stations.size} fuel station(s) from offline POI (${poiFile.name}, corridor=${formatCorridorDistance(radius)})")
                callback(stations, true)
            } catch (e: Exception) {
                DebugLogger.error("❌ [RouteFuel] Offline POI query error: ${e.message}")
                callback(emptyList(), true)
            } finally {
                try { db?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun getExistingTables(db: SQLiteDatabase): Set<String> {
        val tables = HashSet<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) {
                tables.add(c.getString(0).lowercase(Locale.US))
            }
        }
        return tables
    }

    private fun getTableColumns(db: SQLiteDatabase, tableName: String): Set<String> {
        val cols = HashSet<String>()
        try {
            db.rawQuery("PRAGMA table_info($tableName)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (nameIdx != -1) cols.add(c.getString(nameIdx).lowercase(Locale.US))
                }
            }
        } catch (_: Exception) {}
        return cols
    }

    private fun findFuelCategoryIds(db: SQLiteDatabase, tables: Set<String>): List<Int> {
        if ("poi_categories" !in tables) return emptyList()
        val matchedIds = HashSet<Int>()
        try {
            db.rawQuery(
                "SELECT id FROM poi_categories WHERE lower(name) LIKE '%fuel%' OR lower(name) LIKE '%petrol%' OR lower(name) LIKE '%gas_station%' OR lower(name) LIKE '%benzin%'",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    matchedIds.add(c.getInt(0))
                }
            }
            if (matchedIds.isNotEmpty()) {
                val parentList = matchedIds.joinToString(",")
                db.rawQuery("SELECT id FROM poi_categories WHERE parent IN ($parentList)", null).use { c ->
                    while (c.moveToNext()) {
                        matchedIds.add(c.getInt(0))
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.warning("⚠️ [RouteFuel] Could not read poi_categories: ${e.message}")
        }
        return matchedIds.toList()
    }

    private data class RawPoiCandidate(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val data: String
    )

    private const val MAX_DISPLAYED_STATIONS = 300

    private fun queryFuelStationsFromPoiDb(
        db: SQLiteDatabase,
        polylines: List<List<GeoPoint>>,
        corridorMeters: Int
    ): List<FuelStation> {
        val tables = getExistingTables(db)
        val fuelCatIds = findFuelCategoryIds(db, tables)
        val boxes = buildRouteBoundingBoxes(polylines, corridorMeters)

        val globalSouth = boxes.minOf { it.south }
        val globalNorth = boxes.maxOf { it.north }
        val globalWest = boxes.minOf { it.west }
        val globalEast = boxes.maxOf { it.east }

        val candidates = LinkedHashMap<Long, RawPoiCandidate>()

        val hasCategoryMap = "poi_category_map" in tables
        val poiDataCols = getTableColumns(db, "poi_data")
        val hasCategoryColInData = "category" in poiDataCols

        val catFilterClause = when {
            fuelCatIds.isNotEmpty() && hasCategoryMap -> {
                val ids = fuelCatIds.joinToString(",")
                "(poi_index.id IN (SELECT id FROM poi_category_map WHERE category IN ($ids)) OR lower(poi_data.data) LIKE '%fuel%')"
            }
            fuelCatIds.isNotEmpty() && hasCategoryColInData -> {
                val ids = fuelCatIds.joinToString(",")
                "(poi_data.category IN ($ids) OR lower(poi_data.data) LIKE '%fuel%')"
            }
            else -> {
                "(lower(poi_data.data) LIKE '%fuel%' OR lower(poi_data.data) LIKE '%petrol%' OR lower(poi_data.data) LIKE '%benzin%')"
            }
        }

        var rtreeQuerySucceeded = false
        if ("poi_index" in tables) {
            try {
                val indexCols = getTableColumns(db, "poi_index")
                val hasPointCols = "lat" in indexCols && "lon" in indexCols

                val bboxClauses = if (hasPointCols) {
                    boxes.joinToString(" OR ") { b ->
                        String.format(
                            Locale.US,
                            "(poi_index.lat BETWEEN %.6f AND %.6f AND poi_index.lon BETWEEN %.6f AND %.6f)",
                            b.south, b.north, b.west, b.east
                        )
                    }
                } else {
                    boxes.joinToString(" OR ") { b ->
                        String.format(
                            Locale.US,
                            "(poi_index.minLat <= %.6f AND poi_index.maxLat >= %.6f AND poi_index.minLon <= %.6f AND poi_index.maxLon >= %.6f)",
                            b.north, b.south, b.east, b.west
                        )
                    }
                }

                val selectCoords = if (hasPointCols) {
                    "poi_index.lat, poi_index.lon"
                } else {
                    "poi_index.minLat, poi_index.minLon"
                }

                val sql = "SELECT poi_index.id, $selectCoords, poi_data.data " +
                        "FROM poi_index " +
                        "JOIN poi_data ON poi_index.id = poi_data.id " +
                        "WHERE ($bboxClauses) AND $catFilterClause"

                db.rawQuery(sql, null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        var lat = c.getDouble(1)
                        var lon = c.getDouble(2)
                        if (abs(lat) > 90.0 || abs(lon) > 180.0) {
                            lat /= 1_000_000.0
                            lon /= 1_000_000.0
                        }
                        val data = c.getString(3) ?: ""
                        candidates[id] = RawPoiCandidate(id, lat, lon, data)
                    }
                }
                rtreeQuerySucceeded = true
            } catch (e: Exception) {
                DebugLogger.warning("⚠️ [RouteFuel] R*Tree direct query failed (${e.message}), trying shadow node table fallback...")
            }
        }

        if (!rtreeQuerySucceeded && "poi_index_node" in tables) {
            try {
                readRtreeShadowNodes(db, globalSouth, globalNorth, globalWest, globalEast, fuelCatIds, hasCategoryMap, hasCategoryColInData, candidates)
            } catch (e: Exception) {
                DebugLogger.error("❌ [RouteFuel] R*Tree shadow fallback failed: ${e.message}")
            }
        }

        val polyCumDists = ArrayList<DoubleArray>(polylines.size)
        val polyBaseOffsets = DoubleArray(polylines.size)
        var runningOffset = 0.0
        for ((pIdx, poly) in polylines.withIndex()) {
            polyBaseOffsets[pIdx] = runningOffset
            val cum = DoubleArray(poly.size)
            var polyTotal = 0.0
            for (i in 0 until poly.size - 1) {
                polyTotal += poly[i].distanceToAsDouble(poly[i + 1])
                cum[i + 1] = polyTotal
            }
            polyCumDists.add(cum)
            runningOffset += polyTotal
        }

        val matchedStations = mutableListOf<FuelStation>()
        for (cand in candidates.values) {
            val pt = GeoPoint(cand.lat, cand.lon)

            var minOffRouteMeters = Double.MAX_VALUE
            var bestPolyIdx = 0
            var bestSegIdx = 0
            for ((pIdx, poly) in polylines.withIndex()) {
                for (s in 0 until poly.size - 1) {
                    val d = NavigationHelper.distanceToSegmentMeters(pt, poly[s], poly[s + 1])
                    if (d < minOffRouteMeters) {
                        minOffRouteMeters = d
                        bestPolyIdx = pIdx
                        bestSegIdx = s
                    }
                }
            }

            if (minOffRouteMeters > corridorMeters) continue

            val bestPoly = polylines[bestPolyIdx]
            val distFromSegStart = bestPoly[bestSegIdx].distanceToAsDouble(pt)
            val alongRouteMeters = polyBaseOffsets[bestPolyIdx] + polyCumDists[bestPolyIdx][bestSegIdx] + distFromSegStart
            val distanceAlongKm = (alongRouteMeters / 1000.0).coerceAtLeast(0.0)

            val parsedTags = parsePoiDataString(cand.data)
            matchedStations.add(
                FuelStation(
                    id = "poi_${cand.id}",
                    name = parsedTags.name,
                    brand = parsedTags.brand,
                    operator = parsedTags.operator,
                    openingHours = parsedTags.openingHours,
                    point = pt,
                    distanceAlongRouteKm = distanceAlongKm,
                    offRouteMeters = minOffRouteMeters
                )
            )
        }

        // Deduplicate nearby entries (< 55 m), keeping the one closest to the route
        val byProximity = matchedStations.sortedBy { it.offRouteMeters }
        val deduplicated = mutableListOf<FuelStation>()
        for (station in byProximity) {
            if (deduplicated.none { it.point.distanceToAsDouble(station.point) < 55.0 }) {
                deduplicated.add(station)
            }
        }

        if (deduplicated.size <= MAX_DISPLAYED_STATIONS) {
            return deduplicated.sortedBy { it.distanceAlongRouteKm }
        }

        // Distribute stations evenly across the entire route length while prioritizing lowest offRouteMeters
        val maxDistKm = deduplicated.maxOf { it.distanceAlongRouteKm }.coerceAtLeast(1.0)
        val bucketCount = 60
        val buckets = Array(bucketCount) { mutableListOf<FuelStation>() }
        for (station in deduplicated) {
            val bucketIdx = ((station.distanceAlongRouteKm / maxDistKm) * (bucketCount - 1))
                .toInt()
                .coerceIn(0, bucketCount - 1)
            buckets[bucketIdx].add(station)
        }
        // Each bucket is already sorted by offRouteMeters ascending because deduplicated came from byProximity

        val selected = ArrayList<FuelStation>(MAX_DISPLAYED_STATIONS)
        var rank = 0
        while (selected.size < MAX_DISPLAYED_STATIONS) {
            var pickedAny = false
            for (b in buckets) {
                if (rank < b.size) {
                    selected.add(b[rank])
                    pickedAny = true
                    if (selected.size >= MAX_DISPLAYED_STATIONS) break
                }
            }
            if (!pickedAny) break
            rank++
        }

        return selected.sortedBy { it.distanceAlongRouteKm }
    }

    /**
     * Fallback reader for SQLite R*Tree shadow table `poi_index_node` when SQLite is compiled without `ENABLE_RTREE`.
     */
    private fun readRtreeShadowNodes(
        db: SQLiteDatabase,
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        fuelCatIds: List<Int>,
        hasCategoryMap: Boolean,
        hasCategoryColInData: Boolean,
        outCandidates: MutableMap<Long, RawPoiCandidate>
    ) {
        val fuelPoiIds = HashSet<Long>()
        if (fuelCatIds.isNotEmpty() && hasCategoryMap) {
            val ids = fuelCatIds.joinToString(",")
            db.rawQuery("SELECT id FROM poi_category_map WHERE category IN ($ids)", null).use { c ->
                while (c.moveToNext()) fuelPoiIds.add(c.getLong(0))
            }
        } else if (fuelCatIds.isNotEmpty() && hasCategoryColInData) {
            val ids = fuelCatIds.joinToString(",")
            db.rawQuery("SELECT id FROM poi_data WHERE category IN ($ids)", null).use { c ->
                while (c.moveToNext()) fuelPoiIds.add(c.getLong(0))
            }
        }

        val matchedCoords = HashMap<Long, Pair<Double, Double>>()
        db.rawQuery("SELECT nodeno, data FROM poi_index_node", null).use { c ->
            var isRootDepthZero = false
            while (c.moveToNext()) {
                val nodeno = c.getInt(0)
                val blob = c.getBlob(1) ?: continue
                if (blob.size < 4) continue
                val buf = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
                val depthOrZero = buf.short.toInt() and 0xFFFF
                val count = buf.short.toInt() and 0xFFFF
                if (nodeno == 1 && depthOrZero == 0) isRootDepthZero = true
                if (nodeno == 1 && !isRootDepthZero) continue

                val maxCells = min(count, (blob.size - 4) / 24)
                for (i in 0 until maxCells) {
                    val id = buf.long
                    var minLat = buf.float.toDouble()
                    val maxLat = buf.float.toDouble()
                    var minLon = buf.float.toDouble()
                    val maxLon = buf.float.toDouble()
                    if (abs(minLat) > 90.0 || abs(minLon) > 180.0) {
                        minLat /= 1_000_000.0
                        minLon /= 1_000_000.0
                    }
                    if (minLat in south..north && minLon in west..east) {
                        if (fuelPoiIds.isEmpty() || id in fuelPoiIds) {
                            matchedCoords[id] = Pair(minLat, minLon)
                        }
                    }
                }
            }
        }

        if (matchedCoords.isEmpty()) return
        for (chunk in matchedCoords.keys.chunked(200)) {
            val idList = chunk.joinToString(",")
            db.rawQuery("SELECT id, data FROM poi_data WHERE id IN ($idList)", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val data = c.getString(1) ?: ""
                    if (fuelPoiIds.isNotEmpty() || data.contains("fuel", ignoreCase = true) ||
                        data.contains("petrol", ignoreCase = true) || data.contains("benzin", ignoreCase = true)
                    ) {
                        val coord = matchedCoords[id] ?: continue
                        outCandidates[id] = RawPoiCandidate(id, coord.first, coord.second, data)
                    }
                }
            }
        }
    }

    private data class ParsedPoiTags(
        val name: String,
        val brand: String,
        val operator: String,
        val openingHours: String
    )

    private fun parsePoiDataString(raw: String): ParsedPoiTags {
        var name = ""
        var brand = ""
        var operator = ""
        var openingHours = ""

        val lines = raw.split('\r', '\n')
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim().lowercase(Locale.US)
                val value = trimmed.substring(eqIdx + 1).trim()
                when (key) {
                    "name", "name:tr", "name:en" -> if (name.isEmpty()) name = value
                    "brand", "brand:en", "brand:tr" -> if (brand.isEmpty()) brand = value
                    "operator" -> if (operator.isEmpty()) operator = value
                    "opening_hours" -> if (openingHours.isEmpty()) openingHours = value
                }
            } else if (name.isEmpty() && !trimmed.all { it.isDigit() }) {
                name = trimmed
            }
        }
        return ParsedPoiTags(name, brand, operator, openingHours)
    }

    /**
     * Creates a high-contrast pill badge bitmap ("⛽ Shell 4.2km") for 2D, 3D, and TFT displays.
     */
    fun createFuelBadgeBitmap(
        context: Context,
        station: FuelStation,
        scaleFactor: Float = 1.0f
    ): Bitmap {
        val density = context.resources.displayMetrics.density * scaleFactor

        val emojiText = "⛽"
        val brandText = station.badgeLabel
        val distText = if (station.distanceAlongRouteKm < 1.0) {
            "${(station.distanceAlongRouteKm * 1000).toInt()}m"
        } else {
            String.format(Locale.US, "%.1fkm", station.distanceAlongRouteKm)
        }

        val paintEmoji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * density
        }

        val paintBrand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val paintDist = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FDE047")
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val emojiWidth = paintEmoji.measureText(emojiText)
        val brandWidth = paintBrand.measureText(brandText)
        val distWidth = paintDist.measureText(distText)

        val paddingH = 8f * density
        val spacing = 4f * density

        val totalWidth = (paddingH * 2 + emojiWidth + spacing + brandWidth + spacing * 1.2f + distWidth).toInt().coerceAtLeast(1)
        val totalHeight = (30f * density).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#065F46")
            style = Paint.Style.FILL
            alpha = 242
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            alpha = 220
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

        val baseline = (totalHeight / 2f) - ((paintBrand.descent() + paintBrand.ascent()) / 2f)
        var curX = paddingH

        canvas.drawText(emojiText, curX, baseline - 0.5f * density, paintEmoji)
        curX += emojiWidth + spacing

        canvas.drawText(brandText, curX, baseline, paintBrand)
        curX += brandWidth + spacing * 1.2f

        canvas.drawText(distText, curX, baseline, paintDist)

        return bitmap
    }
}
