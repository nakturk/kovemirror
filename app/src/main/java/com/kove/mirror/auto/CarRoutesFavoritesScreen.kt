package com.kove.mirror.auto

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kove.mirror.CurveWarningHelper
import com.kove.mirror.FavoritesManager
import com.kove.mirror.MapStateHolder
import com.kove.mirror.NavigationHelper
import com.kove.mirror.R
import com.kove.mirror.RouteWeatherHelper
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * Android Auto screen listing favorite destinations (to start turn-by-turn navigation)
 * and saved GPX/KML routes (to toggle visibility on the map).
 */
class CarRoutesFavoritesScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer? = null
) : Screen(carContext),
    MapStateHolder.StateListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCalculatingRoute = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                MapStateHolder.addListener(this@CarRoutesFavoritesScreen)
                invalidate()
            }

            override fun onStop(owner: LifecycleOwner) {
                MapStateHolder.removeListener(this@CarRoutesFavoritesScreen)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val builder = ListTemplate.Builder()
            .setHeaderAction(Action.BACK)
            .setTitle("Rotalar ve Favoriler")

        renderer?.let { r ->
            val layersIcon = CarIcon.Builder(
                IconCompat.createWithResource(carContext, R.drawable.ic_car_layers)
            ).build()
            builder.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(layersIcon)
                            .setOnClickListener {
                                r.cycleMapLayer()
                                CarToast.makeText(
                                    carContext,
                                    "Harita katmanı değiştirildi",
                                    CarToast.LENGTH_SHORT
                                ).show()
                            }
                            .build()
                    )
                    .build()
            )
        }

        if (isCalculatingRoute) {
            return builder.setLoading(true).build()
        }

        val favorites = FavoritesManager.getFavorites(carContext)
        val routes = MapStateHolder.loadedRoutes.toList()

        if (favorites.isEmpty() && routes.isEmpty()) {
            val emptyList = ItemList.Builder()
                .setNoItemsMessage("Kayıtlı rota veya favori konum bulunamadı")
                .build()
            return builder.setSingleList(emptyList).build()
        }

        val maxItems = getMaxListItems().coerceAtLeast(2)
        val (favLimit, routeLimit) = allocateLimits(favorites.size, routes.size, maxItems)

        if (favLimit > 0) {
            val favListBuilder = ItemList.Builder()
            for (fav in favorites.take(favLimit)) {
                val subtitle = fav.address.ifBlank {
                    String.format(Locale.US, "%.4f, %.4f", fav.latitude, fav.longitude)
                }
                favListBuilder.addItem(
                    Row.Builder()
                        .setTitle(fav.name)
                        .addText("Navigasyon başlat • $subtitle")
                        .setOnClickListener {
                            startNavigationToFavorite(fav.name, fav.latitude, fav.longitude)
                        }
                        .build()
                )
            }
            builder.addSectionedList(
                SectionedItemList.create(favListBuilder.build(), "Favori Konumlar")
            )
        }

        if (routeLimit > 0) {
            val routeListBuilder = ItemList.Builder()
            for (route in routes.take(routeLimit)) {
                val statusText = if (route.visible) {
                    "Haritada Açık (Gizlemek için dokunun)"
                } else {
                    "Gizli (Göstermek için dokunun)"
                }
                routeListBuilder.addItem(
                    Row.Builder()
                        .setTitle(route.name)
                        .addText("$statusText • ${route.points.size} nokta")
                        .setOnClickListener {
                            val willBeVisible = !route.visible
                            MapStateHolder.toggleRouteVisibility(carContext, route.id)
                            if (willBeVisible && route.points.isNotEmpty() && !MapStateHolder.isNavigating) {
                                MapStateHolder.updateCamera(
                                    route.points[route.points.size / 2],
                                    13.0,
                                    0f
                                )
                            }
                            CarToast.makeText(
                                carContext,
                                if (willBeVisible) "${route.name} gösteriliyor" else "${route.name} gizlendi",
                                CarToast.LENGTH_SHORT
                            ).show()
                            invalidate()
                        }
                        .build()
                )
            }
            builder.addSectionedList(
                SectionedItemList.create(routeListBuilder.build(), "Kayıtlı GPX/KML Rotalar")
            )
        }

        return builder.build()
    }

    private fun startNavigationToFavorite(name: String, lat: Double, lon: Double) {
        val start = MapStateHolder.lastLocation ?: MapStateHolder.currentCenter
        if (start == null || (start.latitude == 0.0 && start.longitude == 0.0)) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.map_waiting_gps),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }

        val dest = GeoPoint(lat, lon)
        val prefs = carContext.getSharedPreferences("kove_map_prefs", Context.MODE_PRIVATE)
        val avoidTolls = prefs.getBoolean("nav_avoid_tolls", false)
        val avoidHighways = prefs.getBoolean("nav_avoid_highways", false)
        val useShortest = prefs.getBoolean("nav_use_shortest", false)

        isCalculatingRoute = true
        invalidate()

        NavigationHelper.fetchRoute(
            start = start,
            destination = dest,
            avoidTolls = avoidTolls,
            avoidHighways = avoidHighways,
            useShortest = useShortest,
            onSuccess = { navRoute ->
                mainHandler.post {
                    isCalculatingRoute = false
                    MapStateHolder.isNavigating = true
                    MapStateHolder.activeNavigationRoute = navRoute
                    MapStateHolder.selectedDestination = dest

                    if (prefs.getBoolean("show_curve_warnings", true) && navRoute.geometryPoints.size >= 4) {
                        MapStateHolder.updateCurves(CurveWarningHelper.detectCurves(navRoute.geometryPoints))
                    }
                    MapStateHolder.notifyNavigationChanged()
                    MapStateHolder.refreshWeatherFromState(carContext, force = true)
                    MapStateHolder.refreshFuelFromState(carContext, force = true)

                    CarToast.makeText(
                        carContext,
                        "Navigasyon başlatıldı: $name",
                        CarToast.LENGTH_SHORT
                    ).show()
                    screenManager.pop()
                }
            },
            onError = { err ->
                mainHandler.post {
                    isCalculatingRoute = false
                    CarToast.makeText(
                        carContext,
                        "Rota hesaplanamadı: $err",
                        CarToast.LENGTH_SHORT
                    ).show()
                    invalidate()
                }
            }
        )
    }

    private fun getMaxListItems(): Int {
        return try {
            if (carContext.carAppApiLevel >= 2) {
                carContext.getCarService(ConstraintManager::class.java)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
            } else {
                6
            }
        } catch (_: Exception) {
            6
        }
    }

    private fun allocateLimits(favCount: Int, routeCount: Int, maxTotal: Int): Pair<Int, Int> {
        if (favCount + routeCount <= maxTotal) return Pair(favCount, routeCount)
        if (favCount == 0) return Pair(0, routeCount.coerceAtMost(maxTotal))
        if (routeCount == 0) return Pair(favCount.coerceAtMost(maxTotal), 0)

        val half = (maxTotal / 2).coerceAtLeast(1)
        val favTake = favCount.coerceAtMost(half)
        val routeTake = routeCount.coerceAtMost(maxTotal - favTake)
        val extraFav = (maxTotal - favTake - routeTake).coerceAtMost(favCount - favTake)
        return Pair(favTake + extraFav, routeTake)
    }

    // ─── MapStateHolder.StateListener ──────────────────────────

    override fun onRoutesChanged(routes: List<MapStateHolder.SharedRoute>) {
        mainHandler.post { invalidate() }
    }

    override fun onNavigationStateChanged(
        isNavigating: Boolean,
        route: NavigationHelper.NavigationRoute?
    ) {
    }

    override fun onLocationUpdated(
        location: GeoPoint,
        bearing: Float,
        speedKmH: Int,
        altitude: Double
    ) {
    }

    override fun onLayerChanged(layer: Int) {
    }

    override fun onCameraChanged(center: GeoPoint, zoom: Double, bearing: Float) {
    }

    override fun onWeatherUpdated(weatherPoints: List<RouteWeatherHelper.RouteWeatherPoint>) {
    }

    override fun onCurvesUpdated(curves: List<CurveWarningHelper.CurvePoint>) {
    }
}
