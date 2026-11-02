package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.NewEpisodes
import com.woodruff.tvexplorer.cloud.NewShow
import com.woodruff.tvexplorer.cloud.episodeCodeOf
import com.woodruff.tvexplorer.cloud.episodePlayTitle
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.EpisodeLabel
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.icons.Sym
import com.woodruff.tvexplorer.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The title menu (title-access spec): wherever a film or a show appears — Home, Streaming's banner, rows and
 * grid, Search, a title page's rows — holding OK (or Menu) offers the same short menu, and Play/Pause runs its
 * first row without opening it. Its first row is always what the title page's main button would do (the same
 * words, worked out by the same rules: [mainPlayLabel], [showMainPlay]), so the three can never disagree.
 * Nothing is fetched from Drive to build it: what isn't known from what's in memory (or saved, for a film's own
 * folder) opens the title page instead, which fetches.
 */

/** Where a title's menu is opened: it decides a few of its rows (title-access §3.3). */
internal enum class MenuPlace {
    /** Home: a Recently added poster (OK opens its page). */
    HOME_ADDED,

    /** Home: a Continue watching tile (OK plays it, so its menu offers the page: Episodes / Details). */
    HOME_RESUME,

    /** Streaming: the banner, the rows and the grid. */
    LIBRARY,

    /** Streaming: its Continue watching row. */
    CONTINUE_ROW,

    /** Search's results. */
    SEARCH,

    /** A title page's collection and More like this rows. */
    TITLE_PAGE,
    ;

    /** On Home: nothing is downloaded from there. */
    val home: Boolean get() = this == HOME_ADDED || this == HOME_RESUME

    /** A Continue watching tile: it can be taken off Continue watching. */
    val continuing: Boolean get() = this == HOME_RESUME || this == CONTINUE_ROW
}

/**
 * A title whose menu is asked for: [file], a library entry (a film, or the folder of a show or of a film
 * kept in its own folder) in [place] (Movies or TV Shows), held in [where]. [tile]: the Continue watching
 * item it stands for (Home's tiles, Streaming's Continue watching row). A Home tile's entry may not be
 * known ([file] null): it's looked up in the libraries as the menu is built.
 */
internal data class MenuFor(val place: DrivePlace?, val file: DriveFile?, val where: MenuPlace, val tile: RecentItem? = null)

/** Something played a minute or less in starts again rather than offering to resume ("Play", not "Resume from 0m"). */
internal const val RESUME_FROM_MS = 60_000L

/** How long the menu waits for what it needs (read off the main thread, usually under 30 ms) before it opens without it. */
internal const val MENU_WAIT_MS = 150L

/**
 * What a title's main button plays: the title page's first button, its menu's first row and Play/Pause on
 * it, one answer (title-access §4.3).
 */
internal sealed interface MainPlay {
    /** A film: [file] itself, or the film found in its own folder ([folder]), from [resumeMs] (where it stopped; 0 for the start). */
    data class Film(val file: DriveFile, val resumeMs: Long, val folder: DriveFile? = null) : MainPlay

    /** A show's episode to go on with ([item]: in progress, or up next after one just finished), with its [season] when that's listed in memory. */
    data class GoOn(val item: RecentItem, val season: Season? = null) : MainPlay

    /** A show's first new episode, nothing being in progress (episodes spec §3.5), with its [season] when listed. */
    data class New(val episode: FreshEpisode, val season: Season? = null) : MainPlay

    /** A show's first episode, [season] its season's videos in order. */
    data class First(val episode: DriveFile, val season: List<DriveFile>) : MainPlay
}

/** An episode as its season lists it ([file]), and that season's videos in order ([videos]), to queue after it. */
internal data class Season(val file: DriveFile, val videos: List<DriveFile>)

/**
 * What a title's main button says for [play]: "Resume from 42m" / "Play" (a film), "Resume S2 E4" (an episode
 * in progress), "Play S2 E5" (up next, or a show's first new episode or first episode). The title page's
 * button and the menu's first row both say it with this.
 */
