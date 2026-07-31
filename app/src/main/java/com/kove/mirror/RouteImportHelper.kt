package com.kove.mirror

import android.content.Context
import android.net.Uri
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses KML, KMZ and GPX route files into lists of GeoPoints.
 */
object RouteImportHelper {

    data class ParsedRoute(
        val name: String,
        val points: List<GeoPoint>
    )

    /**
     * Detects format from URI and parses accordingly.
     * Returns a list of routes (a single file may contain multiple tracks).
     */
    fun parseUri(context: Context, uri: Uri): List<ParsedRoute> {
        val fileName = getFileName(context, uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()

        return when (ext) {
            "gpx" -> context.contentResolver.openInputStream(uri)?.use { parseGpx(it) } ?: emptyList()
            "kml" -> context.contentResolver.openInputStream(uri)?.use { parseKml(it) } ?: emptyList()
            "kmz" -> context.contentResolver.openInputStream(uri)?.use { parseKmz(it) } ?: emptyList()
            else -> {
                // Try to detect from content; fallback to GPX
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val text = String(bytes).trim()
                    when {
                        text.contains("<gpx", ignoreCase = true) -> parseGpx(bytes.inputStream())
                        text.contains("<kml", ignoreCase = true) -> parseKml(bytes.inputStream())
                        else -> emptyList()
                    }
                } ?: emptyList()
            }
        }
    }

    // ─── GPX Parser ─────────────────────────────────────────────

    private fun parseGpx(input: InputStream): List<ParsedRoute> {
        val routes = mutableListOf<ParsedRoute>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        var currentPoints = mutableListOf<GeoPoint>()
        var currentName = ""
        var inTrack = false
        var inRoute = false
        var inName = false
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name?.lowercase()) {
                        "trk" -> { inTrack = true; currentPoints = mutableListOf(); currentName = "" }
                        "rte" -> { inRoute = true; currentPoints = mutableListOf(); currentName = "" }
                        "name" -> { if (inTrack || inRoute) inName = true }
                        "trkpt", "rtept", "wpt" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) {
                                currentPoints.add(GeoPoint(lat, lon))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inName) {
                        currentName = parser.text?.trim() ?: ""
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name?.lowercase()) {
                        "trk" -> {
                            if (currentPoints.isNotEmpty()) {
                                routes.add(ParsedRoute(
                                    name = currentName.ifEmpty { "Track ${routes.size + 1}" },
                                    points = currentPoints.toList()
                                ))
                            }
                            inTrack = false
                        }
                        "rte" -> {
                            if (currentPoints.isNotEmpty()) {
                                routes.add(ParsedRoute(
                                    name = currentName.ifEmpty { "Route ${routes.size + 1}" },
                                    points = currentPoints.toList()
                                ))
                            }
                            inRoute = false
                        }
                        "name" -> inName = false
                    }
                }
            }
            eventType = parser.next()
        }

        // If we got waypoints but no tracks/routes, wrap them
        if (routes.isEmpty() && currentPoints.isNotEmpty()) {
            routes.add(ParsedRoute("Waypoints", currentPoints.toList()))
        }

        return routes
    }

    // ─── KML Parser ─────────────────────────────────────────────

    private fun parseKml(input: InputStream): List<ParsedRoute> {
        val routes = mutableListOf<ParsedRoute>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        var currentName = ""
        var inPlacemark = false
        var inName = false
        var inCoordinates = false
        var coordinatesBuilder = StringBuilder()
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "Placemark" -> { inPlacemark = true; currentName = "" }
                        "name" -> { if (inPlacemark) inName = true }
                        "coordinates" -> { inCoordinates = true; coordinatesBuilder = StringBuilder() }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inName) {
                        currentName = parser.text?.trim() ?: ""
                    }
                    if (inCoordinates) {
                        coordinatesBuilder.append(parser.text ?: "")
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "coordinates" -> {
                            inCoordinates = false
                            val points = parseKmlCoordinates(coordinatesBuilder.toString())
                            if (points.size >= 2) {
                                routes.add(ParsedRoute(
                                    name = currentName.ifEmpty { "Route ${routes.size + 1}" },
                                    points = points
                                ))
                            }
                        }
                        "Placemark" -> inPlacemark = false
                        "name" -> inName = false
                    }
                }
            }
            eventType = parser.next()
        }
        return routes
    }

    private fun parseKmlCoordinates(raw: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return points

        // KML coordinates format: lon,lat,altitude separated by whitespace
        val tuples = trimmed.split(Regex("\\s+"))
        for (tuple in tuples) {
            val parts = tuple.split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) {
                    points.add(GeoPoint(lat, lon))
                }
            }
        }
        return points
    }

    // ─── KMZ Handler ────────────────────────────────────────────

    private fun parseKmz(input: InputStream): List<ParsedRoute> {
        val zipStream = ZipInputStream(input)
        var entry = zipStream.nextEntry
        while (entry != null) {
            if (entry.name.endsWith(".kml", ignoreCase = true)) {
                // Read the KML content without closing the ZipInputStream
                val bytes = zipStream.readBytes()
                zipStream.closeEntry()
                return parseKml(bytes.inputStream())
            }
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        return emptyList()
    }

    // ─── Utility ────────────────────────────────────────────────

    private fun getFileName(context: Context, uri: Uri): String {
        // Try to get display name from content resolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex) ?: "unknown"
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }
}
