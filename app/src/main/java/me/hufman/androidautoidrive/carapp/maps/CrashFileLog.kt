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

	fun install(context: Context) {
		if (installed) return
		installed = true
		val dir = context.getExternalFilesDir(null) ?: return
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
}
