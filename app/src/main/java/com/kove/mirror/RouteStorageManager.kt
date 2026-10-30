package com.kove.mirror

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedPointDto(
    val lat: Double,
    val lon: Double,
    val alt: Double = 0.0
)

data class SavedPlacemarkDto(
    val name: String = "",
    val description: String = "",
    val styleUrl: String = "",
    val geometryType: String = "LINESTRING",
    val points: List<SavedPointDto> = emptyList(),
    val polygons: List<List<SavedPointDto>> = emptyList(),
    val lineColor: Int? = null,
    val lineWidth: Float? = null,
    val polyColor: Int? = null
)

data class SavedRouteDto(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val groupName: String = name,
    val points: List<SavedPointDto>,
    val color: Int,
    val width: Float,
    val visible: Boolean = true,
    val showDirectionArrows: Boolean = true,
    val arrowColor: Int = color,
    val showDistanceMarkers: Boolean = true,
    val distanceIntervalKm: Int = 5,
    val placemarks: List<SavedPlacemarkDto> = emptyList()
)

object RouteStorageManager {

    private const val FILE_NAME = "saved_routes.json"
    private const val PLACEMARKS_FILE_NAME = "saved_placemarks.json"

    fun loadRoutes(context: Context): MutableList<SavedRouteDto> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return mutableListOf()