internal fun mainPlayLabel(play: MainPlay): String = when (play) {
    is MainPlay.Film -> if (play.resumeMs > RESUME_FROM_MS) "Resume from ${Format.duration(play.resumeMs)}" else "Play"
    is MainPlay.GoOn -> goOnLabel(play.item)
    is MainPlay.New -> "Play " + (play.episode.code ?: "new episode")
    is MainPlay.First -> "Play " + (episodeCodeOf(play.episode.episode, play.episode.name) ?: "first episode")
}

/** "Resume S2 E4" for an episode part-way, "Play S2 E5" for one up next. */
private fun goOnLabel(item: RecentItem): String {
    val code = episodeCode(item.title)
    return if (item.upNext) code?.let { "Play $it" } ?: "Play next episode" else code?.let { "Resume $it" } ?: "Resume"
}

/**
 * A show's main play, as its title page decides it (episodes spec §3.5): the episode to go on with ([goOn]);
 * else its first new episode ([firstNew]), unless that's its first episode anyway; else its first episode
 * ([first], of [firstSeason]). Null when none is known without listing its folders.
 */
internal fun showMainPlay(goOn: MainPlay.GoOn?, firstNew: MainPlay.New?, first: DriveFile?, firstSeason: List<DriveFile>): MainPlay? = when {
    goOn != null -> goOn
    firstNew != null && firstNew.episode.id != first?.id -> firstNew
    first != null -> MainPlay.First(first, firstSeason)
    else -> null
}

/** A row of the title menu: what it does. Its words and symbol are [label] and [icon]. */
internal sealed interface TitleAction {
    /** Row 1: [play] (the main button's). */
    data class Play(val play: MainPlay) : TitleAction

    /** Row 2: a film from its start ("Start over"), or a show's first episode from its start ("Play from the start"). */
    data class Restart(val play: MainPlay) : TitleAction

    /** On Home's Continue watching, row 1: the tile as OK plays it; [fromStart] (row 2, a film): from its beginning. */
    data class PlayTile(val tile: RecentItem, val episode: Boolean, val fromStart: Boolean = false) : TitleAction

    /** Row 1 when nothing playable is known without listing, or row 3 on Home's tiles: the title's page ("Episodes" / "Details"). */
    data class Page(val show: Boolean) : TitleAction

    /** Row 4: [key] (a Google Drive video) on every linked TV too, from where it is ([positionMs]; a tile's latest spot for [tile]). */
    data class Together(val key: String, val title: String, val positionMs: Long, val tile: RecentItem? = null) : TitleAction

    /** Row 5: [key]'s ✓ ([watched]: it has one now, so the row takes it off). */
    data class Mark(val key: String, val watched: Boolean) : TitleAction

    /** Row 6: a film that's a file of its own, saved on this TV. */
    data class Download(val file: DriveFile) : TitleAction

    /** Row 7: off Continue watching ([tile]'s show, every episode of it). */
    data class Remove(val tile: RecentItem) : TitleAction
}

/** A row's words. */
internal fun TitleAction.label(): String = when (this) {
    is TitleAction.Play -> mainPlayLabel(play)
    is TitleAction.Restart -> if (play is MainPlay.Film) "Start over" else "Play from the start"
    is TitleAction.PlayTile -> when {
        fromStart -> "Start over"
        episode -> goOnLabel(tile)
        else -> mainPlayLabel(MainPlay.Film(DriveFile(tile.key, tile.title, "", 0, 0, null), tile.positionMs))
    }
    is TitleAction.Page -> if (show) "Episodes" else "Details"
    is TitleAction.Together -> "Watch together on all TVs"
    is TitleAction.Mark -> if (watched) "Mark as unwatched" else "Mark as watched"
    is TitleAction.Download -> "Download to this TV"
    is TitleAction.Remove -> "Remove from Continue watching"
}

/** A row's symbol. */
internal fun TitleAction.icon(): ImageVector = when (this) {
    is TitleAction.Play -> Sym.PlayArrow
    is TitleAction.PlayTile -> if (fromStart) Sym.Replay else Sym.PlayArrow
    is TitleAction.Restart -> Sym.Replay
    is TitleAction.Page -> if (show) Sym.Tv else Sym.Info
    is TitleAction.Together -> Sym.Groups
    is TitleAction.Mark -> if (watched) Sym.RadioButtonUnchecked else Sym.CheckCircle
    is TitleAction.Download -> Sym.Download
    is TitleAction.Remove -> Sym.Delete
}

