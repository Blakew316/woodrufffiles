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
    private const val STOPPED_AT = "keep.stoppedAt"

    /**
     * What a player is made again from: its queue ([keys] and their [titles]), the item playing and
     * where, and when it stopped ([stoppedAt], by the wall clock Continue watching keeps time by; null
     * when it isn't known, as from an older version): see [comeBack].
     */
    data class Kept(
        val keys: List<String>, val titles: List<String>, val index: Int, val positionMs: Long, val playing: Boolean,
        val stoppedAt: Long? = null,
    )

    /** The part of a queue of [size] items kept around [index], at most [max] long. */
    fun window(size: Int, index: Int, max: Int = MAX): IntRange {
        if (size <= max) return 0 until size
        val from = (index - max / 2).coerceIn(0, size - max)
        return from until from + max
    }

    /**
     * Keeps the queue ([keys], [titles]), the item at [index], where it was and when the player stopped
     * on it ([stoppedAt]) in [out]. Nothing for an empty queue.
     */
    fun save(out: Bundle, keys: List<String>, titles: List<String>, index: Int, positionMs: Long, playing: Boolean, stoppedAt: Long? = null) {
        if (keys.isEmpty()) return
        val at = index.coerceIn(0, keys.lastIndex)
        val kept = window(keys.size, at)
        out.putStringArrayList(KEYS, ArrayList(keys.slice(kept)))
        out.putStringArrayList(TITLES, ArrayList(kept.map { titles.getOrNull(it).orEmpty() }))
        out.putInt(INDEX, at - kept.first)
        out.putLong(POSITION, positionMs.coerceAtLeast(0))
        out.putBoolean(PLAYING, playing)
        stoppedAt?.let { out.putLong(STOPPED_AT, it) }
    }

    /** What [saved] kept (see [save]); null when it kept nothing, as on a first opening. */
    fun restore(saved: Bundle?): Kept? {
        val keys = saved?.getStringArrayList(KEYS)?.takeIf { it.isNotEmpty() } ?: return null
        val titles = saved.getStringArrayList(TITLES).orEmpty()
        return Kept(
            keys.toList(), keys.indices.map { titles.getOrNull(it).orEmpty() },
            saved.getInt(INDEX).coerceIn(0, keys.lastIndex), saved.getLong(POSITION).coerceAtLeast(0), saved.getBoolean(PLAYING, true),
            saved.getLong(STOPPED_AT, 0L).takeIf { it > 0 },
        )
    }

    /** How a film the player stopped on comes back to it ([comeBack]). */
    sealed interface ComeBack {
        /** At the spot it stopped at, as it was: playing or paused. */
        data object AsItWas : ComeBack

        /** At [positionMs]: where the family has got to with it since, on another TV (or in another player on this one). */
        data class At(val positionMs: Long) : ComeBack

        /** Finished since, or taken out of Continue watching: at its spot, paused, so it doesn't play on by itself. */
        data object Finished : ComeBack
    }

    /**
     * How a player coming back to [key] (Home and then the app row, standby, or made again after Android
     * closed Woodruff) puts it back, having stopped on it at [stoppedAt]: as it was, unless the family
     * has moved on with it since, as this TV has their progress now ([recent] and [removals]: its own,
     * and the other TVs' merged in). Newer progress wins, as it does on every TV (FamilyMerge), unless
     * it's only "up next" (no spot to go by); finished since, it stays where it was, paused. What this
     * player saved as it stopped is never newer than [stoppedAt]. Not known when it stopped: as it was.
     */
    fun comeBack(key: String, stoppedAt: Long?, recent: List<RecentItem>, removals: Map<String, Long>): ComeBack {
        if (stoppedAt == null) return ComeBack.AsItWas
        val entry = recent.firstOrNull { it.key == key }?.takeIf { it.updatedAt > stoppedAt }
        val removed = removals[key]?.takeIf { it > stoppedAt }
        return when {
            removed != null && (entry == null || removed >= entry.updatedAt) -> ComeBack.Finished
            entry == null || entry.upNext -> ComeBack.AsItWas
            entry.progress >= RecentStore.FINISHED -> ComeBack.Finished
            else -> ComeBack.At(entry.positionMs)
        }
    }

    /**
     * How far a film can be from the spot it started at without anyone having moved it: a little past
     * where a seek may land to start on a clean frame ([SeekAim.SNAP_MS]).
     */
    const val MOVED_MS = 3_000L

    /**
     * Whether anything was done with a film since its player started on [start] (its key and spot): it
     * played ([played]), or it's another item ([key]) or another spot ([positionMs]) now. One only opened
     * and left again has nothing to keep: its spot saved again would be the family's newest, over where
     * another TV has got to with it since. [start] not known: as if it was.
     */
    fun touched(played: Boolean, start: Pair<String, Long>?, key: String, positionMs: Long): Boolean =
        played || start == null || start.first != key || kotlin.math.abs(positionMs - start.second) > MOVED_MS
}
