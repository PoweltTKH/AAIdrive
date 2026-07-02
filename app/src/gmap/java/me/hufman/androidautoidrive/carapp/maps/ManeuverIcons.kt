package me.hufman.androidautoidrive.carapp.maps

import android.util.Log
import me.hufman.androidautoidrive.R

/** Katalog manewr Google -> ikona w stylu znaku drogowego (wspolny dla panelu i renderera). */
object ManeuverIcons {
	private const val TAG = "ManeuverIcons"

	fun res(type: String?): Int = when (type) {
		null, "", "straight" -> R.drawable.ic_gmap_straight
		"turn-left" -> R.drawable.ic_gmap_turn_left
		"turn-right" -> R.drawable.ic_gmap_turn_right
		"turn-slight-left" -> R.drawable.ic_gmap_slight_left
		"turn-slight-right" -> R.drawable.ic_gmap_slight_right
		"turn-sharp-left" -> R.drawable.ic_gmap_sharp_left
		"turn-sharp-right" -> R.drawable.ic_gmap_sharp_right
		"uturn-left" -> R.drawable.ic_gmap_uturn_left
		"uturn-right" -> R.drawable.ic_gmap_uturn_right
		"ramp-left" -> R.drawable.ic_gmap_ramp_left
		"ramp-right" -> R.drawable.ic_gmap_ramp_right
		"merge" -> R.drawable.ic_gmap_merge
		"fork-left" -> R.drawable.ic_gmap_fork_left
		"fork-right" -> R.drawable.ic_gmap_fork_right
		"keep-left" -> R.drawable.ic_gmap_keep_left
		"keep-right" -> R.drawable.ic_gmap_keep_right
		"ferry", "ferry-train" -> R.drawable.ic_gmap_ferry
		"destination" -> R.drawable.ic_gmap_destination
		else -> {
			Log.w(TAG, "Nieznany manewr Google: '$type' - fallback na strzalke prosto")
			R.drawable.ic_gmap_straight
		}
	}
}
