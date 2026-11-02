package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.tmdb.Tmdb
import org.json.JSONArray
import org.json.JSONObject

/** A video put in Drive: its id, name, the folder it's in (its first parent) and when it was put there. */
data class RecentVideo(val id: String, val name: String, val parent: String?, val created: Long)

/** A folder's name and the folder it's in (null at the top of a drive, or for one shared with you). */
data class FolderFacts(val name: String, val parent: String?)

/**
 * A video put in Drive in the last [NewEpisodes.WINDOW_MS] under one of the library's TV shows, as
 * Drive's query of new videos and a walk up its folders found it.
 */
data class FreshEpisode(
    val id: String,
    val name: String,
    /** Its season folder (the one under the show's folder it's in); null when it's in the show's own folder. */
    val seasonId: String?,
    val seasonName: String?,
    /** When it was put in Drive. */
    val created: Long,
    /** Its season and number, from its name (its season folder's name helping: "Episode 4" in "Season 2"); null when they don't say. */
    val season: Int? = null,
    val number: Int? = null,
) {
    /** Its media key ("drive:<id>"), as Continue watching and the ✓s know it. */
    val key: String get() = MediaRef.Drive(id).key

    /** "S3 E2"; null when its name doesn't say. */
    val code: String? get() = if (season != null && number != null) "S$season E$number" else null
}

/**
 * A show with new episodes (see [NewEpisodes.isNew]): [episodes] in the order they'd be watched
 * (lowest season, then episode; specials and unnumbered ones last), and [at], when the newest of them
 * came (or the show itself, if later), which is where it goes in Recently added. [announced]: the show
 * itself is older than the new-episode window, so its posters say what's new ([caption], or the NEW
 * badge on small ones); a show added whole lately is simply a new poster.
 */
data class NewShow(val showId: String, val episodes: List<FreshEpisode>, val at: Long, val announced: Boolean) {
    init { require(episodes.isNotEmpty()) { "a show with new episodes has at least one" } }

    /** The first new episode to watch: where its title page opens, and what its Play button plays. */
    val first: FreshEpisode = episodes.first()

    /** "New · S3 E2", "New season", "3 new episodes" (see [NewEpisodes.caption]): worked out once, where the show is, not while drawing. */
    val caption: String = NewEpisodes.caption(episodes)
}

/**
 * New episodes of the shows the family already has: the rules, pure so they're unit tested, and the
 * walk that finds which show a new video belongs to. The same words on Home, in TV Shows, in Search
 * and on the phone.
 */
object NewEpisodes {
    /** How long an episode counts as new after it was put in Drive: 30 days. */
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** Watched this far, it's started, so no longer new. */
    const val STARTED = 0.02f

    /** Folders looked up, at most, walking from a video up to its show ("Show/Season 2/Disc 1/x.mkv"). */
    private const val MAX_DEPTH = 3

    /**
     * Whether an episode put in Drive at [created] is new at [now]: within [WINDOW_MS], with no ✓ on any
     * TV ([watched], shared through FamilySync), and not started ([progress] under 2 %).
     */
    fun isNew(created: Long, now: Long, watched: Boolean, progress: Float?): Boolean =
        created > 0 && now - created in 0 until WINDOW_MS && !watched && (progress ?: 0f) < STARTED

    /**
     * What a show's new [episodes] (at least one) are called on its poster: exactly one, "New · S3 E2"
     * ("New special" for season 0, "New episode" when its name doesn't say); two or more all in one
     * season and including its first episode, "New season"; otherwise "3 new episodes" (up to "99 new
     * episodes", then "99+ new episodes").
     */
    fun caption(episodes: List<FreshEpisode>): String {
        val n = episodes.size
        if (n <= 1) {
            val e = episodes.firstOrNull()
            return when {
                e?.season == 0 -> "New special"
                e?.code != null -> "New · ${e.code}"
                else -> "New episode"
            }
        }
        val seasons = episodes.map { it.season }.distinct()
        val one = seasons.singleOrNull()
        if (one != null && one > 0 && episodes.all { it.number != null } && episodes.any { it.number == 1 }) return "New season"
        return if (n > 99) "99+ new episodes" else "$n new episodes"
    }

    /** The order episodes are watched in: by season, then number; specials, then ones whose names don't say, last. */
    val watchingOrder: Comparator<FreshEpisode> = compareBy<FreshEpisode>(
        { if (it.season == null || it.number == null) 2 else if (it.season == 0) 1 else 0 },
        { it.season ?: 0 },
        { it.number ?: 0 },
        { it.created },
    )

    /**
     * The shows with new episodes at [now], by show id: of [fresh] (each show's videos put in Drive
     * lately), the ones still new ([isNew], with [watched] and [progress] by media key), for the shows
     * the library lists ([showAdded]: show id to when the show itself came, [DriveFile.added]).
     */
    fun shows(
        fresh: Map<String, List<FreshEpisode>>, showAdded: Map<String, Long>, now: Long,
        watched: (String) -> Boolean, progress: (String) -> Float?,
    ): Map<String, NewShow> {
        val out = HashMap<String, NewShow>()
        for ((show, eps) in fresh) {
            val added = showAdded[show] ?: continue
            val new = eps.filter { isNew(it.created, now, watched(it.key), progress(it.key)) }.sortedWith(watchingOrder)
            if (new.isEmpty()) continue
            out[show] = NewShow(show, new, maxOf(new.maxOf { it.created }, added), announced = now - added >= WINDOW_MS)
        }
        return out
    }

