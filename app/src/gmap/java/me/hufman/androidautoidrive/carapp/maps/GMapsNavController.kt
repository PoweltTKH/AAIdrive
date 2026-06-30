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

/** Dane prowadzenia turn-by-turn pokazywane na pasku nawigacji */
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
		val speedKmh = (location.speed * 3.6f).toInt().coerceAtLeast(0)
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
}
