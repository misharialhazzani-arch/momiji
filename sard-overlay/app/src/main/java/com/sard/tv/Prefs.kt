package com.sard.tv

import android.content.Context
import kotlin.random.Random

/** What the TV remembers: the pairing code and how the subtitles look */
class Prefs(context: Context)
{
	private val sp = context.getSharedPreferences("sard", Context.MODE_PRIVATE)

	/** The 4-digit code the phone must send. Made once, so the TV stays paired. */
	val pairingCode: String
		get() = sp.getString("code", null) ?: newPairingCode()

	fun newPairingCode(): String
	{
		val code = Random.nextInt(1000, 10000).toString()
		sp.edit().putString("code", code).apply()
		return code
	}

	var textSizeSp: Int
		get() = sp.getInt("text_size", 30)
		set(v) = sp.edit().putInt("text_size", v.coerceIn(14, 72)).apply()

	/** Distance of the subtitle box from the bottom of the screen, in dp */
	var bottomMarginDp: Int
		get() = sp.getInt("bottom_margin", 48)
		set(v) = sp.edit().putInt("bottom_margin", v.coerceIn(0, 600)).apply()

	/** Background darkness, 0–100 */
	var backgroundOpacity: Int
		get() = sp.getInt("bg_opacity", 80)
		set(v) = sp.edit().putInt("bg_opacity", v.coerceIn(0, 100)).apply()

	var textColor: Int
		get() = sp.getInt("text_color", 0xFFFFFFFF.toInt())
		set(v) = sp.edit().putInt("text_color", v).apply()
}
