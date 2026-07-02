package me.hufman.androidautoidrive.carapp.maps

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.location.Location
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.bimmergestalt.idriveconnectkit.SidebarRHMIDimensions
import io.bimmergestalt.idriveconnectkit.SubsetRHMIDimensions
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.maps.LatLong
import me.hufman.androidautoidrive.utils.TimeUtils
import java.util.*

class GMapsProjection(val parentContext: Context, display: Display, val appSettings: AppSettingsObserver, val locationSource: GMapsLocationSource): Presentation(parentContext, display), OnMapsSdkInitializedCallback {
	val TAG = "GMapsProjection"
	var map: GoogleMap? = null
	var mapListener: Runnable? = null
	var currentStyleId: Int? = null

	// widoki panelu prowadzenia turn-by-turn (panel z lewej)
	private var navPanel: View? = null
	private var navArrow: TextView? = null
	private var navInstruction: TextView? = null
	private var navDistance: TextView? = null
	private var navEta: TextView? = null
	private var navRemaining: TextView? = null

	// wlasny znacznik pozycji (grot obracany wg location.bearing) zamiast wbudowanej kropki
	private var locationPuck: Marker? = null
	private var puckIcon: BitmapDescriptor? = null

	val fullDimensions = display.run {
		val small = Point()
		val dimension = Point()
		display.getCurrentSizeRange(small, dimension)
		SubsetRHMIDimensions(dimension.x, dimension.y)
	}
	val sidebarDimensions = SidebarRHMIDimensions(fullDimensions) {
		appSettings[AppSettings.KEYS.MAP_WIDESCREEN].toBoolean()
	}

	// szerokosc panelu prowadzenia w px - jedno zrodlo dla layoutu panelu i dla paddingu mapy
	private val panelWidthPx: Int
		get() = (sidebarDimensions.appWidth * 0.30).toInt()

	/** Lewy margines widocznego obszaru na wirtualnym ekranie.
	 *  Przy natywnym panelu komponent obrazu w aucie jest zwezony o pas natywny i przesuniety
	 *  w prawo, wiec region przechwytywania (wezszy aspekt) zaczyna sie dalej od lewej. */
	private val splitMarginPx: Int
		get() {
			var m = (fullDimensions.appWidth - sidebarDimensions.appWidth) / 2
			if (NativePanel.ENABLED) m += NativePanel.PANEL_WIDTH_PX / 2
			return m
		}

	@SuppressLint("MissingPermission")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// specifically request the new renderer
		// can be removed after the new renderer is the default, Summer of 2022
		MapsInitializer.initialize(context.applicationContext, MapsInitializer.Renderer.LATEST, this);