        return try {
            val text = file.readText()
            val array = JSONArray(text)
            val list = mutableListOf<SavedRouteDto>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(parseRouteDto(obj))
            }
            list
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to load saved routes: ${e.message}")
            mutableListOf()
        }
    }

    fun saveRoutes(context: Context, routes: List<SavedRouteDto>) {
        try {
            val array = JSONArray()
            for (route in routes) {
                array.put(serializeRouteDto(route))
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to save routes to storage: ${e.message}")
        }
    }

    fun loadPlacemarks(context: Context): MutableList<SavedPlacemarkDto> {
        val file = File(context.filesDir, PLACEMARKS_FILE_NAME)
        if (!file.exists()) return mutableListOf()

        return try {
            val text = file.readText()
            val array = JSONArray(text)
            parsePlacemarksArray(array)
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to load saved placemarks: ${e.message}")
            mutableListOf()
        }
    }

    fun savePlacemarks(context: Context, placemarks: List<SavedPlacemarkDto>) {
        try {
            val array = serializePlacemarksArray(placemarks)
            val file = File(context.filesDir, PLACEMARKS_FILE_NAME)
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            DebugLogger.error("❌ Failed to save placemarks to storage: ${e.message}")
        }
    }

    private fun parsePlacemarksArray(pmArr: JSONArray?): MutableList<SavedPlacemarkDto> {
        val placemarks = mutableListOf<SavedPlacemarkDto>()
        if (pmArr == null) return placemarks
        for (i in 0 until pmArr.length()) {
            val pmObj = pmArr.getJSONObject(i)
            val pmPoints = mutableListOf<SavedPointDto>()
            val pmPtsArr = pmObj.optJSONArray("points")
            if (pmPtsArr != null) {
                for (j in 0 until pmPtsArr.length()) {
                    val pt = pmPtsArr.getJSONObject(j)
                    pmPoints.add(
                        SavedPointDto(
                            lat = pt.getDouble("lat"),
                            lon = pt.getDouble("lon"),
                            alt = pt.optDouble("alt", 0.0)
                        )
                    )
                }
            }

            val polygons = mutableListOf<List<SavedPointDto>>()
            val polyArr = pmObj.optJSONArray("polygons")
            if (polyArr != null) {
                for (j in 0 until polyArr.length()) {
                    val ringArr = polyArr.getJSONArray(j)
                    val ring = mutableListOf<SavedPointDto>()
                    for (k in 0 until ringArr.length()) {
                        val pt = ringArr.getJSONObject(k)
                        ring.add(
                            SavedPointDto(
                                lat = pt.getDouble("lat"),
                                lon = pt.getDouble("lon"),
                                alt = pt.optDouble("alt", 0.0)
                            )
                        )
                    }
                    polygons.add(ring)
                }
            }

            placemarks.add(
                SavedPlacemarkDto(
                    name = pmObj.optString("name", ""),
                    description = pmObj.optString("description", ""),
                    styleUrl = pmObj.optString("styleUrl", ""),
                    geometryType = pmObj.optString("geometryType", "LINESTRING"),
                    points = pmPoints,
                    polygons = polygons,
                    lineColor = if (pmObj.has("lineColor")) pmObj.getInt("lineColor") else null,
                    lineWidth = if (pmObj.has("lineWidth")) pmObj.getDouble("lineWidth").toFloat() else null,
                    polyColor = if (pmObj.has("polyColor")) pmObj.getInt("polyColor") else null
                )
            )
        }
        return placemarks
    }

    private fun serializePlacemarksArray(placemarks: List<SavedPlacemarkDto>): JSONArray {
        val pmArr = JSONArray()
        for (pm in placemarks) {
            pmArr.put(JSONObject().apply {
                put("name", pm.name)
                put("description", pm.description)
                put("styleUrl", pm.styleUrl)
                put("geometryType", pm.geometryType)

                val pmPts = JSONArray()
                for (p in pm.points) {
                    pmPts.put(JSONObject().apply {
                        put("lat", p.lat)
                        put("lon", p.lon)
                        put("alt", p.alt)
                    })
                }
                put("points", pmPts)

                val polyArr = JSONArray()
                for (ring in pm.polygons) {
                    val ringArr = JSONArray()
                    for (p in ring) {
                        ringArr.put(JSONObject().apply {
                            put("lat", p.lat)
                            put("lon", p.lon)
                            put("alt", p.alt)
                        })
                    }
                    polyArr.put(ringArr)
                }
                put("polygons", polyArr)

                pm.lineColor?.let { put("lineColor", it) }
                pm.lineWidth?.let { put("lineWidth", it) }
                pm.polyColor?.let { put("polyColor", it) }
            })
        }
        return pmArr
    }

    private fun parseRouteDto(obj: JSONObject): SavedRouteDto {
        val id = obj.optString("id", UUID.randomUUID().toString())
        val name = obj.optString("name", "Route")
        val groupName = obj.optString("groupName", name)
        val color = obj.optInt("color", -65536)
        val width = obj.optDouble("width", 5.0).toFloat()
        val visible = obj.optBoolean("visible", true)
        val showArrows = obj.optBoolean("showDirectionArrows", true)
        val arrowColor = obj.optInt("arrowColor", color)
        val showDistance = obj.optBoolean("showDistanceMarkers", true)
        val distanceIntervalKm = obj.optInt("distanceIntervalKm", 5)

        val points = mutableListOf<SavedPointDto>()
        val ptsArr = obj.optJSONArray("points")
        if (ptsArr != null) {
            for (i in 0 until ptsArr.length()) {
                val pObj = ptsArr.getJSONObject(i)
                points.add(
                    SavedPointDto(
                        lat = pObj.getDouble("lat"),
                        lon = pObj.getDouble("lon"),
                        alt = pObj.optDouble("alt", 0.0)
                    )
                )
            }
        }

        val placemarks = parsePlacemarksArray(obj.optJSONArray("placemarks"))

        return SavedRouteDto(
            id = id,
            name = name,
            groupName = groupName,
            points = points,
            color = color,
            width = width,
            visible = visible,
            showDirectionArrows = showArrows,
            arrowColor = arrowColor,
            showDistanceMarkers = showDistance,
            distanceIntervalKm = distanceIntervalKm,
            placemarks = placemarks
        )
    }

    private fun serializeRouteDto(route: SavedRouteDto): JSONObject {
        return JSONObject().apply {
            put("id", route.id)
            put("name", route.name)
            put("groupName", route.groupName)
            put("color", route.color)
            put("width", route.width)
            put("visible", route.visible)
            put("showDirectionArrows", route.showDirectionArrows)
            put("arrowColor", route.arrowColor)
            put("showDistanceMarkers", route.showDistanceMarkers)
            put("distanceIntervalKm", route.distanceIntervalKm)

            val ptsArr = JSONArray()
            for (p in route.points) {
                ptsArr.put(JSONObject().apply {
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("alt", p.alt)
                })
            }
            put("points", ptsArr)

            if (route.placemarks.isNotEmpty()) {
                put("placemarks", serializePlacemarksArray(route.placemarks))
            }
        }
    }
}
