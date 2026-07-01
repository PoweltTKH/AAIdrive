package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Instrumentacja pomiarowa pipeline'u klatek mapy (flavor gmap).
 *
 * SAM POMIAR - nie zmienia kadencji, jakosci JPEG, animacji kamery ani logiki wysylki.
 * Agreguje cztery metryki w oknach 2 s (srednia + min + max + liczba klatek) i DOPISUJE
 * jeden wiersz CSV do:
 *     <getExternalFilesDir(null)>/gmap_perf.csv
 * czyli na telefonie: Android/data/me.hufman.androidautoidrive/files/gmap_perf.csv
 * (dostepne przez zwykle podlaczenie kablem jako MTP, bez adb).
 *
 * Wylaczenie: PERF_LOG = false (jedna flaga na gorze).
 *
 * Metody record()/init() sa wolane wylacznie z watku car-handlera (jednowatkowo w tej
 * sciezce), wiec agregacja nie wymaga synchronizacji. Sam zapis do pliku jest zrzucany
 * na osobny watek (ioExecutor), zeby zapis IO nie wplywal na kadencje watku renderujacego.
 */
object MapFramePerfLog {
	// ====== JEDYNY WLACZNIK ======
	const val PERF_LOG = true          // domyslnie true na czas testow; false = pelne wylaczenie

	private const val TAG = "MapFramePerfLog"
	private const val WINDOW_MS = 2000L
	private const val FILE_NAME = "gmap_perf.csv"

	private const val CSV_HEADER = "timestamp,frames,fps," +
			"bytes_avg,bytes_min,bytes_max," +
			"compress_ms_avg,compress_ms_min,compress_ms_max," +
			"roundtrip_ms_avg,roundtrip_ms_min,roundtrip_ms_max\n"

	private var file: File? = null
	private val ioExecutor by lazy { Executors.newSingleThreadExecutor() }
	private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

	// ---- stan okna agregacji (tylko watek car-handlera) ----
	private var windowStartNanos = 0L
	private var frames = 0
	private var bytesSum = 0L
	private var bytesMin = Long.MAX_VALUE
	private var bytesMax = 0L
	private var compSum = 0.0
	private var compMin = Double.MAX_VALUE
	private var compMax = 0.0
	private var rtSum = 0.0
	private var rtMin = Double.MAX_VALUE
	private var rtMax = 0.0

	/** Inicjalizacja z kontekstem (wolana z gmap MapAppService.onCarStart). Idempotentna. */
	fun init(context: Context) {
		if (!PERF_LOG || file != null) return
		try {
			val dir = context.getExternalFilesDir(null) ?: return
			val f = File(dir, FILE_NAME)
			if (!f.exists()) {
				f.appendText(CSV_HEADER)
			}
			file = f
			Log.i(TAG, "Perf log -> ${f.absolutePath}")
		} catch (e: Exception) {
			Log.w(TAG, "Nie udalo sie zainicjalizowac pliku perf", e)
		}
	}

	/**
	 * Rejestruje jedna faktycznie wyslana klatke. Wszystkie czasy w milisekundach.
	 * @param bytes      rozmiar skompresowanego JPEG (imageData.size)
	 * @param compressMs czas compressBitmap()
	 * @param roundtripMs czas synchronicznego rhmi_setData (begin -> ack z auta)
	 */
	fun record(bytes: Int, compressMs: Double, roundtripMs: Double) {
		if (!PERF_LOG || file == null) return
		val now = System.nanoTime()
		if (windowStartNanos == 0L) windowStartNanos = now

		frames++
		bytesSum += bytes
		if (bytes < bytesMin) bytesMin = bytes.toLong()
		if (bytes > bytesMax) bytesMax = bytes.toLong()
		compSum += compressMs
		if (compressMs < compMin) compMin = compressMs
		if (compressMs > compMax) compMax = compressMs
		rtSum += roundtripMs
		if (roundtripMs < rtMin) rtMin = roundtripMs
		if (roundtripMs > rtMax) rtMax = roundtripMs

		val elapsedMs = (now - windowStartNanos) / 1_000_000.0
		if (elapsedMs >= WINDOW_MS) {
			flush(elapsedMs)
			reset(now)
		}
	}

	private fun flush(elapsedMs: Double) {
		val f = file ?: return
		if (frames == 0) return
		val fps = frames * 1000.0 / elapsedMs   // realny fps = klatki / rzeczywisty czas okna
		val line = String.format(Locale.US,
				"%s,%d,%.2f,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f\n",
				timestampFmt.format(Date(System.currentTimeMillis())),
				frames, fps,
				bytesSum / frames, bytesMin, bytesMax,
				compSum / frames, compMin, compMax,
				rtSum / frames, rtMin, rtMax)
		// zapis IO poza watkiem renderujacym, zeby nie ruszyc kadencji
		ioExecutor.execute {
			try {
				f.appendText(line)
			} catch (e: Exception) {
				Log.w(TAG, "Blad zapisu wiersza perf", e)
			}
		}
	}

	private fun reset(now: Long) {
		windowStartNanos = now
		frames = 0
		bytesSum = 0L; bytesMin = Long.MAX_VALUE; bytesMax = 0L
		compSum = 0.0; compMin = Double.MAX_VALUE; compMax = 0.0
		rtSum = 0.0; rtMin = Double.MAX_VALUE; rtMax = 0.0
	}
}
