package com.woodruff.tvexplorer.player

import android.content.Context

/**
 * Titles watched to the end (media keys), for the ✓ on posters and episodes. Finishing something
 * drops it from Continue watching; this remembers that it was seen. Kept in memory after the
 * first read, since tiles ask on every draw.
 */
object Watched {
    private const val PREFS = "watched"
    private const val KEY = "keys"
    @Volatile private var keys: Set<String>? = null

    private fun load(c: Context): Set<String> = keys ?: c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY, emptySet()).orEmpty().toSet().also { keys = it }

    fun isWatched(c: Context, key: String): Boolean = key in load(c)

    fun mark(c: Context, key: String) {
        val now = load(c)
        if (key in now) return
        // Newest few thousand are plenty; the set only grows by what's finished.
        val next = (now + key).let { if (it.size > 4000) it.drop(it.size - 4000).toSet() else it }
        keys = next
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }

    fun unmark(c: Context, key: String) {
        val now = load(c)
        if (key !in now) return
        keys = now - key
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, now - key).apply()
    }
}
