package me.hufman.androidautoidrive.carapp.maps

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
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
import com.google.android.gms.maps.model.MapStyleOptions
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

	// maska karty aplikacji: przykrywa skrawek mapy nad wspolna linia (dol belki BMW)
	// i zaokragla prawe rogi mapy; lewe rogi karty zaokragla PNG panelu (NativePanelRenderer)
	private var mapMask: MapCardMaskView? = null

	// widoki panelu prowadzenia turn-by-turn (panel z lewej)
	private var navPanel: View? = null
	private var navArrow: TextView? = null
	private var navInstruction: TextView? = null
	private var navDistance: TextView? = null
	private var navEta: TextView? = null
	private var navRemaining: TextView? = null

	// surowe wymiary wirtualnego wyswietlacza - te same, ktorych uzywa przechwytywanie klatek
	private val displaySize = Point().also { display.getCurrentSizeRange(Point(), it) }

	val fullDimensions = SubsetRHMIDimensions(displaySize.x, displaySize.y)
	val sidebarDimensions = SidebarRHMIDimensions(fullDimensions) {
		appSettings[AppSettings.KEYS.MAP_WIDESCREEN].toBoolean()
	}

	// szerokosc panelu prowadzenia w px - jedno zrodlo dla layoutu panelu i dla paddingu mapy
	private val panelWidthPx: Int
		get() = (sidebarDimensions.appWidth * 0.30).toInt()

	/** Lewy margines widocznego obszaru na wirtualnym ekranie (tryb panelu w bitmapie). */
	private val splitMarginPx: Int
		get() = (fullDimensions.appWidth - sidebarDimensions.appWidth) / 2

	/** Region wyswietlacza, ktory REALNIE trafia do klatki przy panelu natywnym -
	 *  lustrzane odbicie findInnerRect z VirtualDisplayScreenCapture (ta sama matematyka,
	 *  te same surowe wymiary displaya). Maska i padding kamery MUSZA uzywac tego regionu;
	 *  liczenie z appWidth (wymiary RHMI) dawalo szersza "dziure" niz kadr i prawa krawedz
	 *  maski z zaokraglonymi rogami wypadala poza klatka. */
	private fun mapCaptureRect(): android.graphics.Rect {
		// wymiary komponentu obrazu mapy w aucie: karta podchodzi pod lewy padding okna,
		// wiec budzet poziomy to visibleWidth (pion od linii belki: appHeight)
		val mapW = sidebarDimensions.visibleWidth - NativePanel.PANEL_WIDTH_PX - NativePanel.CARD_EDGE_PX
		val mapH = sidebarDimensions.appHeight - NativePanel.CARD_EDGE_PX
		var w = displaySize.x
		var h = w * mapH / mapW
		if (h > displaySize.y) {
			h = displaySize.y
			w = h * mapW / mapH
		}
		val left = (displaySize.x - w) / 2
		val top = (displaySize.y - h) / 2
		return android.graphics.Rect(left, top, left + w, top + h)
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

		// nakladka maski na wierzchu calego layoutu (rysuje tylko przy NativePanel.enabled)
		val mask = MapCardMaskView(context)
		mapMask = mask
		addContentView(mask, ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

		val gmapView = findViewById<MapView>(R.id.gmapView)
		gmapView.onCreate(savedInstanceState)
		gmapView.getMapAsync { map ->
			this.map = map

			// load initial theme settings for the map, location might not be loaded yet though
			applySettings()

			map.setLocationSource(locationSource)
			// wbudowana kropka pozycji Google - wlasny grot i snap do trasy wycofane
			// (dwie iteracje odklejaly sie od trasy przez blad lateralny GPS auta)
			map.isMyLocationEnabled = true

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
		if (NativePanel.enabled) {
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
		// przelacznik panelu natywnego (opcje mapy w aucie/telefonie); pelne przelaczenie
		// szerokosci obrazu nastapi przy nastepnym wejsciu w mape (fokus stanu)
		NativePanel.enabled = appSettings[AppSettings.KEYS.MAP_NATIVE_PANEL].toBoolean()

		// the narrow-screen option centers the viewport to the middle of the display
		// so update the map's margin to match
		// panel natywny: kadr = karta mapy (maska: gorny pas, prawe rogi, marginesy) ->
		// padding dopasowany do "dziury" maski, liczonej z REGIONU PRZECHWYTYWANIA;
		// panel w bitmapie: stary uklad
		if (NativePanel.enabled) {
			// kadr == komponent mapy w aucie (marginesy karty sa w rozmiarze komponentu,
			// nie w masce) -> kamera centruje sie dokladnie w wycinku przechwytywania
			val cap = mapCaptureRect()
			map?.setPadding(cap.left, cap.top,
					displaySize.x - cap.right, displaySize.y - cap.bottom)
		} else {
			val margin = splitMarginPx
			map?.setPadding(margin + panelWidthPx, 0, margin, 0)
		}
		mapMask?.invalidate()
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

	/** Maska karty aplikacji (wariant 2 z makiety): wypelnia #1B1B1D wszystko poza
	 *  zaokraglonym oknem mapy - gorny pas nad wspolna linia, marginesy dol/prawo,
	 *  prawe rogi 22 px. Lewa krawedz okna = styk z panelem (ostro, bez zaokraglenia). */
	private inner class MapCardMaskView(context: Context): View(context) {
		private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1B1D.toInt() }
		private val path = Path()

		override fun onDraw(canvas: Canvas) {
			if (!NativePanel.enabled) return
			val r = NativePanel.CARD_RADIUS_PX.toFloat()
			// kadr == komponent mapy 1:1, wiec maska to juz TYLKO zaokraglenie prawych rogow:
			// wypelniamy wszystko poza dziura o ksztalcie kadru z prawymi rogami 22 px
			// (w kadrze laduja wylacznie narozne "wygryzki"; marginesy karty daje rozmiar komponentu)
			val cap = mapCaptureRect()
			path.reset()
			path.fillType = Path.FillType.EVEN_ODD
			path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
			path.addRoundRect(cap.left.toFloat(), cap.top.toFloat(),
					cap.right.toFloat(), cap.bottom.toFloat(),
					floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f), Path.Direction.CW)
			canvas.drawPath(path, paint)
		}
	}
}
