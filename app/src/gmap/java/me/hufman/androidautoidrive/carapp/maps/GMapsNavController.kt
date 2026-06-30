package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.text.Html
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.PendingResult
import com.google.maps.model.DirectionsResult
import com.google.maps.model.DirectionsStep
import com.google.maps.model.TravelMode
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sqrt

/** Dane prowadzenia turn-by-turn pokazywane na panelu nawigacji */
data class NavigationGuidance(
		val maneuverArrow: String,
		val maneuverText: String,
		val distanceToTurnMeters: Double,
		val remainingDistanceMeters: Double,
		val etaEpochMillis: Long,
		val speedKmh: Int
)

class GMapsNavController(val geoClient: GeoApiContext, val locationProvider: CarLocationProvider, var callback: (GMapsNavController) -> Unit) {
	val TAG = "GMapsNavController"

	companion object {
		// zjazd z trasy: prog odleglosci od linii trasy i minimalny odstep miedzy przeliczeniami
		private const val OFFROUTE_THRESHOLD_M = 70.0
		private const val REROUTE_MIN_INTERVAL_MS = 12000L

		fun getInstance(context: Context, locationProvider: CarLocationProvider, callback: (GMapsNavController) -> Unit): GMapsNavController {
			val api_key = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
					.metaData.getString("com.google.android.geo.API_KEY") ?: ""
			val geoClient = GeoApiContext().setQueryRateLimit(3)
					.setApiKey(api_key)
					.setConnectTimeout(5, TimeUnit.SECONDS)
					.setReadTimeout(5, TimeUnit.SECONDS)
					.setWriteTimeout(5, TimeUnit.SECONDS)
			return GMapsNavController(geoClient, locationProvider, callback)
		}
	}

	var currentNavDestination: LatLong? = null
		private set
	var currentNavRoute: List<LatLng>? = null
		private set

	// kroki trasy do prowadzenia turn-by-turn
	private var steps: List<DirectionsStep> = emptyList()
	private var currentStepIndex: Int = 0

	// liczenie predkosci z przesuniecia pozycji GPS (pole predkosci z auta bywa smieciowe)
	private var lastSpeedLat: Double? = null
	private var lastSpeedLng: Double? = null
	private var lastSpeedTimeMs: Long = 0L
	private var smoothedSpeedKmh: Double = 0.0

	// wykrywanie zjazdu z trasy
	private var offRouteCount: Int = 0
	private var lastRerouteTimeMs: Long = 0L

	fun navigateTo(dest: LatLong) {
		currentNavDestination = dest

		val currentLocation = locationProvider.currentLocation ?: return
		routeNavigation(LatLong(currentLocation.latitude, currentLocation.longitude), dest)
	}

	fun stopNavigation() {
		currentNavDestination = null
		currentNavRoute = null
		steps = emptyList()
		currentStepIndex = 0
		offRouteCount = 0
		callback(this)
	}

	private fun routeNavigation(start: LatLong, dest: LatLong) {
		// start a route search
		val origin = com.google.maps.model.LatLng(start.latitude, start.longitude)
		val routeDest = com.google.maps.model.LatLng(dest.latitude, dest.longitude)

		val directionsRequest = DirectionsApi.newRequest(geoClient)
				.mode(TravelMode.DRIVING)
				.language("pl")
				.origin(origin)
				.destination(routeDest)
		directionsRequest.setCallback(object: PendingResult.Callback<DirectionsResult> {
			override fun onFailure(e: Throwable?) {
				Log.w(TAG, "Failed to find route! $e")
			}

			override fun onResult(result: DirectionsResult?) {
				if (result == null || result.routes.isEmpty()) { return }
				Log.i(TAG, "Adding route to map")
				val route = result.routes[0]
				// linia trasy (jak dotychczas)
				val decodedPath = route.overviewPolyline.decodePath()
				currentNavRoute = decodedPath.map {
					LatLng(it.lat, it.lng)
				}
				// kroki manewrow ze wszystkich odcinkow trasy
				steps = route.legs.flatMap { it.steps.toList() }
				currentStepIndex = 0
				callback(this@GMapsNavController)
			}
		})
	}

