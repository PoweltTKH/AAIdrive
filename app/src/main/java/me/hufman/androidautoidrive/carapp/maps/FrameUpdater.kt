package me.hufman.androidautoidrive.carapp.maps

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import de.bmw.idrive.BMWRemoting
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIModel

interface FrameModeListener {
	fun onResume()
	fun onPause()
}

class FrameUpdater(val display: VirtualDisplayScreenCapture, val modeListener: FrameModeListener?): Runnable {
	var destination: RHMIModel? = null
	var isRunning = true
	private var handler: Handler? = null

	// tlumienie identycznych klatek (np. gdy auto stoi) - nie wysylamy tej samej klatki ponownie
	private var lastSentHash = 0
	private var lastSentLen = -1

	fun start(handler: Handler) {
		this.handler = handler
		Log.i(TAG, "Starting FrameUpdater thread with handler $handler")
		display.registerImageListener(ImageReader.OnImageAvailableListener // Called from the UI thread to say a new image is available
		{
			// let the car thread consume the image
			schedule()
		})
		schedule()  // check for a first image
	}

	override fun run() {
		var bitmap = display.getFrame()
		if (bitmap != null) {
			sendImage(bitmap)
			schedule()  // check if there's another frame ready for us right now
		} else {
			// wait for the next frame, unless the callback comes back sooner
			schedule(1000)
		}
	}

	fun schedule(delayMs: Int = 0) {
		handler?.removeCallbacks(this)   // remove any previously-scheduled invocations
		handler?.postDelayed(this, delayMs.toLong())
	}

	fun shutDown() {
		isRunning = false
		display.registerImageListener(null)
		handler?.removeCallbacks(this)
	}

	fun showWindow(width: Int, height: Int, destination: RHMIModel) {
		this.destination = destination
		Log.i(TAG, "Changing map mode to $width x $height")
		display.changeImageSize(width, height)
		lastSentLen = -1   // wymus ponowne wyslanie po zmianie okna/rozmiaru
		modeListener?.onResume()
	}
	fun hideWindow(destination: RHMIModel) {
		if (this.destination == destination) {
			this.destination = null
			modeListener?.onPause()
		}
	}

	private fun sendImage(bitmap: Bitmap) {
		val compressStart = System.nanoTime()   // PERF: pomiar czasu kompresji
		val imageData = display.compressBitmap(bitmap)
		val compressMs = (System.nanoTime() - compressStart) / 1_000_000.0   // PERF
		// jesli klatka jest identyczna jak poprzednio wyslana, pomijamy ja (oszczedza pasmo w bezruchu)
		val hash = imageData.contentHashCode()
		if (imageData.size == lastSentLen && hash == lastSentHash) {
			return
		}
		lastSentLen = imageData.size
		lastSentHash = hash
		val sendStart = System.nanoTime()   // PERF: poczatek synchronicznego rhmi_setData
		try {
			val destination = this.destination
			if (destination is RHMIModel.RaImageModel) {
				destination.value = imageData
			} else if (destination is RHMIModel.RaListModel) {
				val list = RHMIModel.RaListModel.RHMIListConcrete(1)
				list.addRow(arrayOf(BMWRemoting.RHMIResourceData(BMWRemoting.RHMIResourceType.IMAGEDATA, imageData)))
				destination.value = list
			}
		} catch (e: RuntimeException) {
		} catch (e: org.apache.etch.util.TimeoutException) {
			// don't crash if the phone is unplugged during a frame update
		}
		// PERF: round-trip = czas trwania blokujacego setData (begin -> ack z auta)
		val roundtripMs = (System.nanoTime() - sendStart) / 1_000_000.0
		MapFramePerfLog.record(imageData.size, compressMs, roundtripMs)
	}
}