    /**
     * When [f] counts as added, for Recently added and the grids' "Recently added" sort: when its newest
     * new episode came, for a show with some ([shows]); else when it was put in Drive.
     */
    fun addedAt(f: DriveFile, shows: Map<String, NewShow>): Long = shows[f.id]?.at ?: f.added

    /**
     * Which of [shows] (the TV Shows folders' ids) each of [videos] is an episode of, walking up from
     * its folder: that may be a show's own folder, or a season's in it (or a folder deeper: "Season
     * 2/Disc 1"). [facts] looks a folder up on Drive; [known] keeps what each folder walked turned out
     * to be (null: under no show), so a season's episodes, and the next refresh, share one walk. [stops]
     * are folders known to lead to no show (the libraries' own). A video under no show is left out.
     * Throws what [facts] throws (offline): the walk is tried again next time, keeping what it learned.
     */
    suspend fun placeVideos(
        videos: List<RecentVideo>, shows: Set<String>, stops: Set<String>, known: MutableMap<String, Placement?>,
        facts: suspend (String) -> FolderFacts,
    ): Map<String, List<FreshEpisode>> {
        val out = LinkedHashMap<String, MutableList<FreshEpisode>>()
        for (v in videos) {
            val parent = v.parent ?: continue
            val at = (walk(parent, MAX_DEPTH, shows, stops, known, facts) as? Walk.At)?.placement ?: continue
            val number = Tmdb.episodeNumber(v.name, at.seasonName?.let(Tmdb::seasonNumber))
            out.getOrPut(at.show) { ArrayList() } += FreshEpisode(v.id, v.name, at.seasonId, at.seasonName, v.created, number?.first, number?.second)
        }
        return out
    }

    /** Where a walk up from a folder ended: under a show, under none, or past [MAX_DEPTH] (not known). */
    private sealed interface Walk {
        class At(val placement: Placement) : Walk
        data object Nowhere : Walk
        data object TooDeep : Walk
    }

    private suspend fun walk(
        folder: String, depth: Int, shows: Set<String>, stops: Set<String>, known: MutableMap<String, Placement?>,
        facts: suspend (String) -> FolderFacts,
    ): Walk {
        if (folder in shows) return Walk.At(Placement(folder, null, null))
        if (folder in stops) return Walk.Nowhere
        if (known.containsKey(folder)) return known[folder]?.let { Walk.At(it) } ?: Walk.Nowhere
        if (depth <= 0) return Walk.TooDeep
        val f = facts(folder)
        val up = f.parent
        val found = when {
            up == null -> Walk.Nowhere
            // A season (or the show's other folder) right in the show's: this is the season.
            up in shows -> Walk.At(Placement(up, folder, f.name))
            // Deeper: whatever the folder above is in, this is in too (its season is the one above).
            else -> walk(up, depth - 1, shows, stops, known, facts)
        }
        // Only what's known is kept: a walk cut short may reach a show from closer by another time.
        when (found) {
            is Walk.At -> known[folder] = found.placement
            Walk.Nowhere -> known[folder] = null
            Walk.TooDeep -> Unit
        }
        return found
    }

    /** [fresh] as it's saved, so Home has its new episodes at once after a restart, offline too. Visible for tests. */
    internal fun toJson(fresh: Map<String, List<FreshEpisode>>): String = JSONObject().apply {
        for ((show, eps) in fresh) {
            put(show, JSONArray().apply {
                eps.forEach { e ->
                    put(
                        JSONObject().put("i", e.id).put("n", e.name).put("si", e.seasonId ?: "").put("sn", e.seasonName ?: "")
                            .put("c", e.created).put("s", e.season ?: -1).put("e", e.number ?: -1),
                    )
                }
            })
        }
    }.toString()

    /** Reads what [toJson] saved (nothing, for anything it can't read). Visible for tests. */
    internal fun fromJson(s: String): Map<String, List<FreshEpisode>> = runCatching {
        val o = JSONObject(s)
        o.keys().asSequence().associateWith { show ->
            val arr = o.optJSONArray(show) ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { e ->
                FreshEpisode(
                    e.getString("i"), e.optString("n"), e.optString("si").ifBlank { null }, e.optString("sn").ifBlank { null },
                    e.optLong("c"), e.optInt("s", -1).takeIf { it >= 0 }, e.optInt("e", -1).takeIf { it >= 0 },
                )
            }
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())
}

/** Where a folder sits under TV Shows: in show [show]'s folder, in its season folder [seasonId] ([seasonName]); null season: the show's own folder. */
data class Placement(val show: String, val seasonId: String?, val seasonName: String?)
