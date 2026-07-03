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
	const val H = 360

	/** Prawa krawedz pasa bywa przykryta obrazem mapy (padding wymiarow RHMI) -
	 *  tresc PNG konczy sie wczesniej, zeby nic nie bylo przyciete. */
	private const val RIGHT_SAFE = 26

	fun render(context: Context, g: NavigationGuidance): ByteArray {
		val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)
		c.drawColor(0xFF1B1B1D.toInt())

		// zielony blok manewru (szerokosc bez strefy przykrywanej przez mape)
		val greenH = 176f
		val contentW = (W - RIGHT_SAFE).toFloat()
		c.drawRect(0f, 0f, contentW, greenH, Paint().apply { color = 0xFF0B8043.toInt() })

		// ikona manewru (znak drogowy, biala)
		val iconRes = if (g.isRoundabout) R.drawable.ic_gmap_roundabout else ManeuverIcons.res(g.maneuverType)
		val icon = context.getDrawable(iconRes)!!.mutate()
		icon.setBounds(14, 12, 14 + 56, 12 + 56)
		icon.draw(c)

		// numer zjazdu z ronda obok ikony
		if (g.isRoundabout && g.roundaboutExit != null) {
			val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = 0xFFFFFFFF.toInt(); textSize = 44f; isFakeBoldText = true
			}
			c.drawText("${g.roundaboutExit}", 84f, 57f, num)
		}

		// pelna instrukcja, lamana na max 3 linie (nasz maly font -> brak brutalnego uciecia BMW)
		val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 19f }
		val layout = StaticLayout.Builder.obtain(g.maneuverText, 0, g.maneuverText.length, tp, W - RIGHT_SAFE - 28)
				.setAlignment(Layout.Alignment.ALIGN_NORMAL)
				.setMaxLines(3)
				.setEllipsize(TextUtils.TruncateAt.END)
				.build()
		c.save()
		c.translate(14f, 78f)
		layout.draw(c)
		c.restore()

		// Przyjazd / Pozostalo (styl jak w dotychczasowym panelu)
		val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A6.toInt(); textSize = 17f }
		val valBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt(); textSize = 40f; isFakeBoldText = true
		}
		val valMed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt(); textSize = 31f; isFakeBoldText = true
		}
		c.drawText("Przyjazd", 14f, 220f, cap)
		c.drawText(formatEta(g.etaEpochMillis), 14f, 262f, valBig)
		c.drawText("Pozostało", 14f, 304f, cap)
		c.drawText(formatRemaining(g.remainingDistanceMeters), 14f, 340f, valMed)

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
