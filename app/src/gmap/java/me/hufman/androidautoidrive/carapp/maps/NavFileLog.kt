package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Dziennik zdarzen nawigacji (diagnoza bez adb):
 *     Android/data/me.hufman.androidautoidrive/files/gmap_nav.log
 * Male, rzadkie wpisy (trasa policzona, przerysowanie, reroute, wznowienie) -
 * do rozstrzygania problemow typu "pineska nie na koncu trasy" danymi, nie domyslami.
 */
object NavFileLog {
	private const val TAG = "NavFileLog"
	private const val FILE_NAME = "gmap_nav.log"
	private const val MAX_BYTES = 300 * 1024L   // powyzej: zaczynamy plik od nowa

	private var file: File? = null
	private val ioExecutor by lazy { Executors.newSingleThreadExecutor() }
	private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

	fun init(context: Context) {
		if (file != null) return
		file = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
		Log.i(TAG, "Nav log -> ${file?.absolutePath}")
	}

	fun log(message: String) {
		val f = file ?: return
		val line = "${fmt.format(Date())} | $message\n"
		ioExecutor.execute {
			try {
				if (f.exists() && f.length() > MAX_BYTES) f.delete()
				f.appendText(line)
			} catch (e: Exception) {
				// diagnostyka nie moze psuc nawigacji
			}
		}
	}
}
