package com.woodruff.tvexplorer.server

import android.content.Context
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.ui.screens.latestOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Where something started from the phone begins, on the TV that plays it (this one, or a linked TV
 * the hub passes it to). A resume ("resume": the phone's Resume and Play) starts where anyone in the
 * family last stopped, as Home's Continue watching tile does: the other TVs' latest progress is
 * fetched first (half a second at most), and the player then reads the spot itself. The spot the
 * phone was shown can be minutes old (the hub may not have heard from the TV it was watched on since),
 * and sent on as given it beat that TV's own, newer one. A show's "go on" ("latest": a Continue
 * watching tile, a show's Resume) plays the show's newest episode now, which another TV may have
 * moved on to. Start Over, and a spot the phone gives without either, start where it says.
 */
internal object WatchStart {
    /** What to start: [positionMs] null means where the family last stopped, as the player reads it. */
    data class Start(val key: String, val title: String, val positionMs: Long?)

    /** Whether [b] leaves the spot to the TV that plays it. */
    fun resumes(b: JSONObject): Boolean = b.optBoolean("resume")

    /** [b]'s "go on" asks for its show's newest episode. */
    fun goesOn(b: JSONObject): Boolean = b.optBoolean("latest")

    /**
     * What [b] starts once the other TVs' latest progress is in: [watching] is this TV's Continue
     * watching then (asked for only for a show's "go on"), [knows] whether this TV has a spot for a
     * video, or knows it was finished. One it knows nothing of (sharing is off, or it was never
     * played here) starts from the spot the phone was shown. [title]: the title it was sent with.
     */
    fun pick(b: JSONObject, title: String, watching: () -> List<RecentItem>, knows: (String) -> Boolean): Start {
        val key = b.optString("key")
        val given = b.optLong("position").coerceAtLeast(0)
        if (!resumes(b)) return Start(key, title, given)
        val now = if (goesOn(b)) latestOf(RecentItem(key, title, 0, 0, 0), watching()) else null
        if (now != null && now.key != key) return Start(now.key, now.title, null)
        return Start(key, title, if (knows(key)) null else given)
    }

    /** [pick] on this TV, after waiting (at most [FamilySync.RESUME_WAIT_MS]) for the other TVs' latest when it resumes. The request's own thread. */
    fun of(c: Context, b: JSONObject, title: String): Start {
        if (resumes(b)) runBlocking { FamilySync.awaitFresh(c) }
        val store = RecentStore(c)
        return pick(b, title, { store.continueWatching() }) { k -> store.all().any { it.key == k } || k in store.removals() }
    }

    /**
     * [of] with its spot worked out here: Watch together starts every TV from the same one, as the
     * TV's own Watch together does (where the family last stopped; an up-next episode from its start).
     */
    fun spotted(c: Context, b: JSONObject, title: String): Start {
        val s = of(c, b, title)
        return if (s.positionMs != null) s else s.copy(positionMs = RecentStore(c).resumePosition(s.key))
    }
}
