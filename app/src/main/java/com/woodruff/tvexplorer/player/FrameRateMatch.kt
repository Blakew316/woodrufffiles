package com.woodruff.tvexplorer.player

import android.content.Context

/** "Match frame rate" (Settings → Appearance): show a 24-frame film at 24 Hz instead of 60. Off by default. */
object FrameRateMatch {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("matchFrameRate", false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("matchFrameRate", on).apply() }
}
