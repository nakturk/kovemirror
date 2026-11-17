package com.kove.mirror.auto

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kove.mirror.CurveWarningHelper
import com.kove.mirror.DebugLogger
import com.kove.mirror.MapStateHolder
import com.kove.mirror.NavigationHelper
import com.kove.mirror.R
import com.kove.mirror.RouteFuelHelper
import com.kove.mirror.RouteWeatherHelper
import org.osmdroid.util.GeoPoint
import java.util.TimeZone

/**
 * Main Android Auto navigation screen backed by NavigationTemplate.
 * Displays turn-by-turn Maneuver/RoutingInfo, ETA, MapActionStrip, and ActionStrip,
 * while CarMapPresentation renders the live map inside the template surface.
 */
class CarNavigationScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer
) : Screen(carContext), MapStateHolder.StateListener {

    private data class ActiveNavStatus(
        val currentStep: NavigationHelper.RouteStep,
        val nextStep: NavigationHelper.RouteStep?,
        val distToStepMeters: Double,
        val remainingDistanceMeters: Double,
        val remainingDurationSeconds: Long,
        val maneuverType: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val navigationManager: NavigationManager =
        carContext.getCarService(NavigationManager::class.java)

    private var isNavManagerStarted = false
    private var lastKnownSegmentIndex = 0
    private var lastInvalidateMs = 0L

    init {
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                DebugLogger.info("🚗 [Android Auto] Host requested onStopNavigation")
                isNavManagerStarted = false
                MapStateHolder.stopNavigationFromExternal(carContext)
            }

            override fun onAutoDriveEnabled() {
                DebugLogger.info("🚗 [Android Auto] Auto drive enabled")
            }
        })

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                MapStateHolder.addListener(this@CarNavigationScreen)
                syncNavigationManagerState(MapStateHolder.isNavigating)
                invalidate()
            }

            override fun onStop(owner: LifecycleOwner) {
                MapStateHolder.removeListener(this@CarNavigationScreen)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (isNavManagerStarted) {
                    try {
                        navigationManager.navigationEnded()
                    } catch (_: Exception) {}
                    isNavManagerStarted = false
                }
                try {
                    navigationManager.clearNavigationManagerCallback()
                } catch (_: Exception) {}
            }
        })
    }

    override fun onGetTemplate(): Template {
        val builder = NavigationTemplate.Builder()

        builder.setMapActionStrip(buildMapActionStrip())
        builder.setActionStrip(buildActionStrip())

        val route = MapStateHolder.activeNavigationRoute
        if (MapStateHolder.isNavigating && route != null && route.geometryPoints.isNotEmpty()) {
            val status = computeActiveNavStatus(MapStateHolder.lastLocation, route)
            if (status != null) {
                val maneuver = buildManeuver(status.maneuverType)
                val step = Step.Builder(status.currentStep.instruction.ifBlank { "Devam et" })
                    .setManeuver(maneuver)
                    .build()

                val routingBuilder = RoutingInfo.Builder()
                    .setCurrentStep(step, toCarDistance(status.distToStepMeters))

                status.nextStep?.let { next ->
                    val nextManeuver = buildManeuver(mapManeuverType(next))
                    val nextCarStep = Step.Builder(next.instruction.ifBlank { "Devam et" })
                        .setManeuver(nextManeuver)
                        .build()
                    routingBuilder.setNextStep(nextCarStep)
                }

                val arrivalEpochMs =
                    System.currentTimeMillis() + (status.remainingDurationSeconds * 1000L)
                val destEstimate = TravelEstimate.Builder(
                    toCarDistance(status.remainingDistanceMeters),
                    DateTimeWithZone.create(arrivalEpochMs, TimeZone.getDefault())
                )
                    .setRemainingTimeSeconds(status.remainingDurationSeconds)
                    .build()

                builder.setNavigationInfo(routingBuilder.build())
                builder.setDestinationTravelEstimate(destEstimate)

                updateClusterTrip(step, status, destEstimate)
            }
        }

        return builder.build()
    }

    private fun buildMapActionStrip(): ActionStrip {
        val zoomInIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_zoom_in)
        ).build()
        val zoomOutIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_zoom_out)
        ).build()
        val recenterIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_my_location)
        ).build()

        return ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(zoomInIcon)
                    .setOnClickListener { renderer.zoomIn() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(zoomOutIcon)
                    .setOnClickListener { renderer.zoomOut() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(recenterIcon)
                    .setOnClickListener { renderer.recenterOnLocation() }
                    .build()
            )
            .build()
    }

    private fun buildActionStrip(): ActionStrip {
        val fuelTint = if (MapStateHolder.showRouteFuel) {
            CarColor.createCustom(0xFF34D399.toInt(), 0xFF34D399.toInt())
        } else {
            CarColor.createCustom(0xFF94A3B8.toInt(), 0xFF94A3B8.toInt())
        }
        val weatherTint = if (MapStateHolder.showRouteWeather) {
            CarColor.createCustom(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt())
        } else {
            CarColor.createCustom(0xFF94A3B8.toInt(), 0xFF94A3B8.toInt())
        }

        val fuelIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_fuel)
        ).setTint(fuelTint).build()

        val weatherIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_weather)
        ).setTint(weatherTint).build()

        val routeIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_route)
        ).build()

        val stripBuilder = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(fuelIcon)
                    .setOnClickListener {
                        val enabled = MapStateHolder.toggleRouteFuel(carContext) { count, hasPoi ->
                            mainHandler.post {
                                if (!hasPoi) {
                                    CarToast.makeText(
                                        carContext,
                                        carContext.getString(R.string.toast_select_offline_poi),
                                        CarToast.LENGTH_LONG
                                    ).show()
                                } else if (MapStateHolder.showRouteFuel) {
                                    CarToast.makeText(
                                        carContext,
                                        "⛽ $count benzin istasyonu",
                                        CarToast.LENGTH_SHORT
                                    ).show()
                                }
                                invalidate()
                            }
                        }
                        if (!enabled) {
                            CarToast.makeText(
                                carContext,
                                "⛽ Benzin istasyonları kapatıldı",
                                CarToast.LENGTH_SHORT
                            ).show()
                        }
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(weatherIcon)
                    .setOnClickListener {
                        val enabled = MapStateHolder.toggleRouteWeather(carContext)
                        CarToast.makeText(
                            carContext,
                            if (enabled) "🌤️ Hava durumu açıldı" else "🌤️ Hava durumu kapatıldı",
                            CarToast.LENGTH_SHORT
                        ).show()
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(routeIcon)
                    .setTitle("Rotalar")
                    .setOnClickListener {
                        screenManager.push(CarRoutesFavoritesScreen(carContext, renderer))
                    }
                    .build()
            )

        if (MapStateHolder.isNavigating) {
            val stopIcon = CarIcon.Builder(
                IconCompat.createWithResource(carContext, R.drawable.ic_car_stop)
            ).build()
            stripBuilder.addAction(
                Action.Builder()
                    .setIcon(stopIcon)
                    .setOnClickListener {
                        MapStateHolder.stopNavigationFromExternal(carContext)
                    }
                    .build()
            )
        } else {
            val layersIcon = CarIcon.Builder(
                IconCompat.createWithResource(carContext, R.drawable.ic_car_layers)
            ).build()
            stripBuilder.addAction(
                Action.Builder()
                    .setIcon(layersIcon)
                    .setOnClickListener { renderer.cycleMapLayer() }
                    .build()
            )
        }

        return stripBuilder.build()
    }

    private fun updateClusterTrip(
        step: Step,
        status: ActiveNavStatus,
        destEstimate: TravelEstimate
    ) {
        if (!isNavManagerStarted) return
        try {
            val stepArrivalMs = System.currentTimeMillis() +
                (status.currentStep.durationSeconds.toLong().coerceAtLeast(0L) * 1000L)
            val stepEstimate = TravelEstimate.Builder(
                toCarDistance(status.distToStepMeters),
                DateTimeWithZone.create(stepArrivalMs, TimeZone.getDefault())
            )
                .setRemainingTimeSeconds(status.currentStep.durationSeconds.toLong().coerceAtLeast(0L))
                .build()

            val destination = Destination.Builder()
                .setName("Hedef")
                .build()

            val trip = Trip.Builder()
                .addStep(step, stepEstimate)
                .addDestination(destination, destEstimate)
                .setCurrentRoad(status.currentStep.instruction)
                .build()

            navigationManager.updateTrip(trip)
        } catch (_: Exception) {
        }
    }

    private fun syncNavigationManagerState(navigating: Boolean) {
        if (navigating && !isNavManagerStarted) {
            try {
                navigationManager.navigationStarted()
                isNavManagerStarted = true
            } catch (e: Exception) {
                DebugLogger.warning("⚠️ [Android Auto] navigationStarted failed: ${e.message}")
            }
        } else if (!navigating && isNavManagerStarted) {
            try {
                navigationManager.navigationEnded()
            } catch (_: Exception) {}
            isNavManagerStarted = false
        }
    }

    private fun computeActiveNavStatus(
        currentLocation: GeoPoint?,
        route: NavigationHelper.NavigationRoute
    ): ActiveNavStatus? {
        val pts = route.geometryPoints
        if (pts.isEmpty() || route.steps.isEmpty()) return null

        val loc = currentLocation ?: pts.first()
        var nearestSegIndex = lastKnownSegmentIndex.coerceIn(0, (pts.size - 2).coerceAtLeast(0))
        var remMeters = route.totalDistanceMeters

        if (pts.size > 1) {
            var minSegDist = Double.MAX_VALUE
            val searchStart = (nearestSegIndex - 5).coerceAtLeast(0)
            val searchEnd = (nearestSegIndex + 50).coerceAtMost(pts.size - 2)

            for (i in searchStart..searchEnd) {
                val d = NavigationHelper.distanceToSegmentMeters(loc, pts[i], pts[i + 1])
                if (d < minSegDist) {
                    minSegDist = d
                    nearestSegIndex = i
                }
            }
            if (minSegDist > 200.0) {
                for (i in 0 until pts.size - 1) {
                    val d = NavigationHelper.distanceToSegmentMeters(loc, pts[i], pts[i + 1])
                    if (d < minSegDist) {
                        minSegDist = d
                        nearestSegIndex = i
                    }
                }
            }
            lastKnownSegmentIndex = nearestSegIndex

            var calcRem = loc.distanceToAsDouble(pts[nearestSegIndex + 1])
            for (i in (nearestSegIndex + 1) until pts.size - 1) {
                calcRem += pts[i].distanceToAsDouble(pts[i + 1])
            }
            if (calcRem >= 0.0 && calcRem <= route.totalDistanceMeters * 1.5) {
                remMeters = calcRem
            }
        }

        var activeStepIndex = -1
        for (i in route.steps.indices) {
            val step = route.steps[i]
            if (step.type == "arrive") {
                if (activeStepIndex == -1) activeStepIndex = i
                break
            }
            if (step.shapeIndex > nearestSegIndex) {
                activeStepIndex = i
                break
            } else if (step.shapeIndex == nearestSegIndex) {
                if (loc.distanceToAsDouble(step.location) > 15.0) {
                    activeStepIndex = i
                    break
                }
            }
        }

        if (activeStepIndex == -1) {
            activeStepIndex = (route.steps.size - 1).coerceAtLeast(0)
        }
        if (activeStepIndex in route.steps.indices &&
            route.steps[activeStepIndex].type == "depart" &&
            activeStepIndex + 1 < route.steps.size
        ) {
            activeStepIndex++
        }

        val activeStep = route.steps.getOrNull(activeStepIndex) ?: return null
        val nextStep = route.steps.getOrNull(activeStepIndex + 1)

        val targetIdx = activeStep.shapeIndex.coerceIn(0, (pts.size - 1).coerceAtLeast(0))
        val distToStepMeters = if (targetIdx > nearestSegIndex && pts.size > 1) {
            var d = loc.distanceToAsDouble(pts[(nearestSegIndex + 1).coerceAtMost(pts.size - 1)])
            for (i in (nearestSegIndex + 1) until targetIdx) {
                d += pts[i].distanceToAsDouble(pts[i + 1])
            }
            d
        } else {
            loc.distanceToAsDouble(activeStep.location)
        }

        val remSeconds = if (route.totalDistanceMeters > 0.0) {
            ((remMeters / route.totalDistanceMeters) * route.totalDurationSeconds)
                .toLong()
                .coerceAtLeast(0L)
        } else {
            0L
        }

        return ActiveNavStatus(
            currentStep = activeStep,
            nextStep = nextStep,
            distToStepMeters = distToStepMeters.coerceAtLeast(0.0),
            remainingDistanceMeters = remMeters.coerceAtLeast(0.0),
            remainingDurationSeconds = remSeconds,
            maneuverType = mapManeuverType(activeStep)
        )
    }

    private fun mapManeuverType(step: NavigationHelper.RouteStep): Int {
        val t = step.type.lowercase()
        val m = step.modifier.lowercase()
        return when {
            t == "arrive" -> Maneuver.TYPE_DESTINATION
            t == "depart" -> Maneuver.TYPE_DEPART
            t.contains("roundabout") || t.contains("rotary") ->
                Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            m.contains("uturn") || m.contains("u turn") || t.contains("uturn") || t.contains("u turn") ->
                Maneuver.TYPE_U_TURN_LEFT
            m.contains("sharp left") -> Maneuver.TYPE_TURN_SHARP_LEFT
            m.contains("sharp right") -> Maneuver.TYPE_TURN_SHARP_RIGHT
            m.contains("slight left") -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            m.contains("slight right") -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            m.contains("left") -> Maneuver.TYPE_TURN_NORMAL_LEFT
            m.contains("right") -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            else -> Maneuver.TYPE_STRAIGHT
        }
    }

    private fun buildManeuver(maneuverType: Int): Maneuver {
        val builder = Maneuver.Builder(maneuverType)
        if (maneuverType == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) {
            builder.setRoundaboutExitNumber(1)
        }
        return builder.build()
    }

    private fun toCarDistance(meters: Double): Distance {
        val safeMeters = meters.coerceAtLeast(0.0)
        return if (safeMeters < 1000.0) {
            Distance.create(safeMeters.toInt().toDouble(), Distance.UNIT_METERS)
        } else {
            Distance.create(safeMeters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        }
    }

    // ─── MapStateHolder.StateListener ──────────────────────────

    override fun onNavigationStateChanged(
        isNavigating: Boolean,
        route: NavigationHelper.NavigationRoute?
    ) {
        mainHandler.post {
            lastKnownSegmentIndex = 0
            syncNavigationManagerState(isNavigating)
            invalidate()
        }
    }

    override fun onRoutesChanged(routes: List<MapStateHolder.SharedRoute>) {
        // Rendered directly on CarMapPresentation
    }

    override fun onLocationUpdated(
        location: GeoPoint,
        bearing: Float,
        speedKmH: Int,
        altitude: Double
    ) {
        if (!MapStateHolder.isNavigating) return
        val route = MapStateHolder.activeNavigationRoute ?: return

        val now = System.currentTimeMillis()
        if (now - lastInvalidateMs < 750L) return
        lastInvalidateMs = now

        mainHandler.post {
            if (!MapStateHolder.isNavigating) return@post
            if (!MapStateHolder.isMapOpen) {
                val status = computeActiveNavStatus(location, route)
                if (status != null && status.remainingDistanceMeters < 30.0) {
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.nav_arrived),
                        CarToast.LENGTH_LONG
                    ).show()
                    MapStateHolder.stopNavigationFromExternal(carContext)
                    return@post
                }
            }
            invalidate()
        }
    }

    override fun onLayerChanged(layer: Int) {
        // Rendered directly on CarMapPresentation
    }

    override fun onCameraChanged(center: GeoPoint, zoom: Double, bearing: Float) {
    }

    override fun onWeatherUpdated(weatherPoints: List<RouteWeatherHelper.RouteWeatherPoint>) {
        mainHandler.post { invalidate() }
    }

    override fun onFuelStationsUpdated(fuelStations: List<RouteFuelHelper.FuelStation>) {
        mainHandler.post { invalidate() }
    }

    override fun onCurvesUpdated(curves: List<CurveWarningHelper.CurvePoint>) {
    }
}
