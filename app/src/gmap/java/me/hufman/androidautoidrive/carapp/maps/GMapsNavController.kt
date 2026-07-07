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
		val isRoundabout: Boolean = false,
		val roundaboutExit: Int? = null,
		/** surowy manewr Google (turn-left, ramp-right, keep-left...) lub nasze: "straight", "destination";
		 *  keep-x/ramp-x z tekstem "zjazd" w instrukcji mapowane na ramp-x (ikona zjazdu z drogi szybkiej) */
		val maneuverType: String? = null
)

class GMapsNavController(val geoClient: GeoApiContext, val locationProvider: CarLocationProvider, var callback: (GMapsNavController) -> Unit) {
	val TAG = "GMapsNavController"

	companion object {
		// zjazd z trasy: prog odleglosci od trasy i minimalny odstep miedzy przeliczeniami
		private const val OFFROUTE_THRESHOLD_M = 70.0
		private const val REROUTE_MIN_INTERVAL_MS = 10000L

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

	// kroki trasy + detaliczna geometria kazdego kroku (do dokladnego dopasowania pozycji)
	private var steps: List<DirectionsStep> = emptyList()
	private var stepPaths: List<List<LatLng>> = emptyList()
	private var currentStepIndex: Int = 0

	// wykrywanie zjazdu z trasy
	private var offRouteCount: Int = 0
	private var lastRerouteTimeMs: Long = 0L

	// wspolczynnik ruchu drogowego: duration_in_traffic / duration z momentu wyznaczenia trasy;
	// statyczne czasy krokow mnozymy przez niego, zeby ETA odpowiadalo realnemu ruchowi
	private var trafficFactor: Double = 1.0

	fun navigateTo(dest: LatLong) {
		currentNavDestination = dest

		val currentLocation = locationProvider.currentLocation ?: return
		routeNavigation(LatLong(currentLocation.latitude, currentLocation.longitude), dest)
	}

	fun stopNavigation() {
		currentNavDestination = null
		currentNavRoute = null
		steps = emptyList()
		stepPaths = emptyList()
		currentStepIndex = 0
		offRouteCount = 0
		trafficFactor = 1.0
		callback(this)
	}

	private fun routeNavigation(start: LatLong, dest: LatLong) {
		// start a route search
		val origin = com.google.maps.model.LatLng(start.latitude, start.longitude)
		val routeDest = com.google.maps.model.LatLng(dest.latitude, dest.longitude)

		val directionsRequest = DirectionsApi.newRequest(geoClient)
				.mode(TravelMode.DRIVING)
				.language("pl")
				// trasa swiadoma biezacego ruchu + duration_in_traffic w odpowiedzi (realne ETA)
				.departureTime(org.joda.time.DateTime.now())
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
				// kroki manewrow ze wszystkich odcinkow trasy
				val newSteps = route.legs.flatMap { it.steps.toList() }
				// detaliczna geometria kazdego kroku (dokladniejsza niz overviewPolyline)
				val newStepPaths = newSteps.map { step ->
					step.polyline.decodePath().map { LatLng(it.lat, it.lng) }
				}
				steps = newSteps
				stepPaths = newStepPaths
				// wspolczynnik ruchu: ile realny czas (z korkami) jest dluzszy od statycznego
				var staticSec = 0L
				var trafficSec = 0L
				route.legs.forEach { leg ->
					val s = leg.duration?.inSeconds ?: 0L
					staticSec += s
					trafficSec += leg.durationInTraffic?.inSeconds ?: s
				}
				trafficFactor = if (staticSec > 0) trafficSec.toDouble() / staticSec else 1.0
				// linia trasy do narysowania = sklejone detaliczne odcinki krokow
				currentNavRoute = newStepPaths.flatten()
				currentStepIndex = 0
				offRouteCount = 0
				callback(this@GMapsNavController)
			}
		})
	}

	/** Oblicza biezace dane prowadzenia na podstawie aktualnej pozycji auta */
	fun updateGuidance(location: Location): NavigationGuidance? {
		val steps = this.steps
		val stepPaths = this.stepPaths
		if (steps.isEmpty() || stepPaths.size != steps.size) return null

		// --- dopasuj sie do najblizszego kroku OD biezacego w przod (snap do trasy) ---
		var bestIdx = currentStepIndex
		var bestDist = Double.MAX_VALUE
		for (i in currentStepIndex until steps.size) {
			val d = distanceToPolylineMeters(location.latitude, location.longitude, stepPaths[i])
			if (d < bestDist) {
				bestDist = d
				bestIdx = i
			}
		}
		currentStepIndex = bestIdx

		// --- wykrywanie zjazdu z trasy -> automatyczne przeliczenie ---
		val now = System.currentTimeMillis()
		if (bestDist > OFFROUTE_THRESHOLD_M) {
			offRouteCount += 1
			if (offRouteCount >= 2 && now - lastRerouteTimeMs > REROUTE_MIN_INTERVAL_MS) {
				lastRerouteTimeMs = now
				offRouteCount = 0
				Log.i(TAG, "Off route by ${bestDist.toInt()} m, recalculating")
				currentNavDestination?.let { navigateTo(it) }
				return null   // nowa trasa w drodze; pomijamy te klatke prowadzenia
			}
		} else {
			offRouteCount = 0
		}

		var curStep = steps[currentStepIndex]
		// nastepny manewr wykonuje sie na KONCU biezacego kroku (== poczatek nastepnego kroku)
		var distToTurn = distanceMeters(location.latitude, location.longitude,
				curStep.endLocation.lat, curStep.endLocation.lng)

		// jestesmy tuz przy koncu kroku -> manewr wykonany, przelacz na nastepny krok;
		// bez tego stojac juz na skrzyzowaniu panel dalej pokazywal poprzedni manewr (np. zjazd)
		if (distToTurn < 15.0 && currentStepIndex < steps.size - 1) {
			currentStepIndex += 1
			curStep = steps[currentStepIndex]
			distToTurn = distanceMeters(location.latitude, location.longitude,
					curStep.endLocation.lat, curStep.endLocation.lng)
		}

		// pozostaly dystans do celu = do konca biezacego kroku + suma kolejnych krokow
		var remaining = distToTurn
		for (i in (currentStepIndex + 1) until steps.size) {
			remaining += steps[i].distance.inMeters.toDouble()
		}
		// pozostaly czas do celu (statyczne czasy krokow skalowane wspolczynnikiem ruchu)
		var remainingSec = 0L
		for (i in currentStepIndex until steps.size) {
			remainingSec += steps[i].duration.inSeconds
		}
		val eta = System.currentTimeMillis() + (remainingSec * trafficFactor * 1000.0).toLong()

		// WAZNE: pokazujemy NADCHODZACY manewr (z nastepnego kroku), nie ten juz wykonany.
		// W danych Google instrukcja opisuje manewr na POCZATKU kroku, wiec nastepny zakret
		// to instrukcja kroku (currentStepIndex + 1), a odleglosc do niego = do konca biezacego.
		val upcomingIdx = currentStepIndex + 1
		val arrow: String
		val instr: String
		var isRoundabout = false
		var roundaboutExit: Int? = null
		var maneuverType: String? = null
		if (upcomingIdx < steps.size) {
			val next = steps[upcomingIdx]
			@Suppress("DEPRECATION")
			val nextInstr = Html.fromHtml(next.htmlInstructions ?: "").toString()
			val maneuver = next.maneuver
			arrow = if (maneuver != null && maneuver.contains("roundabout")) {
				val exit = parseRoundaboutExit(nextInstr)
				isRoundabout = true
				roundaboutExit = exit
				if (exit != null) "\u21BB" + circledNumber(exit) else "\u21BB"
			} else {
				maneuverType = maneuverTypeFor(maneuver, nextInstr)
				arrowFor(maneuver)
			}
			instr = nextInstr
		} else {
			// biezacy krok jest ostatni -> dojazd do celu
			maneuverType = "destination"
			arrow = "\u25C9"
			instr = "Cel podróży"
		}

		return NavigationGuidance(
				maneuverArrow = arrow,
				maneuverText = instr,
				distanceToTurnMeters = distToTurn,
				remainingDistanceMeters = remaining,
				etaEpochMillis = eta,
				isRoundabout = isRoundabout,
				roundaboutExit = roundaboutExit,
				maneuverType = maneuverType
		)
	}

	/** Normalizuje manewr Google do typu ikony. Heurystyka zjazdu: keep-x/ramp-x z "zjazd/zjedz"
	 *  w polskiej instrukcji -> ramp-x (piktogram zjazdu z drogi szybkiej zamiast "trzymaj sie pasa"). */
	private fun maneuverTypeFor(maneuver: String?, instruction: String): String {
		if (maneuver == null) return "straight"   // pierwszy krok trasy nie ma pola maneuver
		val lower = instruction.lowercase()
		val isExit = lower.contains("zjazd") || lower.contains("zjed")
		if (isExit && (maneuver.startsWith("keep") || maneuver.startsWith("ramp"))) {
			return if (maneuver.endsWith("left")) "ramp-left" else "ramp-right"
		}
		return maneuver
	}

	private fun arrowFor(maneuver: String?): String {
		val m = maneuver ?: return "\u2191"
		return when {
			m.contains("uturn") -> "\u27F2"
			m.contains("roundabout") -> "\u21BB"
			m.contains("left") -> "\u2190"
			m.contains("right") -> "\u2192"
			else -> "\u2191"
		}
	}

	/** Wyciaga numer zjazdu z ronda z polskiej wskazowki (np. "Na rondzie trzeci zjazd ..." -> 3) */
	private fun parseRoundaboutExit(instruction: String): Int? {
		val lower = instruction.lowercase()
		// forma cyfrowa: "3. zjazd" / "3 zjazd"
		Regex("(\\d+)\\s*\\.?\\s*zjazd").find(lower)?.let {
			return it.groupValues[1].toIntOrNull()
		}
		// forma slowna (z polskimi znakami i bez)
		val words = mapOf(
				"pierwszy" to 1, "drugi" to 2, "trzeci" to 3, "czwarty" to 4,
				"piąty" to 5, "piaty" to 5, "szósty" to 6, "szosty" to 6,
				"siódmy" to 7, "siodmy" to 7, "ósmy" to 8, "osmy" to 8,
				"dziewiąty" to 9, "dziewiaty" to 9, "dziesiąty" to 10, "dziesiaty" to 10
		)
		for ((w, n) in words) {
			if (lower.contains("$w zjazd")) return n
		}
		return null
	}

	/** Liczba w kolku: 1 -> ①, 3 -> ③ (Unicode U+2460..). Powyzej 20 zwraca zwykla cyfre. */
	private fun circledNumber(n: Int): String {
		return if (n in 1..20) ('\u2460' + (n - 1)).toString() else "$n"
	}

	private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
		val results = FloatArray(1)
		Location.distanceBetween(lat1, lng1, lat2, lng2, results)
		return results[0].toDouble()
	}

	/** Najmniejsza odleglosc punktu od lamanej (w metrach) */
	private fun distanceToPolylineMeters(lat: Double, lng: Double, poly: List<LatLng>): Double {
		if (poly.isEmpty()) return Double.MAX_VALUE
		if (poly.size == 1) return distanceMeters(lat, lng, poly[0].latitude, poly[0].longitude)
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
