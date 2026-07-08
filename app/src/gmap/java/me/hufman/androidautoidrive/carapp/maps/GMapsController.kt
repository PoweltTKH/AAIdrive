package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.AppSettingsObserver
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import kotlin.math.max
import kotlin.math.min

class GMapsController(private val context: Context,
                     private val carLocationProvider: CarLocationProvider,
                     private val virtualDisplay: VirtualDisplay,
                     private val appSettings: AppSettingsObserver,
                     private val mapAppMode: MapAppMode): MapInteractionController {
	val TAG = "GMapsController"
	var handler = Handler(context.mainLooper)
	var projection: GMapsProjection? = null

	private val SHUTDOWN_WAIT_INTERVAL = 120000L   // milliseconds of inactivity before shutting down map

	private var lastSettingsTime = 0L   // the last time we checked settings, for day/night check
	private val SETTINGS_TIME_INTERVAL = 5 * 60000  // milliseconds between checking day/night

	// throttling odswiezania panelu prowadzenia (oszczedza pasmo: mniej zmian obrazu do wyslania)
	private val GUIDANCE_UI_INTERVAL_MS = 1000L
	private var lastGuidanceUiMs = 0L

	val navController = GMapsNavController.getInstance(context, carLocationProvider) { nav ->
		drawNavigation()
		mapAppMode.currentNavDestination = nav.currentNavDestination
		// FIX: pokaz panel prowadzenia od razu po przeliczeniu trasy - wczesniej czekal na
		// nastepny fix GPS, przez co pierwsze wejscie w mape bylo bez belki z danymi
		handler.post {
			val loc = currentLocation
			if (nav.currentNavDestination != null && loc != null) {
				pushGuidance(nav.updateGuidance(loc))
				lastGuidanceUiMs = System.currentTimeMillis()
			}
		}
	}
	val gMapLocationSource = GMapsLocationSource()
	var currentLocation: Location? = null

	var animatingCamera = false
	var zoomingCamera = false   // whether the animation started with a zoom command, and thus should be cancelable
	val animationFinishedCallback = object: GoogleMap.CancelableCallback {
		override fun onFinish() {
			animatingCamera = false
			zoomingCamera = false
			startZoom = currentZoom // restore a backgrounded map to this zoom level
		}
		override fun onCancel() {
			animatingCamera = false
		}
	}
	private var startZoom = 6f  // what zoom level we start the projection with
	private var currentZoom = 15f

	// ostatni sensowny kurs jazdy do trybu obrotu mapy (GPS nie daje bearing na postoju -
	// trzymamy poprzedni, zeby mapa nie wracala do polnocy na swiatlach)
	private var lastBearing = 0f

	init {
		carLocationProvider.callback = { location ->
			handler.post {
				onLocationUpdate(location)
			}
		}
	}

	override fun showMap() {
		Log.i(TAG, "Beginning map projection")

		// cancel a shutdown timer
		handler.removeCallbacks(shutdownMapRunnable)

		if (projection == null) {
			Log.i(TAG, "First showing of the map")
			val projection = GMapsProjection(context, virtualDisplay.display, appSettings, gMapLocationSource)
			this.projection = projection
			projection.mapListener = Runnable {
				// when getMapAsync finishes
				initCamera()
				drawNavigation()    // restore navigation, if it's still going
			}

		}
		if (projection?.isShowing == false) {
			projection?.show()
		}
		// nudge the camera to trigger a redraw, in case we changed windows
		if (!animatingCamera) {
			projection?.map?.moveCamera(CameraUpdateFactory.scrollBy(1f, 1f))
		}

		// register for location updates
		carLocationProvider.start()
	}

	override fun pauseMap() {
		carLocationProvider.stop()

		handler.postDelayed(shutdownMapRunnable, SHUTDOWN_WAIT_INTERVAL)
	}

	private val shutdownMapRunnable = Runnable {
		Log.i(TAG, "Shutting down GMapProjection due to inactivity of ${SHUTDOWN_WAIT_INTERVAL}ms")
		projection?.hide()
		projection = null
	}

	private fun onLocationUpdate(location: Location) {
		if (currentLocation == null) {  // first view
			initCamera()
		}

		// save the new location and move the camera
		currentLocation = location
		updateCamera()

		// move the map dot to the new location (wbudowana kropka Google)
		gMapLocationSource.onLocationUpdate(location)

		// aktualizuj panel prowadzenia turn-by-turn (throttlowany do ~1/s)
		if (navController.currentNavDestination != null) {
			val guidance = navController.updateGuidance(location)
			val now = System.currentTimeMillis()
			if (now - lastGuidanceUiMs >= GUIDANCE_UI_INTERVAL_MS) {
				pushGuidance(guidance)
				lastGuidanceUiMs = now
			}
		} else {
			pushGuidance(null)
		}

		// check to re-apply day/night settings after an interval
		if (lastSettingsTime + SETTINGS_TIME_INTERVAL < System.currentTimeMillis()) {
			projection?.applySettings()
			lastSettingsTime = System.currentTimeMillis()
		}
	}

	override fun zoomIn(steps: Int) {
		Log.i(TAG, "Zooming map in $steps steps")
		mapAppMode.startInteraction()
		zoomingCamera = true
		currentZoom = min(18f, currentZoom + steps)
		updateCamera()
	}
	override fun zoomOut(steps: Int) {
		Log.i(TAG, "Zooming map out $steps steps")
		mapAppMode.startInteraction()
		zoomingCamera = true
		currentZoom = max(0f, currentZoom - steps)
		updateCamera()
	}

	private fun initCamera() {
		// set the camera to the starting position
		mapAppMode.startInteraction()
		val location = currentLocation
		if (location != null) {
			val cameraLocation = LatLng(location.latitude, location.longitude)
			projection?.map?.moveCamera(CameraUpdateFactory.newLatLngZoom(cameraLocation, startZoom))
		} else {
			projection?.map?.moveCamera(CameraUpdateFactory.zoomTo(startZoom))
		}
	}

	private fun updateCamera() {
		val location = currentLocation ?: return
		if (!animatingCamera || zoomingCamera) {
			// if the camera is idle or we are zooming the camera already
			val cameraLocation = LatLng(location.latitude, location.longitude)

			// tryby widoku (przelaczniki w opcjach mapy, czytane na biezaco):
			// 1. oba OFF -> 2D polnoc (domyslnie)   2. MAP_ROTATE -> 2D + obrot w kierunku jazdy
			// 3. MAP_ROTATE + MAP_TILT -> 3D perspektywa + obrot (sam MAP_TILT = 3D polnoc)
			val rotate = appSettings[AppSettings.KEYS.MAP_ROTATE].toBoolean()
			val tilt = appSettings[AppSettings.KEYS.MAP_TILT].toBoolean()
			if (location.hasBearing()) {
				lastBearing = location.bearing
			}
			val cameraPosition = CameraPosition.Builder()
					.target(cameraLocation)
					.zoom(currentZoom)
					.bearing(if (rotate) lastBearing else 0f)
					.tilt(if (tilt) 50f else 0f)
					.build()
			projection?.map?.stopAnimation()
			projection?.map?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), animationFinishedCallback)
			animatingCamera = true
		}
	}

	override fun navigateTo(dest: LatLong) {
		Log.i(TAG, "Beginning navigation to $dest")
		mapAppMode.startInteraction(NAVIGATION_MAP_STARTZOOM_TIME + 4000)
		// clear out previous nav
		projection?.map?.clear()
		// show new nav destination icon
		navController.navigateTo(dest)

		// start zoom animation
		animateNavigation()
	}

	private fun animateNavigation() {
		// show a camera animation to zoom out to the whole navigation route
		val dest = navController.currentNavDestination ?: return
		val lastLocation = currentLocation ?: return
		animatingCamera = true
		// zoom out to the full view
		val startLatLng = LatLng(lastLocation.latitude, lastLocation.longitude)
		val destLatLng = LatLng(dest.latitude, dest.longitude)

		val navigationBounds = LatLngBounds.builder()
				.include(startLatLng)
				.include(destLatLng)
				.build()
		val currentVisibleRegion = projection?.map?.projection?.visibleRegion?.latLngBounds
		if (currentVisibleRegion == null || !currentVisibleRegion.contains(navigationBounds.northeast) || !currentVisibleRegion.contains(navigationBounds.southwest)) {
			handler.postDelayed({
				projection?.map?.animateCamera(CameraUpdateFactory.newLatLngBounds(navigationBounds, NAVIGATION_MAP_STARTZOOM_PADDING))
			}, 100)
		}

		// then zoom back in to the user's chosen zoom
		handler.postDelayed({
			projection?.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(startLatLng, currentZoom))
			animatingCamera = false
		}, NAVIGATION_MAP_STARTZOOM_TIME.toLong())
	}

	private fun drawNavigation() {
		// make sure we are in the UI thread, and then draw navigation lines onto it
		// because route search comes back on a network thread
		val action = {
			val map = projection?.map
			if (map != null) {
				map.clear()

				// destination flag: pineska na KONCU polilinii trasy (zawsze na drodze, nie na
				// geokodzie celu). Stojacy billboard (domyslny) - flat(true) kladl pineske na mapie
				// i przy obrocie kamery wygladala jak lewitujaca obok konca trasy
				val dest = navController.currentNavDestination
				if (dest != null) {
					val destPos = navController.currentNavRoute?.lastOrNull()
							?: LatLng(dest.latitude, dest.longitude)
					val marker = MarkerOptions()
							.position(destPos)
							.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
							.visible(true)
					map.addMarker(marker)
				}

				// routing
				val currentNavRoute = navController.currentNavRoute
				if (currentNavRoute != null) {
					map.addPolyline(PolylineOptions().color(context.getColor(R.color.mapRouteLine)).addAll(currentNavRoute))
				}
			}
		}
		if (Looper.myLooper() != handler.looper) {
			handler.post(action)
		} else {
			action()
		}
	}

	// klucz tresci ostatnio wyslanego PNG panelu - wysylamy obraz tylko przy realnej zmianie
	private var lastPanelKey: String? = null

	/** Jednolity punkt aktualizacji prowadzenia: panel w bitmapie (flaga off) i/lub pas natywny */
	private fun pushGuidance(guidance: NavigationGuidance?) {
		projection?.updateGuidance(guidance)
		if (!NativePanel.enabled) return
		if (guidance == null) {
			if (lastPanelKey != "") {
				lastPanelKey = ""
				NativePanel.updateDistance(" ")
				NativePanel.updatePanelImage(NativePanelRenderer.renderEmpty())
			}
			return
		}
		// dystans do manewru: mala labelka, deduplikacja w NativePanel
		NativePanel.updateDistance(formatNativePanelDistance(guidance.distanceToTurnMeters))
		// PNG panelu: tylko gdy zmieni sie tresc (manewr/instrukcja/ETA/pozostalo z grubym ziarnem)
		val key = "${guidance.maneuverType}|${guidance.isRoundabout}|${guidance.roundaboutExit}|" +
				"${guidance.maneuverText}|${NativePanelRenderer.formatEta(guidance.etaEpochMillis)}|" +
				NativePanelRenderer.formatRemaining(guidance.remainingDistanceMeters)
		if (key != lastPanelKey) {
			lastPanelKey = key
			NativePanel.updatePanelImage(NativePanelRenderer.render(context, guidance))
		}
	}

	/** Format dystansu do manewru (ziarno 10 m pod 1 km - jak w dotychczasowym panelu) */
	private fun formatNativePanelDistance(m: Double): String {
		return if (m < 1000) "${(Math.round(m / 10.0) * 10).toInt()} m"
		else "%.1f km".format(m / 1000.0)
	}

	override fun recalcNavigation() {
		navController.currentNavDestination?.let {
			navController.navigateTo(it)
		}
	}

	override fun stopNavigation() {
		// clear out previous nav
		navController.stopNavigation()
		pushGuidance(null)
	}
}