/**
 * A title's menu: its title (one line), a line about it, and its rows (six at most), the first focused.
 * [place] and [entry]: the library entry it's for (found for a Home tile that didn't know it).
 */
internal data class TitleMenuModel(
    val title: String,
    val subtitle: String?,
    val rows: List<TitleAction>,
    val place: DrivePlace? = null,
    val entry: DriveFile? = null,
) {
    /** What Play/Pause on the title does: the first row. */
    val first: TitleAction? get() = rows.firstOrNull()
}

/**
 * Everything a title's menu depends on, read off the main thread ([readTitleFacts]); [titleMenu] turns it into
 * the menu without reading anything itself.
 */
internal class TitleFacts(
    val target: MenuFor,
    /** The library entry, found for a Home tile that didn't know it; null when it isn't in the libraries. */
    val entry: DriveFile?,
    val place: DrivePlace?,
    /** What's been played (RecentStore, newest first). */
    val recent: List<RecentItem>,
    /** Media key to library entry ([EpisodeShows]). */
    val shows: Map<String, String>,
    /** The entry's folder as listed before (in memory; for a film's own folder, saved on disk too); null when it hasn't been. */
    val inside: List<DriveFile>?,
    /** A folder's listing in memory, by id (a show's seasons). */
    val listing: (String) -> List<DriveFile>?,
    val watched: (String) -> Boolean,
    /** The show's new episodes, if it has any. */
    val news: NewShow?,
    val now: Long,
    /** Linked TVs exist. */
    val linked: Boolean,
)

/** The title menu for [f] (title-access §3.3): pure, so its rules are unit tested. */
internal fun titleMenu(f: TitleFacts): TitleMenuModel {
    val where = f.target.where
    val tile = f.target.tile?.takeIf { where.continuing }
    val entry = f.entry
    val isShow = entry != null && entry.isFolder && f.place == DrivePlace.TvShows
    val main: MainPlay? = when {
        entry == null -> null
        !entry.isFolder -> MainPlay.Film(entry, resumeAt(f.recent, MediaRef.Drive(entry.id).key))
        isShow -> showPlay(entry, f)
        else -> f.inside?.let(::folderFilm)?.let { MainPlay.Film(it, resumeAt(f.recent, MediaRef.Drive(it.id).key), folder = entry) }
    }
    val first = (main as? MainPlay.First) ?: if (isShow) firstEpisode(f)?.let { (ep, season) -> MainPlay.First(ep, season) } else null
    val homeTile = tile?.takeIf { where == MenuPlace.HOME_RESUME }
    val tileEpisode = homeTile != null && (isShow || episodeCode(homeTile.title) != null)
    val rows = buildList {
        // 1: what the page's main button would do; on Home's tiles, what OK does there.
        add(
            when {
                homeTile != null -> TitleAction.PlayTile(homeTile, tileEpisode)
                main != null -> TitleAction.Play(main)
                else -> TitleAction.Page(show = isShow)
            },
        )
        // 2: from the start, beside an offer to go on.
        when {
            homeTile != null && !tileEpisode -> if (homeTile.positionMs > RESUME_FROM_MS) add(TitleAction.PlayTile(homeTile, episode = false, fromStart = true))
            homeTile != null -> first?.let { add(TitleAction.Restart(it)) }
            main is MainPlay.Film -> if (main.resumeMs > RESUME_FROM_MS) add(TitleAction.Restart(main.copy(resumeMs = 0)))
            main is MainPlay.GoOn || main is MainPlay.New -> first?.let { add(TitleAction.Restart(it)) }
        }
        // 3: where OK doesn't open the page (Home's tiles), the page.
        if (homeTile != null && entry != null) add(TitleAction.Page(show = isShow))
        // 4: on every linked TV, when row 1 plays a Google Drive video.
        if (f.linked) together(first(), main, entry)?.let { add(it) }
        // 5: a film's ✓, or the episode a Continue watching tile stands for.
        val markKey = when {
            tile != null -> tile.key
            main is MainPlay.Film -> MediaRef.Drive(main.file.id).key
            else -> null
        }
        if (markKey != null) add(TitleAction.Mark(markKey, f.watched(markKey)))
        // 6: a film that's a file of its own, from anywhere but Home.
        if (entry != null && !entry.isFolder && entry.kind == FileKind.VIDEO && !where.home) add(TitleAction.Download(entry))
        // 7: Continue watching's own tiles.
        if (tile != null) add(TitleAction.Remove(tile))
    }
    return TitleMenuModel(menuTitle(entry, f.target.tile), menuSubtitle(entry, isShow, main, tile, f), rows, f.place, entry)
}

