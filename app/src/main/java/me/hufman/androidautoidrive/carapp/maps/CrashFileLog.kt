package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zapis nieobsluzonych wyjatkow do pliku (diagnoza crashy bez adb):
 *     Android/data/me.hufman.androidautoidrive/files/gmap_crash.log
 * Wyjmowanie przez MTP, jak gmap_perf.csv. Dopisuje i przekazuje crash dalej
 * do poprzedniego handlera (nie zmienia zachowania aplikacji przy awarii).
 */
object CrashFileLog {
	private const val TAG = "CrashFileLog"
	private const val FILE_NAME = "gmap_crash.log"
	private var installed = false
	private var logDir: File? = null

	fun install(context: Context) {
		if (installed) return
		installed = true
		val dir = context.getExternalFilesDir(null) ?: return
		logDir = dir
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			try {
				val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
				File(dir, FILE_NAME).appendText(
						"\n=== $stamp | watek: ${thread.name} ===\n" +
						Log.getStackTraceString(throwable) + "\n")
			} catch (e: Exception) {
				// zapis logu nie moze przeszkodzic obsludze crasha
			}
			previous?.uncaughtException(thread, throwable)
		}
		Log.i(TAG, "Crash log -> ${File(dir, FILE_NAME).absolutePath}")
	}

	/** Notatka o obsluzonym (polknietym) bledzie - np. smierc watku car przy czkawce BT.
	 *  CarThread celowo lapie te wyjatki; tu zostawiamy slad do diagnozy "wywalen" bez adb. */
	fun note(source: String, throwable: Throwable?) {
		val dir = logDir ?: return
		try {
			val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
			File(dir, FILE_NAME).appendText(
					"\n--- $stamp | $source ---\n" +
					(throwable?.let { Log.getStackTraceString(it) } ?: "(bez wyjatku)") + "\n")
		} catch (e: Exception) {
		}
	}
}
