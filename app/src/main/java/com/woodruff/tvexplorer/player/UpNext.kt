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
     * The picture Up next shows for Drive episode [f]: the one its listing has, TMDB's still or else
     * Drive's own frame of the video, as its show's page shows it (TitleDetail's EpisodeCard). Never
     * one read out of the episode itself: that was its header, its index and a frame from Google
     * Drive, for a picture, while the player loaded that same start. Null when the listing has none.
     */
    fun art(f: DriveFile): String? = f.posterUrl ?: f.thumbnailLink?.let(com.woodruff.tvexplorer.cloud.DriveApi::big)

    /**
     * Where Up next's picture of [key] comes from: a Drive episode's from what its listing says
     * ([known]: its picture, [art]), or none; a file on this TV, a frame from it (read from the TV, so
     * it costs nothing); a link from the phone, none.
     */
    fun artFor(key: String, known: (String) -> String?): UpNextArt = when (MediaRef.parse(key)) {
        is MediaRef.Drive -> known(key)?.let { UpNextArt.Picture(it) } ?: UpNextArt.None
        is MediaRef.Local -> UpNextArt.Frame
        is MediaRef.Remote -> UpNextArt.None
    }

    /**
     * Whether "Play next" in the player's menu counts the episode as watched: in its last 5 % or 40 s
     * (the credits, roughly, where Next Episode shows), as the TV's own Next Episode does. Before that
     * it's the remote's Next: the place is kept, and it stays in Continue watching.
     */
    fun nextCountsAsWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && (durationMs - positionMs <= NEXT_EPISODE_LEAD_MS || positionMs >= durationMs * 0.95)

    /** The end of an episode where Next Episode shows: its credits, roughly. */
    const val NEXT_EPISODE_LEAD_MS = 40_000L

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

/** Where Up next's picture comes from ([UpNext.artFor]). */
sealed interface UpNextArt {
    /** A picture at this address (the listing's). */
    data class Picture(val url: String) : UpNextArt

    /** A frame read from the file itself: one on this TV. */
    data object Frame : UpNextArt

    /** Its plain stand-in. */
    data object None : UpNextArt
}