/** Row 4 for a menu whose first row is [first]: the Google Drive video it plays, from where it starts; null for anything else. */
private fun together(first: TitleAction, main: MainPlay?, entry: DriveFile?): TitleAction.Together? {
    val show = entry?.let { Library.info(it).title }.orEmpty()
    val t = when (first) {
        is TitleAction.PlayTile -> TitleAction.Together(first.tile.key, first.tile.title, if (first.tile.upNext) 0 else first.tile.positionMs, tile = first.tile)
        is TitleAction.Play -> when (val p = main) {
            is MainPlay.Film -> TitleAction.Together(MediaRef.Drive(p.file.id).key, if (p.folder != null) episodePlayTitle(show, p.file) else p.file.name, p.resumeMs)
            is MainPlay.GoOn -> TitleAction.Together(p.item.key, p.item.title, if (p.item.upNext) 0 else p.item.positionMs)
            is MainPlay.New -> TitleAction.Together(p.episode.key, newEpisodeTitle(show, p), 0)
            is MainPlay.First -> TitleAction.Together(MediaRef.Drive(p.episode.id).key, episodePlayTitle(show, p.episode), 0)
            null -> null
        }
        else -> null
    }
    return t?.takeIf { MediaRef.parse(it.key) is MediaRef.Drive }
}

/** The player's title for a show's new episode: from its season's listing when that has it (its real name, once TMDB's known), as the page does. */
private fun newEpisodeTitle(show: String, p: MainPlay.New): String =
    p.season?.file?.let { episodePlayTitle(show, it) } ?: p.episode.name

/** The menu's title: the title as the library names it ("Midnight Harbor (2024)"), never a file name. */
private fun menuTitle(entry: DriveFile?, tile: RecentItem?): String = when {
    entry != null -> Library.label(entry)
    tile != null -> EpisodeLabel.of(tile.title).let { l -> l.eyebrow?.substringBefore(" · ")?.takeIf { it.isNotBlank() } ?: Format.title(l.title) }
    else -> ""
}

/**
 * The line under the menu's title: how far through it is ("45% watched", "S2 E4 · 45% watched"), what's next
 * ("Next · S2 E5"), what's new ("New · S3 E2"), else what it is ("Movie", "TV show").
 */
private fun menuSubtitle(entry: DriveFile?, isShow: Boolean, main: MainPlay?, tile: RecentItem?, f: TitleFacts): String {
    val episodeTile = tile?.takeIf { isShow || episodeCode(it.title) != null }
    val going = (main as? MainPlay.GoOn)?.item ?: episodeTile
    if (going != null) return progressWords(going)
    val film = (main as? MainPlay.Film)?.let { m -> f.recent.firstOrNull { it.key == MediaRef.Drive(m.file.id).key } } ?: tile
    if (film != null && inProgress(film)) return percentWatched(film)
    if (isShow) f.news?.takeIf { it.announced }?.let { return it.caption }
    val show = isShow || (entry == null && f.target.tile?.let { episodeCode(it.title) } != null)
    return if (show) "TV show" else "Movie"
}

/** "S2 E4 · 45% watched" for an episode part-way, "Next · S2 E5" for one up next. */
internal fun progressWords(item: RecentItem): String =
    if (item.upNext) upNextCaption(item.title)
    else episodeCode(item.title)?.let { "$it · ${percentWatched(item)}" } ?: percentWatched(item)

/** "45% watched". */
internal fun percentWatched(item: RecentItem): String = "${(item.progress * 100).toInt()}% watched"

/** Started and not finished, as Continue watching counts it. */
private fun inProgress(item: RecentItem): Boolean = item.positionMs > 10_000 && item.progress < 0.95f

