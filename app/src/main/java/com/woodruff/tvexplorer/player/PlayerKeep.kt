package com.woodruff.tvexplorer.player

import android.os.Bundle

/**
 * What the player was playing and where, kept in its saved state (browsing-motion §3.4). The TV's
 * app row now brings back a film left with Home; if Android closed Woodruff meanwhile to free memory,
 * the player is made again from this rather than from the intent it was first opened with, which
 * started over: the first episode chosen before Up next moved on, or a "Start over" from 0:00.
 *
 * The queue is kept around the item playing, at most [MAX] items: a season or a folder's worth, well
 * inside what a saved state may hold (it crosses to Android in one go, and too much crashes the app).
 */
internal object PlayerKeep {
    /** The most queue items kept: half before the one playing, half after. */
    const val MAX = 100

    private const val KEYS = "keep.keys"
    private const val TITLES = "keep.titles"
    private const val INDEX = "keep.index"
    private const val POSITION = "keep.position"
    private const val PLAYING = "keep.playing"

    /** What a player is made again from: its queue ([keys] and their [titles]), the item playing and where. */
    data class Kept(val keys: List<String>, val titles: List<String>, val index: Int, val positionMs: Long, val playing: Boolean)

    /** The part of a queue of [size] items kept around [index], at most [max] long. */
    fun window(size: Int, index: Int, max: Int = MAX): IntRange {
        if (size <= max) return 0 until size
        val from = (index - max / 2).coerceIn(0, size - max)
        return from until from + max
    }

    /** Keeps the queue ([keys], [titles]), the item at [index] and where it was in [out]. Nothing for an empty queue. */
    fun save(out: Bundle, keys: List<String>, titles: List<String>, index: Int, positionMs: Long, playing: Boolean) {
        if (keys.isEmpty()) return
        val at = index.coerceIn(0, keys.lastIndex)
        val kept = window(keys.size, at)
        out.putStringArrayList(KEYS, ArrayList(keys.slice(kept)))
        out.putStringArrayList(TITLES, ArrayList(kept.map { titles.getOrNull(it).orEmpty() }))
        out.putInt(INDEX, at - kept.first)
        out.putLong(POSITION, positionMs.coerceAtLeast(0))
        out.putBoolean(PLAYING, playing)
    }

    /** What [saved] kept (see [save]); null when it kept nothing, as on a first opening. */
    fun restore(saved: Bundle?): Kept? {
        val keys = saved?.getStringArrayList(KEYS)?.takeIf { it.isNotEmpty() } ?: return null
        val titles = saved.getStringArrayList(TITLES).orEmpty()
        return Kept(
            keys.toList(), keys.indices.map { titles.getOrNull(it).orEmpty() },
            saved.getInt(INDEX).coerceIn(0, keys.lastIndex), saved.getLong(POSITION).coerceAtLeast(0), saved.getBoolean(PLAYING, true),
        )
    }
}
