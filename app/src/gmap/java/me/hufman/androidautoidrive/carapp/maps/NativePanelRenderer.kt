package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import me.hufman.androidautoidrive.R
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renderuje PNG panelu prowadzenia dla natywnego pasa RHMI (NativePanel, image 134).
 * Wyglad 1:1 jak dotychczasowy panel w bitmapie: zielony blok (ikona-znak + pelna
 * instrukcja lamana na linie) + Przyjazd/Pozostalo na ciemnym tle.
 * Wysylany tylko przy zmianie tresci - dystans do manewru celowo NIE jest tu rysowany
 * (tyka co sekunde), idzie osobna natywna labelka.
 */
object NativePanelRenderer {
	const val W = NativePanel.PANEL_WIDTH_PX
	const val H = NativePanel.PANEL_HEIGHT_PX

	fun render(context: Context, g: NavigationGuidance): ByteArray {
		val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)

		// karta aplikacji (wariant 2 z makiety): lewe rogi zaokraglone, prawa krawedz
		// styka sie z mapa na ostro; poza clipem zostaje przezroczystosc (tlo auta)
		val r = NativePanel.CARD_RADIUS_PX.toFloat()
		val clip = android.graphics.Path().apply {
			addRoundRect(0f, 0f, W.toFloat(), H.toFloat(),
					floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r), android.graphics.Path.Direction.CW)
		}
		c.clipPath(clip)
		c.drawColor(0xFF1B1B1D.toInt())

		// zielony blok manewru: pelna szerokosc, od samej gory karty
		val greenH = 176f
		c.drawRect(0f, 0f, W.toFloat(), greenH, Paint().apply { color = 0xFF0B8043.toInt() })

		// ikona manewru (znak drogowy, biala) - lekki margines od gory (makieta: ~18 px)
		val iconRes = if (g.isRoundabout) R.drawable.ic_gmap_roundabout else ManeuverIcons.res(g.maneuverType)
		val icon = context.getDrawable(iconRes)!!.mutate()
		icon.setBounds(14, 20, 14 + 56, 20 + 56)
		icon.draw(c)

		// numer zjazdu z ronda obok ikony
		if (g.isRoundabout && g.roundaboutExit != null) {
			val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = 0xFFFFFFFF.toInt(); textSize = 44f; isFakeBoldText = true
			}
			c.drawText("${g.roundaboutExit}", 84f, 65f, num)
		}

		// pelna instrukcja, lamana na max 3 linie (nasz maly font -> brak brutalnego uciecia BMW)
		val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 19f }
		val layout = StaticLayout.Builder.obtain(g.maneuverText, 0, g.maneuverText.length, tp, W - 28)
				.setAlignment(Layout.Alignment.ALIGN_NORMAL)
				.setMaxLines(3)
				.setEllipsize(TextUtils.TruncateAt.END)
				.build()
		c.save()
		c.translate(14f, 104f)
		layout.draw(c)
		c.restore()

		// Przyjazd / Pozostalo: ponizej strefy strzalki BMW (ciemna przerwa ~176..280),
		// wysrodkowane w dolnej strefie z marginesem 18 px od dolnego zaokraglonego rogu
		val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A6.toInt(); textSize = 17f }
		val valBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt(); textSize = 40f; isFakeBoldText = true
		}
		val valMed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt(); textSize = 31f; isFakeBoldText = true
		}
		c.drawText("Przyjazd", 14f, 300f, cap)
		c.drawText(formatEta(g.etaEpochMillis), 14f, 338f, valBig)
		c.drawText("Pozostało", 14f, 376f, cap)
		c.drawText(formatRemaining(g.remainingDistanceMeters), 14f, 406f, valMed)

		return png(bmp)
	}

	/** Pusty (przezroczysty) obraz - czysci pas po zakonczeniu nawigacji. */
	fun renderEmpty(): ByteArray = png(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

	fun formatEta(epochMillis: Long): String =
			SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

	/** Pozostaly dystans z grubszym ziarnem (100 m pod 1 km), zeby PNG nie odswiezal sie co sekunde. */
	fun formatRemaining(m: Double): String =
			if (m >= 1000) "%.1f km".format(m / 1000.0)
			else "${(Math.round(m / 100.0) * 100).toInt()} m"

	private fun png(bmp: Bitmap): ByteArray {
		val out = ByteArrayOutputStream()
		bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
		return out.toByteArray()
	}
}