/**
 * A show's main play as its page would work it out from what's in memory ([f]): the episode to go on with
 * (matched by its name, or known to sit under the show), else its first new episode, else its first episode.
 */
private fun showPlay(show: DriveFile, f: TitleFacts): MainPlay? {
    val showKey = matchKeyOf(show.name)
    val listed = listedVideos(f).mapTo(HashSet()) { MediaRef.Drive(it.id).key }
    val going = showResume(f.recent) { r -> f.shows[r.key] == show.id || r.key in listed || matchKeyOf(r.title) == showKey }
    val goOn = going?.let { item -> MainPlay.GoOn(item, seasonOf(f) { MediaRef.Drive(it.id).key == item.key }) }
    val progress = f.recent.associate { it.key to it.progress }
    val firstNew = f.news?.episodes
        ?.filter { NewEpisodes.isNew(it.created, f.now, f.watched(it.key), progress[it.key]) }
        ?.minWithOrNull(NewEpisodes.watchingOrder)
        ?.let { e -> MainPlay.New(e, seasonOf(f) { it.id == e.id }) }
    val (first, firstSeason) = firstEpisode(f) ?: (null to emptyList())
    return showMainPlay(goOn, firstNew, first, firstSeason)
}

/** The show's seasons as its page tabs them: its season folders, or its own folder (null) when the episodes sit in it. */
private fun seasonsOf(f: TitleFacts): List<Pair<String, DriveFile?>> {
    val inside = f.inside ?: return emptyList()
    val folders = inside.filter { it.isFolder }.map<DriveFile, Pair<String, DriveFile?>> { it.name to it }
    return folders.ifEmpty { if (inside.any { it.kind == FileKind.VIDEO }) listOf("Episodes" to null) else emptyList() }
}

/** A season's videos as listed in memory ([folder] null: the show's own folder). */
private fun videosOf(f: TitleFacts, folder: DriveFile?): List<DriveFile>? =
    (if (folder == null) f.inside else f.listing(folder.id))?.filter { it.kind == FileKind.VIDEO }

/** Every episode of the show listed in memory. */
private fun listedVideos(f: TitleFacts): List<DriveFile> = seasonsOf(f).flatMap { (_, folder) -> videosOf(f, folder).orEmpty() }

/** The season holding the episode [which] picks, as listed in memory. */
private fun seasonOf(f: TitleFacts, which: (DriveFile) -> Boolean): Season? {
    for ((_, folder) in seasonsOf(f)) {
        val videos = videosOf(f, folder) ?: continue
        videos.firstOrNull(which)?.let { return Season(it, videos) }
    }
    return null
}

/** The show's first episode and its season, when its first season is listed in memory (found as its page finds it: "Specials" don't count). */
private fun firstEpisode(f: TitleFacts): Pair<DriveFile, List<DriveFile>>? {
    val seasons = seasonsOf(f)
    if (seasons.isEmpty()) return null
    val at = seasons.withIndex()
        .filter { (_, s) -> (Tmdb.seasonNumber(s.first) ?: 1) >= 1 }
        .minByOrNull { (_, s) -> Tmdb.seasonNumber(s.first) ?: Int.MAX_VALUE }?.index ?: 0
    val videos = videosOf(f, seasons[at].second) ?: return null
    return videos.firstOrNull()?.let { it to videos }
}

/**
 * The menu as it opens when what it needs isn't read within [MENU_WAIT_MS]: only what [target] itself says
 * (the page in place of playing, and the rows that need nothing read). Rows never change once it's open.
 */
