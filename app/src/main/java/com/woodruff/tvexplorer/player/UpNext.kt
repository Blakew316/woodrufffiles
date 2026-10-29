package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.tmdb.Tmdb

/**
 * What comes after an episode: the next one in the player's queue, or at the end of a season the
 * first episode of the next one (the queue only ever holds one season folder).
 */
object UpNext {
    /**
     * The queue position right after [key]'s own; null when it's the last or isn't queued. Not the
     * player's "next": by the time an episode counts as finished the player may have moved on.
     */
    fun indexAfter(queue: List<String>, key: String): Int? =
        queue.indexOf(key).takeIf { it >= 0 && it < queue.lastIndex }?.plus(1)

    /**
     * The season an episode is from: the number of its folder among the show's [showItems]
     * ("Season 2"), else the one in its own name ("S02E10"); null when neither says.
     */
    fun seasonOf(showItems: List<DriveFile>, folderId: String?, episodeName: String): Int? =
        showItems.firstOrNull { it.isFolder && it.id == folderId }?.let { Tmdb.seasonNumber(it.name) }
            ?: Tmdb.episodeNumber(episodeName, null)?.first

    /** The show's season folder after [season]: the lowest numbered one above it (Specials never come next). */
    fun nextSeason(showItems: List<DriveFile>, season: Int): DriveFile? =
        showItems.filter { it.isFolder }
            .mapNotNull { f -> Tmdb.seasonNumber(f.name)?.takeIf { it > season }?.let { f to it } }
            .minByOrNull { it.second }?.first

    /**
     * A season's first episode: the lowest episode number among its videos ([season] is the
     * folder's, for names like "Episode 1"), else the first one named like an episode, in Drive's
     * name order. Null when nothing looks like an episode (extras, a trailer).
     */
    fun firstEpisode(seasonItems: List<DriveFile>, season: Int?): DriveFile? {
        val videos = seasonItems.filter { it.kind == FileKind.VIDEO }
        return videos.mapNotNull { f -> Tmdb.episodeNumber(f.name, season)?.let { f to it } }
            .minWithOrNull(compareBy<Pair<DriveFile, Pair<Int, Int>>>({ it.second.first }, { it.second.second }))?.first
            ?: videos.firstOrNull { ShowPrefs.isEpisode(it.name) }
    }
}
