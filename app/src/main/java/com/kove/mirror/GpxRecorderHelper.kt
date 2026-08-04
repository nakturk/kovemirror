package com.kove.mirror

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val speed: Float,
    val timeMs: Long
)

object GpxRecorderHelper {

    private const val TEMP_FILE_NAME = "temp_recording.json"

    fun saveTempPoints(context: Context, color: Int, points: List<TrackPoint>) {
        try {
            val jsonArray = JSONArray()
            for (p in points) {
                val obj = JSONObject().apply {
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("ele", p.ele)
                    put("speed", p.speed)
                    put("time", p.timeMs)
                }
                jsonArray.put(obj)
            }
            val root = JSONObject().apply {
                put("color", color)
                put("points", jsonArray)
            }
            val file = File(context.getExternalFilesDir(null), TEMP_FILE_NAME)
            file.writeText(root.toString())
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to save temp track recording: ${e.message}")
        }
    }

    fun loadTempPoints(context: Context): Pair<Int, List<TrackPoint>>? {
        val file = File(context.getExternalFilesDir(null), TEMP_FILE_NAME)
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val color = root.optInt("color", android.graphics.Color.RED)
            val jsonArray = root.getJSONArray("points")
            val list = mutableListOf<TrackPoint>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    TrackPoint(
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        ele = obj.optDouble("ele", 0.0),
                        speed = obj.optDouble("speed", 0.0).toFloat(),
                        timeMs = obj.optLong("time", System.currentTimeMillis())
                    )
                )
            }
            Pair(color, list)
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to load temp track recording: ${e.message}")
            null
        }
    }

    fun clearTempPoints(context: Context) {
        try {
            val file = File(context.getExternalFilesDir(null), TEMP_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }

    fun generateGpxString(trackName: String, points: List<TrackPoint>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"KoveMirror\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")

        for (p in points) {
            val timeStr = sdf.format(Date(p.timeMs))
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n", p.lat, p.lon))
            sb.append(String.format(Locale.US, "        <ele>%.1f</ele>\n", p.ele))
            sb.append("        <time>").append(timeStr).append("</time>\n")
            if (p.speed > 0f) {
                sb.append(String.format(Locale.US, "        <speed>%.2f</speed>\n", p.speed))
            }
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>")

        return sb.toString()
    }

    fun saveGpxToDownloads(context: Context, fileName: String, gpxContent: String): Uri? {
        val cleanName = if (fileName.endsWith(".gpx", ignoreCase = true)) fileName else "$fileName.gpx"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(gpxContent.toByteArray(Charsets.UTF_8))
                    }
                }
                uri
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val destFile = File(downloadsDir, cleanName)
                destFile.writeText(gpxContent, Charsets.UTF_8)
                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to save GPX file: ${e.message}")
            null
        }
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
