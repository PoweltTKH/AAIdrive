package me.hufman.androidautoidrive.carapp.maps

import android.os.Handler

/**
 * Natywny panel prowadzenia w lewym pasie RHMI (wersja DOCELOWA, nastepca NativePanelTest).
 *
 * Architektura: komponent obrazu mapy w aucie jest zwezony o PANEL_WIDTH_PX; w uwolnionym
 * pasie stoja natywne komponenty stanu mapy (deskryptor onlineservices id5 v2, hmiState 19):
 *  - label 135: dystans do manewru (tyka ~1/s -> male setData; font BMW)
 *  - image 134: reszta panelu jako PNG w NASZYM stylu (zielony blok: ikona-znak + pelna
 *    instrukcja; nizej Przyjazd/Pozostalo) - wysylany TYLKO przy zmianie tresci (co 5-15 s)
 *
 * Zysk: klatki mapy bez pasa panelu (-25-30% bajtow), aktualizacje panelu nie brudza klatek.
 * Lekcja z buildu testowego: 4 zapisy/s zapychaly kolejke RHMI (kilkanascie sekund opoznienia)
 * -> tu: 1 zapis/s z deduplikacja + rzadki PNG.
 *
 * enabled = false przywraca panel w bitmapie (uklad z buildu #9).
 */
object NativePanel {
	/** Wlacznik RUNTIME - sterowany ustawieniem MAP_NATIVE_PANEL (przelacznik w opcjach mapy
	 *  w aucie i w telefonie). Odswiezany w MapAppService.onCarStart i GMapsProjection.applySettings;
	 *  zmiana w trakcie sesji dziala w pelni po ponownym wejsciu w mape (szerokosc obrazu
	 *  ustawiana przy fokusie stanu). */
	@Volatile var enabled = true

	/** Szerokosc lewego pasa oddanego natywnym komponentom (px ekranu auta). */
	const val PANEL_WIDTH_PX = 223

	/** Wysokosc PNG panelu (px): zielony blok u samej gory + przerwa na natywna strzalke BMW
	 *  + sekcja Przyjazd/Pozostalo na dole. */
	const val PANEL_HEIGHT_PX = 432

	@Volatile private var handler: Handler? = null
	@Volatile private var distanceSink: ((String) -> Unit)? = null
	@Volatile private var imageSink: ((ByteArray) -> Unit)? = null
	@Volatile private var lastDistance: String? = null

	/** MapApp podpina zapisy do modeli RHMI, wykonywane na watku car. */
	fun attach(handler: Handler, distanceSink: (String) -> Unit, imageSink: (ByteArray) -> Unit) {
		this.handler = handler
		this.distanceSink = distanceSink
		this.imageSink = imageSink
		this.lastDistance = null
	}

	fun detach() {
		handler = null
		distanceSink = null
		imageSink = null
		lastDistance = null
	}

	/** Dystans do manewru; deduplikacja - bez zmiany wartosci zero ruchu po BT. */
	fun updateDistance(distance: String) {
		if (!enabled) return
		if (distance == lastDistance) return
		lastDistance = distance
		val h = handler ?: return
		val s = distanceSink ?: return
		h.post {
			try {
				s(distance)
			} catch (e: Exception) {
				// nie wywracamy apki, gdy HMI odrzuci setData
			}
		}
	}

	/** PNG panelu (zielony blok + Przyjazd/Pozostalo); wolajacy dba o wysylke tylko przy zmianie. */
	fun updatePanelImage(png: ByteArray) {
		if (!enabled) return
		val h = handler ?: return
		val s = imageSink ?: return
		h.post {
			try {
				s(png)
			} catch (e: Exception) {
			}
		}
	}
}