		window?.setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION)
		setContentView(R.layout.gmaps_projection)

		// referencje do panelu prowadzenia
		navPanel = findViewById(R.id.navPanel)
		navArrow = findViewById(R.id.navArrow)
		navInstruction = findViewById(R.id.navInstruction)
		navDistance = findViewById(R.id.navDistance)
		navEta = findViewById(R.id.navEta)
		navRemaining = findViewById(R.id.navRemaining)
		layoutNavPanel()

		val gmapView = findViewById<MapView>(R.id.gmapView)
		gmapView.onCreate(savedInstanceState)
		gmapView.getMapAsync { map ->
			this.map = map

			// load initial theme settings for the map, location might not be loaded yet though
			applySettings()

			map.setLocationSource(locationSource)
			// wbudowana kropka wylaczona - rysujemy wlasny grot obracany wg kursu (updateLocationPuck)
			map.isMyLocationEnabled = false

			map.isIndoorEnabled = false

			with (map.uiSettings) {
				isCompassEnabled = true
				isMyLocationButtonEnabled = false
			}

			mapListener?.run()
		}
	}

	/** Dopasowuje panel do widocznego obszaru mapy (w trybie split mapa jest wezsza i wycentrowana) */
	private fun layoutNavPanel() {
		val panel = navPanel ?: return
		val margin = splitMarginPx
		val lp = panel.layoutParams
		if (lp is ViewGroup.MarginLayoutParams) {
			lp.leftMargin = margin
			lp.width = panelWidthPx
			panel.layoutParams = lp
		}
	}

	/** Aktualizuje panel prowadzenia; null = ukryj (brak nawigacji) */
	fun updateGuidance(g: NavigationGuidance?) {
		if (NativePanel.ENABLED) {
			// panel w bitmapie wylaczony - dane prowadzenia ida natywnym pasem RHMI
			// (NativePanel/NativePanelRenderer), a klatki mapy sa o pas panelu mniejsze
			navPanel?.visibility = View.GONE
			return
		}
		if (g == null) {
			navPanel?.visibility = View.GONE
			return
		}
		navPanel?.visibility = View.VISIBLE
		// wszystkie manewry jako ikony w stylu znakow drogowych; rondo (C-12) z numerem zjazdu
		navArrow?.text = if (g.isRoundabout) buildRoundaboutLabel(g.roundaboutExit)
				else buildManeuverLabel(maneuverIconRes(g.maneuverType))
		navInstruction?.text = g.maneuverText
		navDistance?.text = formatDistance(g.distanceToTurnMeters)
		navRemaining?.text = formatDistance(g.remainingDistanceMeters)
		navEta?.text = formatEta(g.etaEpochMillis)
	}

	private fun formatDistance(m: Double): String {
		return if (m < 1000) "${(Math.round(m / 10.0) * 10).toInt()} m"
		else "%.1f km".format(m / 1000.0)
	}

	private fun formatEta(epochMillis: Long): String {
		val sdf = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
		return sdf.format(Date(epochMillis))
	}

	/** Aktualizuje wlasny grot pozycji: pozycja + obrot wg kursu. Mapa zostaje north-up, obraca sie sam sprite. */
	fun updateLocationPuck(location: Location) {
		val map = this.map ?: return
		val pos = LatLng(location.latitude, location.longitude)
		val rot = if (location.hasBearing()) location.bearing else (locationPuck?.rotation ?: 0f)
		val puck = locationPuck
		if (puck == null) {
			val icon = puckIcon ?: buildLocationPuck().also { puckIcon = it }
			locationPuck = map.addMarker(MarkerOptions()
					.position(pos)
					.icon(icon)
					// glif jest teraz symetryczny wokol srodka bitmapy (okrag + chevron),
					// wiec anchor 0.5/0.5 jest jednoznaczny; jesli grot NADAL odsuniety od trasy,
					// to blad lateralny GPS z auta (CDS), nie geometria - wtedy: snap do polilinii
					.anchor(0.5f, 0.5f)
					.flat(true)           // lezy na mapie -> rotation == kurs geograficzny
					.rotation(rot)
					.zIndex(1000f))
		} else {
			puck.position = pos
			puck.rotation = rot
		}
	}

	/** Po map.clear() marker jest juz usuniety - kasujemy referencje, by przy nastepnym fixie odtworzyc grot. */
	fun resetLocationPuck() {
		locationPuck = null
	}

	/** Grot pozycji w stylu GMaps: niebieski okrag z bialym pierscieniem + bialy chevron kursu.
	 *  Symetryczny wokol srodka bitmapy -> wizualny srodek == pozycja GPS, bez niejednoznacznosci anchora. */
	private fun buildLocationPuck(): BitmapDescriptor {
		val s = 64
		val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)
		val cx = s / 2f; val cy = s / 2f; val r = s * 0.30f
		val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt(); style = Paint.Style.FILL }
		val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = s * 0.06f
		}
		val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
		c.drawCircle(cx, cy, r, fill)
		c.drawCircle(cx, cy, r, ring)
		val path = Path().apply {
			moveTo(cx, cy - r * 0.62f)                  // czubek chevronu (kurs)
			lineTo(cx - r * 0.52f, cy + r * 0.45f)
			lineTo(cx, cy + r * 0.12f)                  // wciecie
			lineTo(cx + r * 0.52f, cy + r * 0.45f)
			close()
		}
		c.drawPath(path, chevron)
		return BitmapDescriptorFactory.fromBitmap(bmp)
	}

	/** Etykieta ronda: ikona w stylu znaku C-12 (vector drawable, biale strzalki w okregu) + numer zjazdu obok. */
	private fun buildRoundaboutLabel(exit: Int?): CharSequence {
		return iconLabel(R.drawable.ic_gmap_roundabout, if (exit != null) "  $exit" else null)
	}

	/** Etykieta zwyklego manewru: sama ikona znaku. */
	private fun buildManeuverLabel(resId: Int): CharSequence = iconLabel(resId, null)

	private fun iconLabel(resId: Int, suffix: String?): CharSequence {
		val sizePx = (navArrow?.textSize ?: 44f).toInt().coerceAtLeast(24)
		val icon = context.getDrawable(resId)!!.mutate()
		icon.setBounds(0, 0, sizePx, sizePx)
		val sb = SpannableStringBuilder(" ")
		sb.setSpan(ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
		if (suffix != null) sb.append(suffix)
		return sb
	}

	/** Katalog manewr -> ikona znaku przeniesiony do ManeuverIcons (wspolny z NativePanelRenderer). */
	private fun maneuverIconRes(type: String?): Int = ManeuverIcons.res(type)

	override fun onStart() {
		super.onStart()
		Log.i(TAG, "Projection Start")
		val gmapView = findViewById<MapView>(R.id.gmapView)
		gmapView.onStart()
		gmapView.onResume()

		// watch for map settings
		appSettings.callback = {applySettings()}
	}

	fun applySettings() {
		// the narrow-screen option centers the viewport to the middle of the display
		// so update the map's margin to match
		val margin = splitMarginPx
		// panel natywny: caly kadr to mapa (panel poza bitmapa) -> symetryczny padding;
		// panel w bitmapie: lewy padding powiekszony o panel, zeby pozycja centrowala sie
		// w widocznym obszarze NA PRAWO od panelu
		val leftPad = if (NativePanel.ENABLED) margin else margin + panelWidthPx
		map?.setPadding(leftPad, 0, margin, 0)
		// panel tez musi sie dopasowac do biezacego trybu (full/split)
		layoutNavPanel()

		val style = appSettings[AppSettings.KEYS.GMAPS_STYLE].lowercase(Locale.ROOT)

		val location = this.locationSource.location
		val mapstyleId = when(style) {
			// odchudzony styl (slim) w dzien/normalnie -> mniej detalu = mniejsze klatki + czytelniej
			"auto" -> if (location == null || TimeUtils.getDayMode(LatLong(location.latitude, location.longitude))) R.raw.gmaps_style_slim else R.raw.gmaps_style_night
			"hybrid" -> null
			"night" -> R.raw.gmaps_style_night
			"aubergine" -> R.raw.gmaps_style_aubergine
			"midnight_commander" -> R.raw.gmaps_style_midnight_commander
			else -> R.raw.gmaps_style_slim
		}
		if (mapstyleId != currentStyleId) {
			Log.i(TAG, "Setting gmap style to $style")
			val mapstyle = if (mapstyleId != null) MapStyleOptions.loadRawResourceStyle(parentContext, mapstyleId) else null
			map?.setMapStyle(mapstyle)
		}
		if (style == "hybrid") {
			map?.mapType = GoogleMap.MAP_TYPE_HYBRID
		} else {
			map?.mapType = GoogleMap.MAP_TYPE_NORMAL
		}
		map?.isBuildingsEnabled = appSettings[AppSettings.KEYS.MAP_BUILDINGS] == "true"
		map?.isTrafficEnabled = appSettings[AppSettings.KEYS.MAP_TRAFFIC] == "true"
		currentStyleId = mapstyleId
	}

	override fun onStop() {
		super.onStop()
		Log.i(TAG, "Projection Stopped")
		val gmapView = findViewById<MapView>(R.id.gmapView)
		gmapView.onPause()
		gmapView.onStop()
		gmapView.onDestroy()
		appSettings.callback = null
	}

	override fun onSaveInstanceState(): Bundle {
		val output = super.onSaveInstanceState()
		val gmapView = findViewById<MapView>(R.id.gmapView)
		gmapView.onSaveInstanceState(output)
		return output
	}

	override fun onMapsSdkInitialized(renderer: MapsInitializer.Renderer) {
		when (renderer) {
			MapsInitializer.Renderer.LATEST -> Log.d("MapsDemo", "The latest version of the renderer is used.")
			MapsInitializer.Renderer.LEGACY -> Log.d("MapsDemo", "The legacy version of the renderer is used.")
		}
	}
}