internal fun quickTitleMenu(target: MenuFor, news: NewShow?): TitleMenuModel {
    val entry = target.file
    val tile = target.tile?.takeIf { target.where.continuing }
    val isShow = entry != null && entry.isFolder && target.place == DrivePlace.TvShows
    val homeTile = tile?.takeIf { target.where == MenuPlace.HOME_RESUME }
    val rows = buildList {
        if (homeTile != null) {
            add(TitleAction.PlayTile(homeTile, episode = isShow || episodeCode(homeTile.title) != null))
            if (entry != null) add(TitleAction.Page(show = isShow))
        } else add(TitleAction.Page(show = isShow))
        if (entry != null && !entry.isFolder && entry.kind == FileKind.VIDEO && !target.where.home) add(TitleAction.Download(entry))
        if (tile != null) add(TitleAction.Remove(tile))
    }
    val subtitle = when {
        tile != null && (isShow || episodeCode(tile.title) != null) -> progressWords(tile)
        tile != null && inProgress(tile) -> percentWatched(tile)
        isShow && news?.announced == true -> news.caption
        isShow -> "TV show"
        else -> "Movie"
    }
    return TitleMenuModel(menuTitle(entry, target.tile), subtitle, rows, target.place, entry)
}

/**
 * Reads what [target]'s menu depends on: what's been played, the shows' episodes, the folders listed in
 * memory (a film's own folder from disk too), the ✓s and the linked TVs. Never the network. Background thread.
 */
internal fun readTitleFacts(c: Context, target: MenuFor, libraries: Map<DrivePlace, List<DriveFile>>, news: Map<String, NewShow>): TitleFacts {
    val recent = RecentStore(c).all()
    EpisodeShows.load(c)
    val shows = EpisodeShows.map.value
    TvLinks.load(c)
    // A Home tile whose entry wasn't known: the library entry it's found under, as Continue watching finds it.
    val found = when {
        target.file != null -> target.place to target.file
        target.tile != null -> entryOf(target.tile, libraries, shows) ?: (null to null)
        else -> null to null
    }
    val (place, entry) = found
    val inside = entry?.takeIf { it.isFolder }?.let { e ->
        FolderCache.inMemory(e.id) ?: if (place != DrivePlace.TvShows) FolderCache.peek(c, e.id) else null
    }
    return TitleFacts(
        target, entry, place, recent, shows, inside, FolderCache::inMemory, { Watched.isWatched(c, it) },
        entry?.let { news[it.id] }, System.currentTimeMillis(), TvLinks.linked.value.isNotEmpty(),
    )
}

/** The library entry (and its library) a Continue watching [tile] stands for: an episode's show, a film. */
internal fun entryOf(tile: RecentItem, libraries: Map<DrivePlace, List<DriveFile>>, shows: Map<String, String>): Pair<DrivePlace, DriveFile>? {
    for ((place, files) in libraries) {
        Library.continuing(files, listOf(tile), shows, ::matchKeyOf).firstOrNull()?.let { return place to it.first }
    }
    return null
}

/**
 * Carries out a title menu's rows (and Play/Pause) for a screen: plays (waiting at most half a second for the
 * other TVs' latest spot, as every resume does), opens pages, marks, removes. Made by [rememberTitleRun].
 */
