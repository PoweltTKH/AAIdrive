package me.hufman.androidautoidrive.carapp.maps

import android.os.Handler

/**
 * EKSPERYMENT "native panel" - rozpoznanie bojem, NIE docelowa przebudowa.
 *
 * Cel: pokazac na ekranie iDrive nieuzywane natywne komponenty stanu mapy
 * (image 134 + label 135/136/137 z deskryptora onlineservices id5 v2) wypelnione
 * zywymi danymi nawigacji, ROWNOLEGLE z dzisiejszym panelem w bitmapie,
 * zeby porownac wyglad (font BMW, pozycje, ucinanie dlugiego tekstu).
 *
 * NATIVE_PANEL_TEST = false -> zero zmian, zero efektu (jak PERF_LOG).
 *
 * Przeplyw: GMapsController (watek UI telefonu) -> update() -> post na watek car
 * -> sink ustawia modele RHMI (male setData: 3x tekst + 1x PNG ikony).
 */
object NativePanelTest {
	// ====== JEDYNY WLACZNIK ======
	const val NATIVE_PANEL_TEST = true   // WLACZONE na build testowy (jazda porownawcza); po tescie wracamy na false

	@Volatile private var handler: Handler? = null
	@Volatile private var sink: ((iconPng: ByteArray?, line1: String, line2: String, line3: String) -> Unit)? = null

	/** MapApp podpina watek car + zapis do modeli RHMI (tylko gdy flaga ON). */
	fun attach(handler: Handler, sink: (ByteArray?, String, String, String) -> Unit) {
		this.handler = handler
		this.sink = sink
	}

	fun detach() {
		handler = null
		sink = null
	}

	/** Wolane z GMapsController przy kazdej (throttlowanej ~1/s) aktualizacji prowadzenia. */
	fun update(iconPng: ByteArray?, line1: String, line2: String, line3: String) {
		if (!NATIVE_PANEL_TEST) return
		val h = handler ?: return
		val s = sink ?: return
		h.post {
			try {
				s(iconPng, line1, line2, line3)
			} catch (e: Exception) {
				// build testowy - nie wywracamy apki, gdy HMI odrzuci setData
			}
		}
	}
}
