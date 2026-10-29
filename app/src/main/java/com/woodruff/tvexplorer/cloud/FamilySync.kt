package com.woodruff.tvexplorer.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continue watching, ✓s and skip-intro marks shared by every TV signed in to the same Google
 * account, through Drive's app-data folder (hidden; only this app can see it, and it can't touch the
 * family's files). Each TV writes its own small file there and merges the others' in, newest wins.
 */
object FamilySync {
    private const val PREFS = "family_sync"

    private val _updates = MutableStateFlow(0)
    /** Ticks when another TV's progress was merged in: re-read RecentStore and Watched. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "Share progress with other TVs" (Settings), on unless turned off on this TV. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply() }

    /** Something changed (progress, a ✓, an intro mark): send it to the other TVs soon. */
    fun changed(c: Context) {}

    /** Playback stopped: send now, before the TV goes to sleep. */
    fun pushNow(c: Context) {}

    /** Fetch the other TVs' progress (at most every couple of minutes); [updates] ticks when something merged in. */
    fun pullSoon(c: Context) {}

    /** Why sharing isn't working, for Settings; null when it works or is turned off. */
    fun problem(c: Context): String? = null
}
