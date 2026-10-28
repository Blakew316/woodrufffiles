package com.woodruff.tvexplorer.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which Movies / TV Shows entry (by Drive id) each played video belongs to: an episode's show, or
 * a film's own folder. Home's Continue watching uses it for the right poster even when a file's
 * name doesn't say ("S01E01 - Pilot.mkv"). Learned when something is played from its title page,
 * or worked out once from the file's folders on Drive ([DriveViewModel.resolveShows]). Kept in
 * SharedPreferences, keyed by media key ("drive:<id>").
 */
object EpisodeShows {
    private const val PREFS = "episode_shows"
    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map.asStateFlow()
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _map.value = prefs(c).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            loaded = true
        }
    }

    /** [entries]: media key -> library entry id. */
    fun remember(c: Context, entries: Map<String, String>) {
        load(c)
        synchronized(this) {
            val fresh = entries.filter { (k, v) -> _map.value[k] != v }
            if (fresh.isEmpty()) return
            _map.value = _map.value + fresh
            prefs(c).edit().apply { fresh.forEach { (k, v) -> putString(k, v) } }.apply()
        }
    }

    fun clear(c: Context) = synchronized(this) {
        _map.value = emptyMap()
        prefs(c).edit().clear().apply()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