	/** Oblicza biezace dane prowadzenia na podstawie aktualnej pozycji auta */
	fun updateGuidance(location: Location): NavigationGuidance? {
		val steps = this.steps
		if (steps.isEmpty()) return null

		// --- wykrywanie zjazdu z trasy -> automatyczne przeliczenie ---
		val route = currentNavRoute
		if (route != null && route.size >= 2) {
			val offDist = distanceToPolylineMeters(location.latitude, location.longitude, route)
			val now = System.currentTimeMillis()
			if (offDist > OFFROUTE_THRESHOLD_M) {
				offRouteCount += 1
				if (offRouteCount >= 2 && now - lastRerouteTimeMs > REROUTE_MIN_INTERVAL_MS) {
					lastRerouteTimeMs = now
					offRouteCount = 0
					Log.i(TAG, "Off route by ${offDist.toInt()} m, recalculating")
					currentNavDestination?.let { navigateTo(it) }
					return null   // nowa trasa w drodze; pomijamy te klatke prowadzenia
				}
			} else {
				offRouteCount = 0
			}
		}

		if (currentStepIndex >= steps.size) return null

		// jesli dojechalismy blisko konca biezacego kroku, przejdz do nastepnego
		val curStep = steps[currentStepIndex]
		val distToCurEnd = distanceMeters(location.latitude, location.longitude,
				curStep.endLocation.lat, curStep.endLocation.lng)
		if (distToCurEnd < 25.0 && currentStepIndex < steps.size - 1) {
			currentStepIndex += 1
		}

		val step = steps[currentStepIndex]
		val distToTurn = distanceMeters(location.latitude, location.longitude,
				step.endLocation.lat, step.endLocation.lng)

		// pozostaly dystans = do najblizszego zakretu + suma kolejnych krokow
		var remaining = distToTurn
		for (i in (currentStepIndex + 1) until steps.size) {
			remaining += steps[i].distance.inMeters.toDouble()
		}
		// pozostaly czas = suma czasow biezacego i kolejnych krokow
		var remainingSec = 0L
		for (i in currentStepIndex until steps.size) {
			remainingSec += steps[i].duration.inSeconds
		}
		val eta = System.currentTimeMillis() + remainingSec * 1000L

		val speedKmh = computeSpeedKmh(location)

		@Suppress("DEPRECATION")
		val instr = Html.fromHtml(step.htmlInstructions ?: "").toString()

		return NavigationGuidance(
				maneuverArrow = arrowFor(step.maneuver),
				maneuverText = instr,
				distanceToTurnMeters = distToTurn,
				remainingDistanceMeters = remaining,
				etaEpochMillis = eta,
				speedKmh = speedKmh
		)
	}

	/** Predkosc liczona z przesuniecia pozycji w czasie (odporna na smieciowe pole predkosci z CDS) */
	private fun computeSpeedKmh(location: Location): Int {
		val nowMs = System.currentTimeMillis()
		val pLat = lastSpeedLat
		val pLng = lastSpeedLng
		if (pLat != null && pLng != null && lastSpeedTimeMs != 0L) {
			val dt = (nowMs - lastSpeedTimeMs) / 1000.0
			if (dt >= 0.5) {
				val d = distanceMeters(pLat, pLng, location.latitude, location.longitude)
				val kmh = (d / dt) * 3.6
				if (kmh in 0.0..250.0) {
					// filtr dolnoprzepustowy, zeby predkosc nie skakala
					smoothedSpeedKmh = 0.6 * smoothedSpeedKmh + 0.4 * kmh
				}
				lastSpeedLat = location.latitude
				lastSpeedLng = location.longitude
				lastSpeedTimeMs = nowMs
			}
		} else {
			lastSpeedLat = location.latitude
			lastSpeedLng = location.longitude
			lastSpeedTimeMs = nowMs
		}
		return smoothedSpeedKmh.toInt().coerceIn(0, 250)
	}

	private fun arrowFor(maneuver: String?): String {
		val m = maneuver ?: return "\u2191"
		return when {
			m.contains("uturn") -> "\u27F2"
			m.contains("left") -> "\u2190"
			m.contains("right") -> "\u2192"
			else -> "\u2191"
		}
	}

	private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
		val results = FloatArray(1)
		Location.distanceBetween(lat1, lng1, lat2, lng2, results)
		return results[0].toDouble()
	}

	/** Najmniejsza odleglosc punktu od lamanej trasy (w metrach) */
	private fun distanceToPolylineMeters(lat: Double, lng: Double, poly: List<LatLng>): Double {
		var best = Double.MAX_VALUE
		for (i in 0 until poly.size - 1) {
			val d = distanceToSegmentMeters(lat, lng,
					poly[i].latitude, poly[i].longitude,
					poly[i + 1].latitude, poly[i + 1].longitude)
			if (d < best) best = d
		}
		return best
	}

	/** Odleglosc punktu P od odcinka A-B, lokalna aproksymacja rownopowierzchniowa w metrach */
	private fun distanceToSegmentMeters(plat: Double, plng: Double,
	                                    alat: Double, alng: Double,
	                                    blat: Double, blng: Double): Double {
		val mPerDegLat = 111320.0
		val mPerDegLng = 111320.0 * cos(Math.toRadians(plat))
		val ax = (alng - plng) * mPerDegLng
		val ay = (alat - plat) * mPerDegLat
		val bx = (blng - plng) * mPerDegLng
		val by = (blat - plat) * mPerDegLat
		val dx = bx - ax
		val dy = by - ay
		val len2 = dx * dx + dy * dy
		val t = if (len2 <= 0.0) 0.0 else (((0.0 - ax) * dx + (0.0 - ay) * dy) / len2).coerceIn(0.0, 1.0)
		val cx = ax + t * dx
		val cy = ay + t * dy
		return sqrt(cx * cx + cy * cy)
	}
}
