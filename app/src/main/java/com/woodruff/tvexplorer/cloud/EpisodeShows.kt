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
 * SharedPreferences, keyed by media key ("drive:<id>"), with the videos found under no entry ([unplaced]).
 */
object EpisodeShows {
    private const val PREFS = "episode_shows"
    /** The videos found to sit under no entry, by media key, and when ([unplaced]). */
    private const val UNPLACED = "episode_unplaced"

    /**
     * How long a video found under no Movies / TV Shows entry (a clip elsewhere in Drive), or gone from Drive, isn't
     * walked up again for: a week. They used to be walked again after every restart (every update), up to four
     * requests each.
     */
    const val UNPLACED_MS = 7 * 24 * 60 * 60_000L

    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map.asStateFlow()
    @Volatile private var nowhere: Map<String, Long> = emptyMap()
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _map.value = prefs(c).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            nowhere = unplacedPrefs(c).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
            loaded = true
        }
    }

    /** Whether [key] was found under no entry within [UNPLACED_MS] of [now], so it isn't walked again yet ([load] first). */
    fun unplaced(key: String, now: Long): Boolean = nowhere[key]?.let { now - it in 0 until UNPLACED_MS } == true

    /** [keys] found under no entry (or gone from Drive) at [at]; the ones found longer ago than [UNPLACED_MS] go. */
    fun rememberUnplaced(c: Context, keys: Collection<String>, at: Long) {
        load(c)
        synchronized(this) {
            val old = nowhere.filterValues { at - it !in 0 until UNPLACED_MS }.keys
            nowhere = nowhere - old + keys.associateWith { at }
            unplacedPrefs(c).edit().apply {
                old.forEach { remove(it) }
                keys.forEach { putLong(it, at) }
            }.apply()
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
        nowhere = emptyMap()
        prefs(c).edit().clear().apply()
        unplacedPrefs(c).edit().clear().apply()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun unplacedPrefs(c: Context) = c.applicationContext.getSharedPreferences(UNPLACED, Context.MODE_PRIVATE)
}
