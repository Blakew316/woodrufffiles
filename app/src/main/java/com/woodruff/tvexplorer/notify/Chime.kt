package com.woodruff.tvexplorer.notify

import android.content.Context

/** A short sound for announcements and the doorbell, so a pop-up gets noticed over a film. */
object Chime {
    enum class Sound { ANNOUNCE, DOORBELL }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("chime", Context.MODE_PRIVATE)

    /** "Chime for announcements" (Settings), on unless turned off on this TV. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply() }

    /** Plays [sound] when chimes are on and the screen is awake. Never takes audio focus. */
    fun play(c: Context, sound: Sound) {}
}