@Stable
internal class TitleRun(
    private val context: Context,
    private val scope: CoroutineScope,
    private val resume: (() -> Unit) -> Unit,
    private val drive: DriveViewModel?,
    /** Opens a title's page where this screen opens them (over Home or Streaming, over Search, in place of a page). */
    private val openPage: (DrivePlace, DriveFile, String?) -> Unit,
    /** Something's about to play: a title page closes first. */
    private val beforePlay: () -> Unit,
    /** ✓s or Continue watching changed (a Mark or Remove row, once written): the screen reads them again. */
    private val changed: () -> Unit,
    /**
     * A tile is coming off Continue watching (Remove, as it's chosen, before it's written; with the library
     * entry it stands for, when known): the screen takes it away and moves the focus to its neighbour
     * (title-access §4.2).
     */
    private val removing: (RecentItem, DriveFile?) -> Unit,
    /** Home's Continue watching: a tile played as OK plays it. */
    private val playTile: ((RecentItem) -> Unit)?,
) {
    /** A show being resumed, waiting for the other TVs' latest: a second press meanwhile doesn't start it twice. */
    private var goingOn: Job? = null

    /** [target]'s menu, read off the main thread. */
    suspend fun build(target: MenuFor): TitleMenuModel = withContext(Dispatchers.IO) {
        titleMenu(readTitleFacts(context, target, drive?.libraries().orEmpty(), drive?.newEpisodes?.value.orEmpty()))
    }

    /** The menu if [build] takes too long: what [target] says by itself. */
    fun quick(target: MenuFor): TitleMenuModel = quickTitleMenu(target, target.file?.let { drive?.newEpisodes?.value?.get(it.id) })

    /** Play/Pause on [target]: its menu's first row, without the menu (its page opens when nothing playable is known). */
    fun playNow(target: MenuFor) {
        val tile = target.tile
        if (target.where == MenuPlace.HOME_RESUME && tile != null && playTile != null) { playTile.invoke(tile); return }
        scope.launch {
            val model = try { build(target) } catch (e: CancellationException) { throw e } catch (e: Exception) { quick(target) }
            model.first?.let { run(model, it) }
        }
    }

    /** Does what [action] (a row of [model]) says. */
    fun run(model: TitleMenuModel, action: TitleAction) {
        when (action) {
            is TitleAction.Play -> play(model, action.play, fromStart = false)
            is TitleAction.Restart -> play(model, action.play, fromStart = true)
            is TitleAction.PlayTile ->
                if (action.fromStart) { beforePlay(); startKey(action.tile.key, action.tile.title, 0L) }
                else playTile?.invoke(action.tile) ?: resume { beforePlay(); startKey(action.tile.key, action.tile.title, null) }
            is TitleAction.Page -> {
                val place = model.place
                val entry = model.entry
                if (place != null && entry != null) openPage(place, entry, focusEpisode(entry))
            }
            is TitleAction.Together -> together(action)
            is TitleAction.Mark -> scope.launch {
                withContext(Dispatchers.IO + NonCancellable) {
                    if (action.watched) Watched.unmark(context, action.key)
                    else { RecentStore(context).remove(action.key); Watched.mark(context, action.key) }
                }
                changed()
            }
            is TitleAction.Download -> drive?.download(action.file)
            is TitleAction.Remove -> {
                // Taken away at once (the focus moving on to its neighbour), written behind it.
                removing(action.tile, model.entry)
                scope.launch {
                    withContext(Dispatchers.IO + NonCancellable) { RecentStore(context).removeShow(action.tile) }
                    changed()
                }
            }
        }
    }

    /** A show with new episodes opens at the first of them (when its poster says so). */
    private fun focusEpisode(f: DriveFile): String? = drive?.newEpisodes?.value?.get(f.id)?.takeIf { it.announced }?.first?.id

    private fun play(model: TitleMenuModel, play: MainPlay, fromStart: Boolean) {
        val show = model.entry
        when (play) {
            is MainPlay.Film -> {
                val go = {
                    beforePlay()
                    if (play.folder != null) playEpisodes(context, play.folder, play.file, listOf(play.file), fromStart)
                    else playTitle(context, play.file, fromStart)
                }
                if (fromStart) go() else resume(go)
            }
            is MainPlay.GoOn -> goOn(model, play)
            is MainPlay.New -> {
                beforePlay()
                val season = play.season
                if (season != null && show != null) playEpisodes(context, show, season.file, season.videos, true)
                else startKey(play.episode.key, newEpisodeTitle(show?.let { Library.info(it).title }.orEmpty(), play), null)
            }
            is MainPlay.First -> {
                beforePlay()
                if (show != null) playEpisodes(context, show, play.episode, play.season, true)
                else startKey(MediaRef.Drive(play.episode.id).key, play.episode.name, 0L)
            }
        }
    }

    /**
     * A show's episode to go on with: once the other TVs' latest spot is in (half a second at most), the
     * show is worked out again (another TV may have gone on to the next episode since the menu opened), as
     * its page does on Resume. Not started if Woodruff left the screen meanwhile.
     */
    private fun goOn(model: TitleMenuModel, play: MainPlay.GoOn) {
        if (goingOn?.isActive == true) return
        val show = model.entry
        goingOn = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            FamilySync.awaitFresh(context)
            val now = if (show == null || model.place == null) play
            else (build(MenuFor(model.place, show, MenuPlace.LIBRARY)).first as? TitleAction.Play)?.play as? MainPlay.GoOn ?: play
            if (!context.inFront()) return@launch
            beforePlay()
            val season = now.season
            if (season != null && show != null) playEpisodes(context, show, season.file, season.videos, now.item.upNext)
            else startKey(now.item.key, now.item.title, null)
        }
    }

    private fun startKey(key: String, title: String, positionMs: Long?) {
        context.startActivity(PlayerActivity.intent(context, listOf(key), listOf(title), key, positionMs))
    }

    /** The same video on every linked TV and this one, from the same spot; this TV leads (pause and seek keep them in step). */
    fun together(a: TitleAction.Together) {
        scope.launch {
            // From where anyone last stopped, as Play does.
            FamilySync.awaitFresh(context)
            val (key, title, at) = withContext(Dispatchers.IO) {
                val store = RecentStore(context)
                when {
                    a.tile != null -> latestOf(a.tile, store.continueWatching()).let { Triple(it.key, it.title, if (it.upNext) 0L else it.positionMs) }
                    a.positionMs > 0 -> Triple(a.key, a.title, store.resumePosition(a.key))
                    else -> Triple(a.key, a.title, 0L)
                }
            }
            withContext(Dispatchers.IO) { TvLinks.forEachLinked(context) { TvLinks.watch(it, key, title, at) } }
            beforePlay()
            context.startActivity(PlayerActivity.intent(context, listOf(key), listOf(title), key, at, together = true))
        }
    }
}

