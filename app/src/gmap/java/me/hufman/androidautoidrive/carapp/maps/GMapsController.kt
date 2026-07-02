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
				projection?.updateGuidance(nav.updateGuidance(loc))
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

		// move the map dot to the new location
		gMapLocationSource.onLocationUpdate(location)

		// wlasny grot pozycji obracany wg kursu (mapa north-up)
		projection?.updateLocationPuck(location)

		// aktualizuj panel prowadzenia turn-by-turn (throttlowany do ~1/s)
		if (navController.currentNavDestination != null) {
			val guidance = navController.updateGuidance(location)
			val now = System.currentTimeMillis()
			if (now - lastGuidanceUiMs >= GUIDANCE_UI_INTERVAL_MS) {
				projection?.updateGuidance(guidance)
				lastGuidanceUiMs = now

				// EKSPERYMENT native panel: te same dane do natywnych komponentow RHMI (porownanie)
				// maneuverText = PELNA instrukcja (bez skracania) - celowo, to test ucinania natywnej labelki
				if (NativePanelTest.NATIVE_PANEL_TEST && guidance != null) {
					NativePanelTest.update(
							renderNativePanelTestIcon(guidance.maneuverArrow),
							guidance.maneuverText,
							"Przyjazd " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(guidance.etaEpochMillis)),
							"Pozostało " + formatNativePanelDistance(guidance.remainingDistanceMeters))
				}
			}
		} else {
			projection?.updateGuidance(null)
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
			projection?.map?.stopAnimation()
			projection?.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(cameraLocation, currentZoom), animationFinishedCallback)
			animatingCamera = true
		}
	}

	override fun navigateTo(dest: LatLong) {
		Log.i(TAG, "Beginning navigation to $dest")
		mapAppMode.startInteraction(NAVIGATION_MAP_STARTZOOM_TIME + 4000)
		// clear out previous nav
		projection?.map?.clear()
		projection?.resetLocationPuck()
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
				projection?.resetLocationPuck()   // clear() usunal grot - odtworzymy go nizej

				// destination flag
				val dest = navController.currentNavDestination
				if (dest != null) {
					val destLatLng = LatLng(dest.latitude, dest.longitude)
					val marker = MarkerOptions()
							.position(destLatLng)
							.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
							.visible(true)
					map.addMarker(marker)
				}

				// routing
				val currentNavRoute = navController.currentNavRoute
				if (currentNavRoute != null) {
					map.addPolyline(PolylineOptions().color(context.getColor(R.color.mapRouteLine)).addAll(currentNavRoute))
				}

				// odtworz grot pozycji po map.clear()
				currentLocation?.let { projection?.updateLocationPuck(it) }
			}
		}
		if (Looper.myLooper() != handler.looper) {
			handler.post(action)
		} else {
			action()
		}
	}

	/** EKSPERYMENT native panel: ikona manewru (glif tekstowy) jako PNG dla raImageModel 530 */
	private fun renderNativePanelTestIcon(maneuverArrow: String): ByteArray {
		val s = 120
		val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
		val canvas = android.graphics.Canvas(bmp)
		val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt()
			textSize = s * 0.72f
			textAlign = android.graphics.Paint.Align.CENTER
		}
		val y = s / 2f - (paint.descent() + paint.ascent()) / 2f
		canvas.drawText(maneuverArrow, s / 2f, y, paint)
		val out = java.io.ByteArrayOutputStream()
		bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
		return out.toByteArray()
	}

	/** EKSPERYMENT native panel: format dystansu jak w panelu (kopia lokalna na czas testu) */
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
		projection?.updateGuidance(null)
	}
}
