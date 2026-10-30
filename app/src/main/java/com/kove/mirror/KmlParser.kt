package com.kove.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object KmlParser {

    fun parseKmz(input: InputStream, context: Context): KmlDocument {
        val cacheDir = File(context.cacheDir, "kmz_assets_${System.currentTimeMillis()}").apply { mkdirs() }
        var kmlBytes: ByteArray? = null

        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        if (name.endsWith(".kml", ignoreCase = true) && kmlBytes == null) {
                            kmlBytes = zip.readBytes()
                        } else {
                            // Extract asset files (icons, images)
                            val outFile = File(cacheDir, name)
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zip.copyTo(fos)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ Error unzipping KMZ: ${e.message}")
        }

        return if (kmlBytes != null) {
            parseKml(kmlBytes!!.inputStream(), cacheDir)
        } else {
            KmlDocument()
        }
    }

    fun parseKml(input: InputStream, assetDir: File? = null): KmlDocument {
        val stylesMap = mutableMapOf<String, KmlStyle>()
        val styleMapAliases = mutableMapOf<String, String>() // StyleMap id -> Style id
        val placemarks = mutableListOf<KmlPlacemark>()
        var docName = ""

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        var eventType = parser.eventType
        var currentTag = ""
        var inDocument = false
        var inStyle = false
        var inIconStyle = false
        var inLineStyle = false
        var inPolyStyle = false
        var currentStyleId = ""
        var currentLineColor: Int? = null
        var currentLineWidth: Float? = null
        var currentIconHref: String? = null
        var currentIconScale = 1.0f
        var currentIconColor: Int? = null
        var currentPolyColor: Int? = null
        var currentFill = true
        var currentOutline = true

        var inStyleMap = false
        var currentStyleMapId = ""
        var styleMapPairKey = ""
        var styleMapPairUrl = ""

        var inPlacemark = false
        var pmName = ""
        var pmDescription = ""
        var pmStyleUrl = ""
        var pmInlineStyle: KmlStyle? = null
        var pmGeometryType = KmlGeometryType.LINESTRING
        var pmPoints = mutableListOf<GeoPoint>()
        var pmPolygons = mutableListOf<List<GeoPoint>>()

        fun resetCurrentStyleVars() {
            currentStyleId = ""
            currentLineColor = null
            currentLineWidth = null
            currentIconHref = null
            currentIconScale = 1.0f
            currentIconColor = null
            currentPolyColor = null
            currentFill = true
            currentOutline = true
        }

        var currentCoordinatesText = StringBuilder()
        var inCoordinates = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name ?: ""

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = tagName
                    when (tagName) {
                        "Document" -> inDocument = true
                        "Style" -> {
                            inStyle = true
                            resetCurrentStyleVars()
                            if (!inPlacemark) {
                                currentStyleId = parser.getAttributeValue(null, "id") ?: ""
                            }
                        }
                        "IconStyle" -> inIconStyle = true
                        "LineStyle" -> inLineStyle = true
                        "PolyStyle" -> inPolyStyle = true
                        "StyleMap" -> {
                            inStyleMap = true
                            currentStyleMapId = parser.getAttributeValue(null, "id") ?: ""
                        }
                        "Placemark" -> {
                            inPlacemark = true
                            pmName = ""
                            pmDescription = ""
                            pmStyleUrl = ""
                            pmInlineStyle = null
                            pmGeometryType = KmlGeometryType.LINESTRING
                            pmPoints = mutableListOf()
                            pmPolygons = mutableListOf()
                            resetCurrentStyleVars()
                        }
                        "Point" -> if (inPlacemark) pmGeometryType = KmlGeometryType.POINT
                        "LineString" -> if (inPlacemark) pmGeometryType = KmlGeometryType.LINESTRING
                        "Polygon" -> if (inPlacemark) pmGeometryType = KmlGeometryType.POLYGON
                        "MultiGeometry" -> if (inPlacemark) pmGeometryType = KmlGeometryType.MULTIGEOMETRY
                        "coordinates" -> {
                            inCoordinates = true
                            currentCoordinatesText = StringBuilder()
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        if (inCoordinates) {
                            currentCoordinatesText.append(text).append(" ")
                        } else if (inStyle) {
                            when (currentTag) {
                                "color" -> {
                                    val c = KmlColorUtils.parseKmlColor(text)
                                    if (inLineStyle) currentLineColor = c
                                    else if (inPolyStyle) currentPolyColor = c
                                    else if (inIconStyle) currentIconColor = c
                                    else {
                                        currentLineColor = currentLineColor ?: c
                                        currentPolyColor = currentPolyColor ?: c
                                    }
                                }
                                "width" -> currentLineWidth = text.toFloatOrNull()
                                "href" -> currentIconHref = text
                                "scale" -> currentIconScale = text.toFloatOrNull() ?: 1.0f
                                "fill" -> currentFill = text == "1" || text.equals("true", ignoreCase = true)
                                "outline" -> currentOutline = text == "1" || text.equals("true", ignoreCase = true)
                            }
                        } else if (inStyleMap) {
                            when (currentTag) {
                                "key" -> styleMapPairKey = text
                                "styleUrl" -> styleMapPairUrl = text.removePrefix("#")
                            }
                        } else if (inPlacemark) {
                            when (currentTag) {
                                "name" -> pmName = text
                                "description" -> pmDescription = text
                                "styleUrl" -> pmStyleUrl = text.removePrefix("#")
                            }
                        } else if (inDocument && currentTag == "name" && docName.isEmpty()) {
                            docName = text
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (tagName) {
                        "IconStyle" -> inIconStyle = false
                        "LineStyle" -> inLineStyle = false
                        "PolyStyle" -> inPolyStyle = false
                        "Style" -> {
                            val builtStyle = KmlStyle(
                                id = currentStyleId,
                                lineColor = currentLineColor,
                                lineWidth = currentLineWidth,
                                iconHref = currentIconHref,
                                iconScale = currentIconScale,
                                iconColor = currentIconColor,
                                polyColor = currentPolyColor,
                                fill = currentFill,
                                outline = currentOutline
                            )
                            if (inPlacemark) {
                                if (currentIconHref != null || currentLineColor != null || currentPolyColor != null || currentLineWidth != null) {
                                    pmInlineStyle = builtStyle
                                }
                            } else if (currentStyleId.isNotEmpty()) {
                                stylesMap[currentStyleId] = builtStyle
                            }
                            resetCurrentStyleVars()
                            inStyle = false
                        }
                        "Pair" -> {
                            if (inStyleMap && (styleMapPairKey == "normal" || styleMapAliases[currentStyleMapId] == null)) {
                                if (currentStyleMapId.isNotEmpty() && styleMapPairUrl.isNotEmpty()) {
                                    styleMapAliases[currentStyleMapId] = styleMapPairUrl
                                }
                            }
                        }
                        "StyleMap" -> inStyleMap = false
                        "coordinates" -> {
                            inCoordinates = false
                            val parsedPts = parseCoordinatesString(currentCoordinatesText.toString())
                            if (parsedPts.isNotEmpty()) {
                                if (pmGeometryType == KmlGeometryType.POLYGON) {
                                    pmPolygons.add(parsedPts)
                                } else {
                                    pmPoints.addAll(parsedPts)
                                }
                            }
                        }
                        "Placemark" -> {
                            // Resolve StyleMap alias if present
                            val resolvedStyleUrl = styleMapAliases[pmStyleUrl] ?: pmStyleUrl
                            val matchedStyle = stylesMap[resolvedStyleUrl] ?: pmInlineStyle

                            val iconHref = matchedStyle?.iconHref
                            val iconBmp = loadIconBitmap(iconHref, assetDir)

                            placemarks.add(
                                KmlPlacemark(
                                    name = pmName,
                                    description = pmDescription,
                                    styleUrl = resolvedStyleUrl,
                                    inlineStyle = matchedStyle,
                                    geometryType = pmGeometryType,
                                    points = pmPoints.toList(),
                                    polygons = pmPolygons.toList(),
                                    iconBitmap = iconBmp
                                )
                            )
                            pmInlineStyle = null
                            resetCurrentStyleVars()
                            inPlacemark = false
                        }
                        "Document" -> inDocument = false
                    }
                }
            }
            eventType = parser.next()
        }

        return KmlDocument(
            name = docName.ifEmpty { "KML Layer" },
            placemarks = placemarks,
            styles = stylesMap
        )
    }

    private fun parseCoordinatesString(raw: String): List<GeoPoint> {
        val list = mutableListOf<GeoPoint>()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return list

        val tuples = trimmed.split(Regex("\\s+"))
        for (tuple in tuples) {
            val parts = tuple.split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) {
                    list.add(GeoPoint(lat, lon))
                }
            }
        }
        return list
    }

    private fun loadIconBitmap(href: String?, assetDir: File?): Bitmap? {
        if (href.isNullOrBlank()) return null

        val cleanHref = href.replace('\\', '/').trim()
        val baseName = cleanHref.substringAfterLast('/')

        try {
            // 1. Try loading from KMZ unzipped assetDir with multiple fallbacks
            if (assetDir != null && assetDir.exists()) {
                val candidates = arrayOf(
                    File(assetDir, cleanHref),
                    File(assetDir, baseName),
                    File(assetDir, "images/$baseName"),
                    File(assetDir, "files/$baseName"),
                    File(assetDir, "doc.kml_files/$baseName")
                )
                for (file in candidates) {
                    if (file.exists()) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) return bmp
                    }
                }
            }

            // 2. Try web URL icon (HTTP/HTTPS) with caching, User-Agent, and redirect handling
            if (cleanHref.startsWith("http://", ignoreCase = true) || cleanHref.startsWith("https://", ignoreCase = true)) {
                val cacheDir = if (assetDir != null) File(assetDir.parentFile, "kml_icon_cache") else null
                cacheDir?.mkdirs()
                val safeFileName = cleanHref.hashCode().toString() + "_" + baseName.take(30)
                val cachedFile = if (cacheDir != null) File(cacheDir, safeFileName) else null

                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    if (bmp != null) return bmp
                }

                // Automatic http -> https upgrade for Google Maps and known icon domains
                var targetUrlStr = if (cleanHref.startsWith("http://maps.google.com", ignoreCase = true)) {
                    cleanHref.replace("http://maps.google.com", "https://maps.google.com")
                } else if (cleanHref.startsWith("http://", ignoreCase = true)) {
                    cleanHref.replaceFirst("http://", "https://")
                } else cleanHref

                var conn: HttpURLConnection? = null
                var redirects = 0
                while (redirects < 3) {
                    val url = URL(targetUrlStr)
                    conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    conn.instanceFollowRedirects = true

                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                        val loc = conn.getHeaderField("Location")
                        if (!loc.isNullOrBlank()) {
                            targetUrlStr = loc
                            redirects++
                            conn.disconnect()
                            continue
                        }
                    }

                    if (code == HttpURLConnection.HTTP_OK) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.isNotEmpty()) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                if (cachedFile != null) {
                                    try {
                                        cachedFile.writeBytes(bytes)
                                    } catch (_: Exception) {}
                                }
                                return bmp
                            }
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to load KML icon ($href): ${e.message}")
        }

        return null
    }
}