/**
 * A [TitleRun] for this screen. [openPage]: where it opens title pages; [beforePlay]: what closes as something
 * starts (a title page); [changed]: ✓s or Continue watching changed; [removing]: a Continue watching tile is
 * being taken off; [playTile]: Home's way of playing a tile.
 */
@Composable
internal fun rememberTitleRun(
    drive: DriveViewModel?,
    openPage: (DrivePlace, DriveFile, String?) -> Unit = { place, f, ep -> TitlePages.open(place, f, ep) },
    beforePlay: () -> Unit = {},
    changed: () -> Unit = {},
    removing: (RecentItem, DriveFile?) -> Unit = { _, _ -> },
    playTile: ((RecentItem) -> Unit)? = null,
): TitleRun {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume = rememberResume()
    // The latest of each, read when a row runs: the run itself stays the same object.
    val open by rememberUpdatedState(openPage)
    val before by rememberUpdatedState(beforePlay)
    val change by rememberUpdatedState(changed)
    val remove by rememberUpdatedState(removing)
    val tile by rememberUpdatedState(playTile)
    val hasTile = playTile != null
    return remember(context, scope, resume, drive, hasTile) {
        TitleRun(
            context, scope, resume, drive,
            openPage = { p, f, e -> open(p, f, e) }, beforePlay = { before() }, changed = { change() }, removing = { r, f -> remove(r, f) },
            playTile = if (hasTile) { r -> tile?.invoke(r) } else null,
        )
    }
}

/**
 * The title menu for [target] (none when null), over this window: built off the main thread and shown once
 * it's ready (within [MENU_WAIT_MS], else with only what's known: rows never change once it's open). Back
 * closes it ([onClose]); a row closes it, then runs.
 */
@Composable
internal fun TitleMenu(target: MenuFor?, run: TitleRun, onClose: () -> Unit) {
    val t = target ?: return
    var model by remember(t) { mutableStateOf<TitleMenuModel?>(null) }
    LaunchedEffect(t) {
        val built = async { try { run.build(t) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
        model = withTimeoutOrNull(MENU_WAIT_MS) { built.await() } ?: run.quick(t).also { built.cancel() }
    }
    val m = model ?: return
    ActionMenu(
        title = m.title, subtitle = m.subtitle, titleMaxLines = 1, onDismiss = onClose,
        actions = m.rows.map { a -> MenuAction(a.label(), a.icon(), destructive = a is TitleAction.Remove) { run.run(m, a) } },
    )
}

/**
 * Play/Pause on a title (on key up): plays it ([onPlay]), as its menu's first row would. Put before the
 * title's focus target, so the key reaches it; only those two keys are taken.
 */
internal fun Modifier.playPauseKey(onPlay: () -> Unit): Modifier = onKeyEvent { e ->
    val code = e.nativeKeyEvent.keyCode
    if (code != NativeKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && code != NativeKeyEvent.KEYCODE_MEDIA_PLAY) return@onKeyEvent false
    if (e.type == KeyEventType.KeyUp) onPlay()
    true
}
