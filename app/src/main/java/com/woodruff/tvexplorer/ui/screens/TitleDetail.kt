package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.components.FocusButton
import com.woodruff.tvexplorer.ui.icons.Sym
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.EpisodeMeta
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.NewEpisodes
import com.woodruff.tvexplorer.cloud.episodeCodeOf
import com.woodruff.tvexplorer.cloud.episodeEyebrow
import com.woodruff.tvexplorer.cloud.episodeName
import com.woodruff.tvexplorer.cloud.episodePlayTitle
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.cloud.retryPause
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.bringWholeIntoView
import com.woodruff.tvexplorer.ui.motion.DialogWindowMotion
import com.woodruff.tvexplorer.ui.motion.WindowMotion
import com.woodruff.tvexplorer.ui.motion.dpadGate
import com.woodruff.tvexplorer.ui.motion.posterRowState
import com.woodruff.tvexplorer.ui.components.BadgeKind
import com.woodruff.tvexplorer.ui.components.StatusBadge
import com.woodruff.tvexplorer.ui.components.tryFocus
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.NetworkWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * A title's page, Apple TV style: its wide art across the screen, the name, year, length, age
 * rating, genres, rating and cast, the synopsis, and the buttons — Play / Resume / Start over for a
 * film (also one kept in its own folder), with when it would end. A show gets Resume (the episode
 * you were in) or Play for the episode up next, Play from its first episode, then season tabs over
 * a row of episode cards with progress bars and ✓s (a long press on a card or a tab marks it
 * watched). Each episode card has its real name and a few lines about it (TMDB's), and new episodes
 * a NEW badge. A film in a collection lists the collection's other films the family has; "More like
 * this" lists titles from your own library that share its genres. Back returns to the grid.
 *
 * [artHeaders] adds the Google token for Drive-hosted art (never sent to TMDB).
 */
@Composable
fun TitleDetail(
    f: DriveFile,
    art: String?,
    token: String?,
    /** The rest of the library, for "More like this". */
    all: List<DriveFile>,
    isShow: Boolean,
    artHeaders: (String) -> Map<String, String>,
    onPlay: (fromStart: Boolean) -> Unit,
    /** Play [episode] with [queue] (its season, in order) after it. */
    onPlayEpisode: (episode: DriveFile, queue: List<DriveFile>, fromStart: Boolean) -> Unit,
    /** Play a single media key (resuming a show from Continue watching). */
    onPlayKey: (key: String, title: String) -> Unit,
    openLabel: String,
    onOpen: () -> Unit,
    onChoose: (DriveFile) -> Unit,
    onDismiss: () -> Unit,
    /** Open at this episode: its season chosen, its card second from the left (a poster with new episodes opens at the first). */
    focusEpisodeId: String? = null,
    /** The show's new episodes ([com.woodruff.tvexplorer.cloud.NewShow.episodes]): the first is what Play plays when nothing's in progress. */
    fresh: List<FreshEpisode> = emptyList(),
    /** For the title menu on the page's rows (its titles, downloads and new episodes); null in the gallery. */
    drive: com.woodruff.tvexplorer.cloud.DriveViewModel? = null,
    /** Where the title menu opens a page: in place of this one. */
    openTitle: (com.woodruff.tvexplorer.cloud.DrivePlace, DriveFile, String?) -> Unit = { _, f, _ -> onChoose(f) },
    /** Something on the page is about to play (from a menu): the page closes first. */
    beforePlay: () -> Unit = {},
) {
    // Whether a title has been shown yet: the first comes with the window gliding in (below), so it
    // doesn't ease in as well; the ones picked after it do.
    val shownOne = remember { BooleanArray(1) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The page grows into place as it fades in, and back out, as the system moves its window
        // (smooth even while the page is still being built).
        DialogWindowMotion(WindowMotion.Page, label = "Title page")
        // A dialog's keys never pass the activity's own: the gate gives the page the same (held arrows gated, the
        // remote's Search button opening Search over the page, the phone's remote reaching it).
        Box(Modifier.fillMaxSize().dpadGate()) {
            // One page per title: a title picked from "More like this" starts afresh, with nothing (cast,
            // seasons, the first episode, scroll, focus) carried over from the one before.
            key(f.id) {
                // A title picked here eases in (fade and a gentle zoom) rather than popping up.
                val ease = remember { shownOne[0].also { shownOne[0] = true } }
                com.woodruff.tvexplorer.ui.ScreenEnter(animate = ease) {
                    TitlePage(f, art, token, all, isShow, artHeaders, onPlay, onPlayEpisode, onPlayKey, openLabel, onOpen, onChoose, focusEpisodeId, fresh, drive, openTitle, beforePlay)
                }
            }
        }
    }
}

/** A title's page itself, without its window (the gallery draws it as it is). */
@Composable
internal fun TitlePage(
    f: DriveFile,
    art: String?,
    token: String?,
    all: List<DriveFile>,
    isShow: Boolean,
    artHeaders: (String) -> Map<String, String>,
    onPlay: (fromStart: Boolean) -> Unit,
    onPlayEpisode: (episode: DriveFile, queue: List<DriveFile>, fromStart: Boolean) -> Unit,
    onPlayKey: (key: String, title: String) -> Unit,
    openLabel: String,
    onOpen: () -> Unit,
    onChoose: (DriveFile) -> Unit,
    focusEpisodeId: String? = null,
    fresh: List<FreshEpisode> = emptyList(),
    drive: com.woodruff.tvexplorer.cloud.DriveViewModel? = null,
    openTitle: (com.woodruff.tvexplorer.cloud.DrivePlace, DriveFile, String?) -> Unit = { _, t, _ -> onChoose(t) },
    beforePlay: () -> Unit = {},
) {
    val context = LocalContext.current
    val info = remember { Library.info(f) }
    // Bumped when a ✓ is changed here (an episode, a whole season): the ✓s, the progress bars and
    // the show's Resume are read again.
    var marks by remember { mutableIntStateOf(0) }
    // The other TVs' latest progress, fetched as the page opens (unless it was just now), so by the
    // time Resume is pressed it's in; and merged in while the page is open, it's read again.
    LaunchedEffect(Unit) { FamilySync.pullFresh(context) }
    val synced by FamilySync.updates.collectAsState()
    // What's been played, read once for the page (and again when a ✓ changes here or another TV's
    // progress comes in): the places to resume from and the lengths below all come from this one read.
    val recent = remember(marks, synced) { RecentStore(context).all() }
    val resumeMs = remember(recent) { if (f.isFolder) 0L else resumeAt(recent, MediaRef.Drive(f.id).key) }
    // How far through each episode is, and how long each runs as the player measured it: by media key.
    val progressOf = remember(recent) { recent.associate { it.key to it.progress } }
    val measured = remember(recent) { recent.associate { it.key to it.durationMs } }
    // Offline with nothing of the show's folder saved: its episodes will show once the TV is back online.
    var insideOffline by remember { mutableStateOf(false) }
    // What's in the folder: a show's seasons (or its episodes), or the film kept in it. The last
    // listing shows at once (from memory, or from disk a moment later, even after a restart) while
    // a fresh one loads behind it. Offline, what was saved stays; with nothing saved, it's listed by
    // itself the moment the network is back (or after a growing pause).
    val inside by produceState<List<DriveFile>?>(remember { if (f.isFolder) FolderCache.inMemory(f.id) else emptyList() }) {
        if (!f.isFolder) return@produceState
        if (value == null) value = withContext(Dispatchers.IO) { FolderCache.peek(context, f.id) }
        val saved = value != null
        var tries = 0
        while (true) {
            val mark = NetworkWatch.comebacks.value
            when (val listed = FolderCache.fetch(context, token, f)) {
                is FolderCache.Listing.Listed -> { value = listed.files; insideOffline = false; return@produceState }
                FolderCache.Listing.Failed -> { if (value == null) value = emptyList(); insideOffline = false; return@produceState }
                FolderCache.Listing.Offline -> {
                    // Nothing to show meanwhile: the page has its folder button, and says why there are no episodes.
                    if (!saved) { if (value == null) value = emptyList(); insideOffline = true }
                    NetworkWatch.awaitComeback(mark, retryPause(tries++))
                }
            }
        }
    }
    // A show's episodes already listed (in memory), to know its own when their names don't say.
    val listedAtOpen = remember {
        if (!isShow) emptySet<String>()
        else inside.orEmpty().let { top -> listedKeys(null, top) + top.filter { it.isFolder }.flatMap { listedKeys(it, null) } }
    }
    // A show: the episode to go on with, the newest in progress or up next after one just finished.
    // Matched by the show's name, so it's found whichever season folder it's in and however its file
    // is named ("Show (2008) - S02E04.mkv", "Show.S02E04…"), or known to sit under this show
    // ([EpisodeShows], or listed in its folders) when its name doesn't say ("S01E013_Pilot").
    // Remembered keys (matchKeyOf): the names in progress were worked out off the main thread already.
    val showKey = remember { matchKeyOf(f.name) }
    // Whether something played is this show's (EpisodeShows is loaded first).
    val ofThisShow: (RecentItem) -> Boolean = remember { { r -> EpisodeShows.map.value[r.key] == f.id || r.key in listedAtOpen || matchKeyOf(r.title) == showKey } }
    val resumeEpisode = remember(recent) {
        if (!isShow) null
        else {
            EpisodeShows.load(context)
            showResume(recent, ofThisShow)
        }
    }
    // TMDB's details (length, age rating, collection, cast), from its disk cache after the first time.
    val details by produceState<Tmdb.Details?>(TitleSamples.details) {
        val id = info.tmdbId ?: return@produceState
        if (value == null) value = Tmdb.details(context, id, info.tv)
    }
    val cast = details?.cast.orEmpty()
    // Season folders, or one "Episodes" tab when the episodes sit in the show's folder itself.
    val seasons = remember(inside) {
        inside?.let { list ->
            if (!isShow) emptyList<Pair<String, DriveFile?>>()
            else list.filter { it.isFolder }.map<DriveFile, Pair<String, DriveFile?>> { it.name to it }
                .ifEmpty { if (list.any { it.kind == FileKind.VIDEO }) listOf<Pair<String, DriveFile?>>("Episodes" to null) else emptyList() }
        }
    }
    // Where a show starts: its first numbered season ("Specials", "Extras" and "Season 0" sort first).
    val firstSeason = remember(seasons) {
        seasons.orEmpty().withIndex()
            .filter { (_, s) -> (Tmdb.seasonNumber(s.first) ?: 1) >= 1 }
            .minByOrNull { (_, s) -> Tmdb.seasonNumber(s.first) ?: Int.MAX_VALUE }?.index ?: 0
    }
    // The page opens on the season holding the episode it was opened at (a new one, from a poster that
    // says so), else the one holding the episode to go on with (when that's known), else the first.
    // Worked out once, when the seasons are first known, so a fresh listing never moves it (a folder
    // first known offline as empty counts once its seasons arrive).
    val resumeAtOpen = remember { resumeEpisode }
    val focusAtOpen = remember { focusEpisodeId }
    val (startSeason, resumeSeason) = remember(seasons.isNullOrEmpty()) {
        val list = seasons
        val r = resumeAtOpen
        val listed = list?.map { listedKeys(it.second, inside) }
        val held = if (list == null || listed == null || r == null) null
        else seasonHolding(list.map { it.first }, listed, r.key, r.title)
        val focused = if (list == null || listed == null || focusAtOpen == null) null
        else seasonOfEpisode(list.map { it.second?.id }, list.map { it.first }, listed, focusAtOpen, fresh)
        (focused ?: held ?: firstSeason) to held
    }
    // Only OK on a tab chooses a season: the focus crossing the tabs on its way to the episodes (or
    // back up to the buttons) mustn't swap the row out from under it.
    var chosenSeason by remember { mutableIntStateOf(-1) }
    val season = (if (chosenSeason >= 0) chosenSeason else startSeason).coerceIn(0, maxOf(0, seasons.orEmpty().size - 1))
    // Each season's episodes with their pictures (by season folder, "" for episodes in the show's
    // own folder), kept while the page is open: going back to a season shows it at once.
    val seasonEps = remember { mutableStateMapOf<String, List<DriveFile>>() }
    // The seasons listed afresh during this visit.
    val refreshed = remember { HashSet<String>() }
    // The seasons that couldn't be listed (offline) and were never listed on this TV: they say so.
    val offlineSeasons = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    // The season on screen, and the first for "Play S1 E1": what was listed before at once, then a
    // fresh listing. Each tagged with its season, so switching seasons never shows (or plays) the
    // previous one's while the next loads.
    val loading = remember(seasons, season, firstSeason) {
        listOfNotNull(seasons?.getOrNull(firstSeason), seasons?.getOrNull(season)).distinctBy { seasonKey(it.second) }
    }
    loading.forEach { s ->
        val k = seasonKey(s.second)
        key(k) {
            LaunchedEffect(k, if (s.second == null) inside else null) {
                val folder = s.second
                if (folder == null) {
                    // Episodes in the show's own folder: they come with its listing.
                    val eps = stills(context, inside.orEmpty(), f.name, null)
                    if (seasonEps[k] != eps) seasonEps[k] = eps
                    return@LaunchedEffect
                }
                if (k !in seasonEps) {
                    val saved = FolderCache.inMemory(folder.id) ?: withContext(Dispatchers.IO) { FolderCache.peek(context, folder.id) }
                    if (saved != null) seasonEps[k] = stills(context, saved, f.name, folder.name)
                }
                // Offline, what was listed before stays (the season says it will show when the TV is
                // back online if it was never listed); listed again the moment the network's back.
                var tries = 0
                while (k !in refreshed) {
                    val mark = NetworkWatch.comebacks.value
                    when (val listed = FolderCache.fetch(context, token, folder)) {
                        is FolderCache.Listing.Listed -> {
                            refreshed += k
                            offlineSeasons -= k
                            val eps = stills(context, listed.files, f.name, folder.name)
                            // Only when something changed: the cards (and the focus on one) stay put.
                            if (seasonEps[k] != eps) seasonEps[k] = eps
                        }
                        FolderCache.Listing.Failed -> {
                            if (k !in seasonEps) seasonEps[k] = emptyList()
                            break
                        }
                        FolderCache.Listing.Offline -> {
                            if (k !in seasonEps && k !in offlineSeasons) offlineSeasons += k
                            NetworkWatch.awaitComeback(mark, retryPause(tries++))
                        }
                    }
                }
            }
        }
    }
    val episodes = seasons?.getOrNull(season)?.let { seasonEps[seasonKey(it.second)] }
    // The first season's episodes, so "Play S1 E1" stays put while you browse other seasons.
    val firstEps = seasons?.getOrNull(firstSeason)?.let { seasonEps[seasonKey(it.second)] }
    val firstEpisode = firstEps?.firstOrNull()
    // "Loading episodes…" only when it takes a while: a season listed before arrives within a frame or two.
    val slow by produceState(false, season) {
        value = false
        delay(500)
        value = true
    }
    // A film kept in its own folder (Plex style); a collection or a film in parts opens instead.
    val film = remember(inside) {
        if (isShow || !f.isFolder) null else inside?.let(::folderFilm)
    }
    val filmResumeMs = remember(film, recent) { film?.let { resumeAt(recent, MediaRef.Drive(it.id).key) } ?: 0L }
    // A film's collection ("The Lord of the Rings Collection"): its other films the family has, in
    // release order.
    val collection by produceState<Pair<String, List<DriveFile>>?>(null, details) {
        val d = details ?: return@produceState
        val id = d.collectionId ?: return@produceState
        val name = d.collectionName ?: return@produceState
        if (isShow) return@produceState
        val parts = Tmdb.collection(context, id)
        if (parts.isEmpty()) return@produceState
        val owned = withContext(Dispatchers.Default) { ownedParts(parts, all, info.tmdbId) }
        if (owned.isNotEmpty()) value = name to owned
    }
    // More like this, sorted from the whole library on a background thread; again once the
    // collection is known (its films aren't repeated here).
    val similar by produceState(emptyList<DriveFile>(), collection) {
        val inCollection = collection?.second.orEmpty().mapTo(HashSet()) { it.id }
        value = withContext(Dispatchers.Default) { moreLikeThis(f, info.genres, all, inCollection) }
    }
    // When "now" is for the NEW badges: read with what's been played (again when a ✓ changes here).
    val now = remember(recent) { System.currentTimeMillis() }
    // The first new episode to watch (put in Drive lately, not watched or started on any TV): what Play
    // plays when nothing's in progress or up next, unless it's the first episode anyway (a show added
    // whole lately starts at S1 E1, as any does).
    val firstNew = remember(fresh, recent) {
        fresh.filter { NewEpisodes.isNew(it.created, now, Watched.isWatched(context, it.key), progressOf[it.key]) }.minWithOrNull(NewEpisodes.watchingOrder)
    }
    val playsNew = isShow && f.isFolder && resumeEpisode == null && firstNew != null && firstNew.id != firstEpisode?.id
    // The episode a show's main button plays, and its season folder: its name and length.
    val mainEpisode: Pair<String, String?>? = when {
        !isShow || !f.isFolder -> null
        resumeEpisode != null -> resumeEpisode.title to resumeSeason?.let { seasons?.getOrNull(it)?.second?.name }
        playsNew && firstNew != null -> firstNew.name to firstNew.seasonName
        firstEpisode != null -> firstEpisode.name to seasons?.getOrNull(firstSeason)?.second?.name
        else -> null
    }
    // That episode as its season on the page has it (TMDB's name and length are on it then), else as
    // TMDB's cache knows it.
    val mainKey = when {
        mainEpisode == null -> null
        resumeEpisode != null -> resumeEpisode.key
        playsNew -> firstNew?.key
        else -> firstEpisode?.let { MediaRef.Drive(it.id).key }
    }
    val listedMain = mainKey?.let { k -> seasonEps.values.firstNotNullOfOrNull { eps -> eps.firstOrNull { MediaRef.Drive(it.id).key == k } } }?.episode
    val lookedUp by produceState<EpisodeMeta?>(null, mainEpisode) {
        value = null
        val (name, folder) = mainEpisode ?: return@produceState
        value = Tmdb.episodeMeta(context, f.name, name, folder)
    }
    val mainMeta = listedMain ?: lookedUp
    val episodeMinutes = mainMeta?.minutes
    // The line under the buttons names it when TMDB knows its name: "S2 E4 · The Volcano".
    val mainNamed = mainMeta?.name?.let { "S${mainMeta.season} E${mainMeta.number}" to it }
    // What the main button plays, for when it would end if started now: its length (exactly, once
    // it's been started; else TMDB's), and where it starts. The clock is added where it's shown.
    val (playMs, fromMs) = when {
        isShow && f.isFolder -> when {
            resumeEpisode != null -> playLength(resumeEpisode.durationMs, episodeMinutes) to (if (resumeEpisode.upNext) 0L else resumeEpisode.positionMs)
            playsNew && firstNew != null -> playLength(measured[firstNew.key], episodeMinutes) to 0L
            firstEpisode != null -> playLength(durationOf(recent, firstEpisode), episodeMinutes) to 0L
            else -> null to 0L
        }
        film != null -> playLength(durationOf(recent, film), details?.runtime) to filmResumeMs
        f.isFolder -> null to 0L
        else -> playLength(durationOf(recent, f), details?.runtime) to resumeMs
    }
    // Its line is kept while TMDB's lengths load (a title TMDB knows), so the rows below don't move.
    val holdEnds = info.tmdbId != null && when {
        isShow && f.isFolder -> resumeEpisode != null || playsNew || firstEpisode != null
        f.isFolder -> film != null
        else -> true
    }
    // Long press (or Menu) on an episode or a season's tab: its menu (play, Watch together, Mark as watched).
    var episodeMenu by remember { mutableStateOf<DriveFile?>(null) }
    // Long press (or Menu) on a title in the page's rows (its collection, More like this): the title menu.
    var rowMenu by remember { mutableStateOf<MenuFor?>(null) }
    // The library this page's rows come from: its titles' menus and pages are that library's.
    val rowsPlace = if (isShow) com.woodruff.tvexplorer.cloud.DrivePlace.TvShows else com.woodruff.tvexplorer.cloud.DrivePlace.Movies
    val run = rememberTitleRun(drive, openPage = openTitle, beforePlay = beforePlay, changed = { marks++ })
    // Linked TVs (for Watch together), read off the main thread once.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.woodruff.tvexplorer.tvs.TvLinks.load(context) } }
    val linked by com.woodruff.tvexplorer.tvs.TvLinks.linked.collectAsState()
    var seasonMenu by remember { mutableStateOf<Pair<String, List<DriveFile>>?>(null) }
    val primary = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Resumes start where anyone in the family last stopped (see rememberResume); from the start, at once.
    val resume = rememberResume()
    val page = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Marks episodes watched or not (done even if the page closes meanwhile), then reads the ✓s (and
    // what's in progress) again.
    val setWatched: (List<String>, Boolean) -> Unit = { keys, seen ->
        scope.launch {
            withContext(Dispatchers.IO + NonCancellable) { markWatched(context, keys, seen) }
            marks++
        }
    }
    // Focus the main button once it's there — a show's Resume at once, its Play when the first
    // season's episodes arrive, a folder's Play once its film is found — then leave focus alone.
    val ready = when {
        !f.isFolder -> true
        isShow -> resumeEpisode != null || playsNew || firstEps != null || seasons?.isEmpty() == true
        else -> inside != null
    }
    LaunchedEffect(ready) {
        if (focused || !ready) return@LaunchedEffect
        repeat(10) {
            withFrameNanos {}
            // tryFocus: a request with nothing attached yet no longer throws, it does nothing (and the
            // next frames would never have tried again).
            if (primary.tryFocus()) { focused = true; return@LaunchedEffect }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = info.backdrop ?: art
        val samplePainter = LocalPosterPainter.current
        if (wide != null && samplePainter != null) {
            // The gallery: its sample art, drawn at once.
            samplePainter(wide)?.let { androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        } else if (wide != null) {
            val request = remember(wide) {
                ImageRequest.Builder(context).data(wide)
                    .apply { artHeaders(wide).forEach { (k, v) -> addHeader(k, v) } }
                    .size(1280, 720).crossfade(250).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Darken the left and the bottom, where the text and rows sit, so they read on any picture.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.55f to Color.Black.copy(alpha = 0.5f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.94f))))

        // Moving down the page brings the focused row to rest a third of the way down, Apple TV style.
        PivotScroll(page, Pivot(fraction = 0.3f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(page).padding(start = 72.dp, end = 48.dp, top = 120.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // The top of the page: the title, its details and synopsis, and the buttons. The focus on a
                // button asks for all of it to be shown (the words above them can't take the focus), in the
                // focus's own scroll: back up on the buttons, the whole top of the page shows again.
                Column(Modifier.bringWholeIntoView(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(info.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // Year, length, age rating and genres; whatever isn't known is left out.
                        val meta = listOfNotNull(
                            info.year?.toString(),
                            details?.runtime?.takeIf { !isShow }?.let(::runtimeLabel),
                            details?.ageRating,
                            info.genres.take(3).joinToString(", ").ifBlank { null },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // One line, kept while TMDB's details load, so the lines below don't move.
                            if (meta.isNotEmpty() || info.tmdbId != null) Text(
                                meta.joinToString("  ·  ").ifEmpty { " " }, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            info.rating?.let { r ->
                                // White like its number: the page is always dark, whatever the theme's text color.
                                TvIcon(Sym.Star, size = 18.dp, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 14.dp, end = 4.dp))
                                Text(String.format("%.1f", r), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        info.overview?.let {
                            Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        // The cast's line is kept for it while it loads (a title TMDB knows has one), so the
                        // buttons below, and the focus on them, don't move when it arrives.
                        if (info.tmdbId != null) Text(
                            if (cast.isEmpty()) " " else "Starring " + cast.take(4).joinToString(", "),
                            color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The buttons, all on one row; the first is the main one. Each keeps its place by what
                    // it is, so when another TV's progress comes in and "Play" becomes "Resume from 40m"
                    // (and Start over appears), the focus stays on the button it was on.
                    val buttons: List<PageButton> = when {
                        isShow && f.isFolder -> buildList {
                            if (resumeEpisode != null) {
                                // Up next (the episode after one just finished) plays from its start.
                                add(PageButton(MAIN, Sym.PlayArrow, mainPlayLabel(MainPlay.GoOn(resumeEpisode))) {
                                    resume {
                                        // Where the show is now, the other TVs' latest included: it may have gone on.
                                        EpisodeShows.load(context)
                                        val r = showResume(RecentStore(context).all(), ofThisShow) ?: resumeEpisode
                                        // From its season when that's listed here, so the rest of the season follows it.
                                        val found = seasonEps.values.firstNotNullOfOrNull { eps -> eps.firstOrNull { MediaRef.Drive(it.id).key == r.key }?.let { it to eps } }
                                        if (found != null) onPlayEpisode(found.first, found.second, r.upNext)
                                        else onPlayKey(r.key, r.title)
                                    }
                                })
                                if (firstEpisode != null) add(PageButton(RESTART, Sym.Replay, "Play from the start") { onPlayEpisode(firstEpisode, firstEps.orEmpty(), true) })
                            } else if (playsNew && firstNew != null) {
                                // New episodes, nothing in progress: the first of them, from its start.
                                add(PageButton(MAIN, Sym.PlayArrow, mainPlayLabel(MainPlay.New(firstNew))) {
                                    // From its season when that's listed here, so the rest of the season follows it.
                                    val found = seasonEps.values.firstNotNullOfOrNull { eps -> eps.firstOrNull { it.id == firstNew.id }?.let { it to eps } }
                                    if (found != null) onPlayEpisode(found.first, found.second, true)
                                    else onPlayKey(firstNew.key, episodePlayTitle(info.title, DriveFile(firstNew.id, firstNew.name, "", 0, 0, null, episode = mainMeta)))
                                })
                                if (firstEpisode != null) add(PageButton(RESTART, Sym.Replay, "Play from the start") { onPlayEpisode(firstEpisode, firstEps.orEmpty(), true) })
                            } else if (firstEpisode != null) {
                                add(PageButton(MAIN, Sym.PlayArrow, mainPlayLabel(MainPlay.First(firstEpisode, firstEps.orEmpty()))) { onPlayEpisode(firstEpisode, firstEps.orEmpty(), true) })
                            }
                            // The folders only when no episode was found to play (never beside Play).
                            if (resumeEpisode == null && !playsNew && firstEpisode == null && (firstEps != null || seasons?.isEmpty() == true)) {
                                add(PageButton(MAIN, Sym.FolderOpen, openLabel, onOpen))
                            }
                        }
                        film != null -> buildList {
                            if (filmResumeMs > RESUME_FROM_MS) {
                                add(PageButton(MAIN, Sym.PlayArrow, mainPlayLabel(MainPlay.Film(film, filmResumeMs, folder = f))) { resume { onPlayEpisode(film, listOf(film), false) } })
                                add(PageButton(RESTART, Sym.Replay, "Start over") { onPlayEpisode(film, listOf(film), true) })
                            } else {
                                add(PageButton(MAIN, Sym.PlayArrow, "Play") { resume { onPlayEpisode(film, listOf(film), false) } })
                            }
                            add(PageButton(OPEN, Sym.FolderOpen, openLabel, onOpen))
                        }
                        f.isFolder -> listOf(PageButton(MAIN, Sym.FolderOpen, openLabel, onOpen))
                        resumeMs > RESUME_FROM_MS -> listOf(
                            PageButton(MAIN, Sym.PlayArrow, mainPlayLabel(MainPlay.Film(f, resumeMs))) { resume { onPlay(false) } },
                            PageButton(RESTART, Sym.Replay, "Start over") { onPlay(true) },
                        )
                        else -> listOf(PageButton(MAIN, Sym.PlayArrow, "Play") { resume { onPlay(false) } })
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        buttons.forEach { b ->
                            key(b.id) {
                                val main = b.id == MAIN
                                DetailButton(b.icon, b.label, primary = main, if (main) Modifier.focusRequester(primary) else Modifier, b.onClick)
                            }
                        }
                    }
                }
                // When it would end, started now; for a show, first which episode that is (TMDB knowing its name).
                if (playMs != null || holdEnds || mainNamed != null) EndsAt(playMs, fromMs, mainNamed)
                // A show's seasons and episodes.
                val seasonList = seasons
                if (isShow && f.isFolder && seasonList != null && seasonList.isNotEmpty()) {
                    // Rows leave room at their ends for a focused item's grown edge and white outline
                    // (a row clips there).
                    // Scrolled to the season the page opens on (one up next can be far along).
                    if (seasonList.size > 1) {
                        val tabs = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, startSeason - 1))
                        PivotScroll(tabs, RowPivot) {
                            LazyRow(
                                state = tabs,
                                horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                items(seasonList.size) { i ->
                                    val s = seasonList[i]
                                    SeasonTab(
                                        s.first, selected = i == season,
                                        // Its episodes for the menu: as shown, else listed now.
                                        onLongClick = {
                                            val shown = seasonEps[seasonKey(s.second)]
                                            if (shown != null) { if (shown.isNotEmpty()) seasonMenu = s.first to shown }
                                            else scope.launch { videosIn(context, token, s.second, inside).takeIf { it.isNotEmpty() }?.let { seasonMenu = s.first to it } }
                                        },
                                    ) { chosenSeason = i }
                                }
                            }
                        }
                    }
                    val eps = episodes
                    // The row is always this tall, its cards top-aligned in it: while a season loads (a
                    // card's picture, its small line and name, and three lines about it for a show TMDB
                    // knows), and whatever each card has to say, so nothing below moves when the cards
                    // arrive or the row scrolls on to one with more words.
                    // (Synopses found for a show TMDB matched only now, its listing older: room made for them.)
                    val rowHeight = episodeRowHeight(synopses = info.tmdbId != null || eps.orEmpty().any { it.episode?.overview != null })
                    if (eps.isNullOrEmpty()) Box(Modifier.height(rowHeight)) {
                        val words = when {
                            eps != null -> "No episodes in this folder"
                            seasonKey(seasonList.getOrNull(season)?.second) in offlineSeasons -> EPISODES_OFFLINE
                            slow -> "Loading episodes…"
                            else -> null
                        }
                        // Where the first card will be (just under the tabs, in view as the page opens).
                        if (words != null) EpisodesNote(words)
                    }
                    // Each season's row starts at its beginning, or at the episode the page was opened at, or
                    // the one to go on with: that card second from the left.
                    else key(season) {
                        val at = eps.indexOfFirst { it.id == focusAtOpen }.takeIf { it >= 0 }
                            ?: eps.indexOfFirst { MediaRef.Drive(it.id).key == resumeEpisode?.key }
                        val row = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, at - 1))
                        PivotScroll(row, RowPivot) {
                            LazyRow(
                                state = row,
                                modifier = Modifier.height(rowHeight),
                                horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 16.dp),
                            ) {
                                items(eps, key = { it.id }) { ep ->
                                    val key = MediaRef.Drive(ep.id).key
                                    // Read again when a ✓ is changed here, or another TV's are merged in
                                    // (watched there, it isn't new here either).
                                    val seen = remember(marks, synced, key) { Watched.isWatched(context, key) }
                                    val progress = progressOf[key]
                                    EpisodeCard(
                                        ep, token,
                                        progress = progress,
                                        watched = seen,
                                        fg = Color.White, secondary = Color.White.copy(alpha = 0.6f), body = Color.White.copy(alpha = 0.7f),
                                        isNew = NewEpisodes.isNew(ep.created, now, seen, progress),
                                        measuredMs = measured[key],
                                        modifier = Modifier.width(EpisodeCardWidth).playPauseKey { resume { onPlayEpisode(ep, eps, false) } },
                                        onLongClick = { episodeMenu = ep },
                                    ) { resume { onPlayEpisode(ep, eps, false) } }
                                }
                            }
                        }
                    }
                } else if (isShow && f.isFolder && insideOffline) {
                    // Offline with nothing of the show saved: where its episodes will be.
                    Box(Modifier.height(episodeRowHeight(synopses = info.tmdbId != null))) { EpisodesNote(EPISODES_OFFLINE) }
                }
                // A film's collection, with ✓s and progress like the library's posters.
                collection?.let { (name, films) ->
                    Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    val row = remember { posterRowState() }
                    PivotScroll(row, RowPivot) {
                        LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp)) {
                            items(films, key = { it.id }) { s ->
                                val keys = remember(s.id) { EpisodeShows.load(context); keysOf(s, EpisodeShows.map.value) }
                                PosterTile(
                                    s, token, onClick = { onChoose(s) }, onLongClick = { rowMenu = MenuFor(rowsPlace, s, MenuPlace.TITLE_PAGE) },
                                    modifier = Modifier.width(130.dp), label = Library.label(s), onPlay = { run.playNow(MenuFor(rowsPlace, s, MenuPlace.TITLE_PAGE)) },
                                    progress = recent.firstOrNull { it.key in keys }?.progress?.takeIf { it < 0.97f },
                                    watched = keys.any { Watched.isWatched(context, it) },
                                )
                            }
                        }
                    }
                }
                // More like this, from your own library.
                if (similar.isNotEmpty()) {
                    Text("More like this", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    val row = remember { posterRowState() }
                    PivotScroll(row, RowPivot) {
                        LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp)) {
                            items(similar, key = { it.id }) { s ->
                                PosterTile(
                                    s, token, onClick = { onChoose(s) }, onLongClick = { rowMenu = MenuFor(rowsPlace, s, MenuPlace.TITLE_PAGE) },
                                    modifier = Modifier.width(130.dp), label = Library.label(s), onPlay = { run.playNow(MenuFor(rowsPlace, s, MenuPlace.TITLE_PAGE)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    episodeMenu?.let { ep ->
        val key = MediaRef.Drive(ep.id).key
        val seen = remember(marks, key) { Watched.isWatched(context, key) }
        // Its season as the page shows it, queued after it (as OK on its card does).
        val season = seasonEps.values.firstOrNull { eps -> eps.any { it.id == ep.id } } ?: listOf(ep)
        val at = resumeAt(recent, key)
        ActionMenu(
            title = remember(ep) { com.woodruff.tvexplorer.cloud.episodeMenuTitle(ep.episode, ep.name) },
            subtitle = episodeMenuSubtitle(seen, recent.firstOrNull { it.key == key }),
            titleMaxLines = 1,
            onDismiss = { episodeMenu = null },
            actions = buildList {
                add(MenuAction(if (at > RESUME_FROM_MS) "Resume from ${Format.duration(at)}" else "Play", Sym.PlayArrow) { resume { onPlayEpisode(ep, season, false) } })
                if (at > RESUME_FROM_MS) add(MenuAction("Play from the start", Sym.Replay) { onPlayEpisode(ep, season, true) })
                if (linked.isNotEmpty()) add(MenuAction("Watch together on all TVs", Sym.Groups) {
                    run.together(TitleAction.Together(key, episodePlayTitle(info.title, ep), at))
                })
                add(
                    if (seen) MenuAction("Mark as unwatched", Sym.RadioButtonUnchecked) { setWatched(listOf(key), false) }
                    else MenuAction("Mark as watched", Sym.CheckCircle) { setWatched(listOf(key), true) },
                )
            },
        )
    }
    TitleMenu(rowMenu, run) { rowMenu = null }
    seasonMenu?.let { (label, eps) ->
        val keys = remember(eps) { eps.map { MediaRef.Drive(it.id).key } }
        val seen = remember(marks, keys) { keys.count { Watched.isWatched(context, it) } }
        ActionMenu(
            title = label, subtitle = null, onDismiss = { seasonMenu = null },
            actions = buildList {
                if (seen < keys.size) add(MenuAction("Mark season as watched", Sym.CheckCircle) { setWatched(keys, true) })
                if (seen > 0) add(MenuAction("Mark season as unwatched", Sym.RadioButtonUnchecked) { setWatched(keys, false) })
            },
        )
    }
}

/**
 * The line under an episode menu's title: "Watched" with its ✓ ([seen]), "45% watched" part-way ([played]:
 * its Continue watching entry), else none.
 */
internal fun episodeMenuSubtitle(seen: Boolean, played: RecentItem?): String? = when {
    seen -> "Watched"
    played != null && played.positionMs > 10_000 && played.progress < 0.95f -> percentWatched(played)
    else -> null
}

/** Sample TMDB details for the UI gallery, which has no TMDB key or network. Never set in the app. */
internal object TitleSamples {
    var details: Tmdb.Details? = null
}

/** A season's key in the page's lists: its folder's id, "" for episodes in the show's own folder. */
private fun seasonKey(folder: DriveFile?): String = folder?.id ?: ""

/** Where a focused card or tab comes to rest as a row scrolls: at the row's start, Apple TV style. */
private val RowPivot = Pivot(offset = 16.dp)

/**
 * "Ends at 11:34 PM": when something [lengthMs] long would end if started now, from [startMs] into
 * it (a blank line while its length isn't known). For a show, the episode it is comes first when
 * TMDB knows its name ([episode]: its code and name): "S2 E4 · The Volcano · Ends at 11:34 PM", one
 * line, a long name giving way (an ellipsis inside it) before the time does. The clock ticks in here,
 * once a minute, so only this line is composed again for it, not the whole page.
 */
@Composable
private fun EndsAt(lengthMs: Long?, startMs: Long, episode: Pair<String, String>? = null) {
    val context = LocalContext.current
    // The clock, to the minute.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000 - value % 60_000 + 50)
            value = System.currentTimeMillis()
        }
    }
    // The TV's own clock style: "11:34 PM", or "23:34".
    val clock = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val ends = endsAt(now, lengthMs, startMs)?.let { "Ends at " + clock.format(Date(it)) }
    val color = Color.White.copy(alpha = 0.6f)
    val style = MaterialTheme.typography.bodyMedium
    // As wide as the words above it: past them the picture isn't shaded enough to read on.
    Row(Modifier.fillMaxWidth(0.6f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (episode == null) Text(ends ?: " ", color = color, style = style, maxLines = 1)
        else {
            Text("${episode.first} · ", color = color, style = style, maxLines = 1, softWrap = false)
            Text(episode.second, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (ends != null) Text(" · $ends", color = color, style = style, maxLines = 1, softWrap = false)
        }
    }
}

/** What a season says in place of its episodes when it couldn't be listed and was never listed on this TV. */
internal const val EPISODES_OFFLINE = "Episodes will show when the TV is back online."

/** A season's words in place of its episodes ("Loading episodes…"): where its first card's picture will be. */
@Composable
private fun EpisodesNote(words: String) {
    Text(
        words, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp),
    )
}

/** An episode card's width on the title page. */
private val EpisodeCardWidth = 280.dp

/**
 * How tall a season's row is on the title page: a card's 16:9 picture, the small line and the name
 * under it, and, for a show TMDB knows ([synopses]), three lines about it; plus the row's own room
 * above and below for a focused card's growth (10 dp each). Text heights follow the TV's font size.
 */
@Composable
private fun episodeRowHeight(synopses: Boolean): Dp {
    val type = MaterialTheme.typography
    return with(LocalDensity.current) {
        val words = type.labelSmall.lineHeight.toDp() + 2.dp + type.titleSmall.lineHeight.toDp() +
            if (synopses) 4.dp + type.bodySmall.lineHeight.toDp() * 3 else 0.dp
        EpisodeCardWidth * (9f / 16f) + 10.dp + words + 20.dp
    }
}

/** The media keys of a season's videos as listed in memory ([inside] for the show's own folder). */
private fun listedKeys(folder: DriveFile?, inside: List<DriveFile>?): Set<String> =
    (if (folder == null) inside else FolderCache.inMemory(folder.id)).orEmpty()
        .filter { it.kind == FileKind.VIDEO }.mapTo(HashSet()) { MediaRef.Drive(it.id).key }

/** A season's videos with their TMDB pictures (just the videos, when TMDB has none to give). */
private suspend fun stills(context: Context, files: List<DriveFile>, show: String, seasonFolder: String?): List<DriveFile> {
    val videos = files.filter { it.kind == FileKind.VIDEO }
    return try {
        Tmdb.withEpisodeStills(context, videos, show = show, seasonFolder = seasonFolder)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        videos
    }
}

/** A season's videos for its menu when they aren't on the page yet: as listed before, else now. */
private suspend fun videosIn(context: Context, token: String?, folder: DriveFile?, inside: List<DriveFile>?): List<DriveFile> {
    val files = if (folder == null) inside
    else FolderCache.inMemory(folder.id) ?: withContext(Dispatchers.IO) { FolderCache.peek(context, folder.id) } ?: FolderCache.list(context, token, folder)
    return files.orEmpty().filter { it.kind == FileKind.VIDEO }
}

/**
 * Marks [keys] watched — out of Continue watching too, as when they're watched to the end — or not
 * watched. The stores tell the family's other TVs. It writes them: off the main thread.
 */
private fun markWatched(context: Context, keys: List<String>, seen: Boolean) {
    if (seen) {
        if (keys.size == 1) Watched.mark(context, keys[0]) else Watched.markAll(context, keys)
        val store = RecentStore(context)
        keys.forEach { store.remove(it) }
    } else {
        if (keys.size == 1) Watched.unmark(context, keys[0]) else Watched.unmarkAll(context, keys)
    }
}

/** How long [file] runs, as the player measured it when it was started; null when it hasn't been. */
private fun durationOf(recent: List<RecentItem>, file: DriveFile): Long? {
    val key = MediaRef.Drive(file.id).key
    return recent.firstOrNull { it.key == key }?.durationMs
}

/**
 * Where [key] resumes, found in [recent] (one read of RecentStore for the whole page) as
 * RecentStore.resumePosition finds it: where it stopped; 0 when it's not there or was finished
 * (past 95%, where the store counts it finished).
 */
internal fun resumeAt(recent: List<RecentItem>, key: String): Long =
    recent.firstOrNull { it.key == key }?.takeIf { it.progress < 0.95f }?.positionMs ?: 0L

/**
 * "More like this" for [self]: up to 14 titles of [library] sharing any of its [genres], those
 * sharing the most first, then the best rated; never itself, nor one in [exclude] (its collection's
 * row). Pure, so it's sorted on a background thread (and unit tested).
 */
internal fun moreLikeThis(self: DriveFile, genres: List<String>, library: List<DriveFile>, exclude: Set<String>): List<DriveFile> =
    if (genres.isEmpty()) emptyList()
    else library.asSequence().filter { it.id != self.id && it.id !in exclude }
        .map { it to Library.info(it).genres.count { g -> g in genres } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<DriveFile, Int>> { it.second }.thenByDescending { Library.info(it.first).rating ?: 0.0 })
        .take(14).map { it.first }.toList()

/** The media keys of a library entry: a film's own file, or the files played from the folder it's kept in ([shows]). */
private fun keysOf(entry: DriveFile, shows: Map<String, String>): List<String> =
    if (!entry.isFolder) listOf(MediaRef.Drive(entry.id).key) else shows.filterValues { it == entry.id }.keys.toList()

/**
 * A show's episode to go on with: the newest of [recent] (newest first) that's in progress (2–97%)
 * or up next after one just finished, of those [mine] says are this show's. So an episode left
 * part-way long ago doesn't win over a newer one up next.
 */
internal fun showResume(recent: List<RecentItem>, mine: (RecentItem) -> Boolean): RecentItem? =
    recent.firstOrNull { (it.upNext || it.progress in 0.02f..0.97f) && mine(it) }

/**
 * Which of a show's seasons ([names], with the media keys [listed] in each) holds episode [key]:
 * the one listing it, else the one its name's season ("S02E05" in [title]) points to; null when
 * neither says.
 */
internal fun seasonHolding(names: List<String>, listed: List<Set<String>>, key: String, title: String): Int? {
    listed.indexOfFirst { key in it }.takeIf { it >= 0 }?.let { return it }
    val number = Tmdb.episodeNumber(title, null)?.first ?: return null
    return names.indexOfFirst { Tmdb.seasonNumber(it) == number }.takeIf { it >= 0 }
}

/**
 * Which of a show's seasons (their folders' ids, [folderIds], null for episodes in the show's own
 * folder; [names]; the media keys [listed] in each) holds episode [id]: the folder its new-episode
 * record ([fresh]) says, else as [seasonHolding] finds it. Null when nothing says.
 */
internal fun seasonOfEpisode(folderIds: List<String?>, names: List<String>, listed: List<Set<String>>, id: String, fresh: List<FreshEpisode>): Int? {
    val e = fresh.firstOrNull { it.id == id }
    if (e != null) folderIds.indexOf(e.seasonId).takeIf { it >= 0 }?.let { return it }
    return seasonHolding(names, listed, MediaRef.Drive(id).key, e?.name.orEmpty())
}

/** A film's length as its page shows it: "2 h 32 min", "45 min", "2 h". */
internal fun runtimeLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/**
 * How long something runs: exactly, as the player measured it, once it's been started
 * ([durationMs]); else TMDB's length in [minutes]. Null when neither is known.
 */
internal fun playLength(durationMs: Long?, minutes: Int?): Long? =
    durationMs?.takeIf { it > 0 } ?: minutes?.takeIf { it > 0 }?.let { it * 60_000L }

/** When something [lengthMs] long ends, started at [nowMs] from [startMs] into it; null when its length isn't known. */
internal fun endsAt(nowMs: Long, lengthMs: Long?, startMs: Long): Long? =
    lengthMs?.let { nowMs + (it - startMs.coerceIn(0L, it)) }

/**
 * The films of a collection ([parts]: TMDB ids, in release order) that are in [library], in that
 * order: one copy of each, and not the one whose page this is ([self]).
 */
internal fun ownedParts(parts: List<Int>, library: List<DriveFile>, self: Int?): List<DriveFile> {
    val order = parts.withIndex().associate { (i, id) -> id to i }
    return library.mapNotNull { f ->
        val i = Library.info(f)
        val at = i.tmdbId?.takeIf { !i.tv && it != self }?.let { order[it] } ?: return@mapNotNull null
        at to f
    }.sortedBy { it.first }.distinctBy { it.first }.map { it.second }
}

/** "S02E04" / "1x05" in a file name → "S2 E4"; null when there isn't one. */
internal fun episodeCode(name: String): String? {
    CODE_SE.find(name)?.let { return "S${it.groupValues[1].toInt()} E${it.groupValues[2].toInt()}" }
    CODE_X.find(name)?.let { return "S${it.groupValues[1].toInt()} E${it.groupValues[2].toInt()}" }
    return null
}

// Compiled once: Continue watching captions and the title page's buttons work these out.
private val CODE_SE = Regex("""(?i)s(\d{1,2})\s*e(\d{1,3})""")
private val CODE_X = Regex("""(?i)(?<!\d)(\d{1,2})x(\d{2})(?!\d)""")

/**
 * The film in a folder of its own: its one video, or one at least twice the size of any other (a
 * trailer or a sample beside it). Null for a collection ("Back to the Future Trilogy") or a film in
 * parts ("CD1", "CD2"), where playing the biggest would start the wrong film or the wrong half.
 */
internal fun folderFilm(files: List<DriveFile>): DriveFile? {
    val videos = files.filter { it.kind == FileKind.VIDEO }.sortedByDescending { it.size }
    val biggest = videos.firstOrNull() ?: return null
    return biggest.takeIf { videos.size == 1 || it.size >= 2 * videos[1].size }
}

/** A season's tab: OK shows its episodes; a long press (or Menu) offers marking it all watched. */
@Composable
private fun SeasonTab(label: String, selected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    // Menu, like holding OK, is the long click.
    FocusButton(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = Shapes.pill,
        color = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent, focusedColor = Color.White,
        content = if (selected) Color.White else Color.White.copy(alpha = 0.7f), focusedContent = Color(0xFF0B1220),
        focusedScale = 1.06f,
    ) { tint ->
        RowText(
            label, tint.color, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * An episode, as the Apple TV app shows one: its picture (TMDB's, else Drive's frame from the video,
 * else a dark stand-in) with a ✓, a progress bar or NEW, and under it a small line ("EPISODE 3 · 42
 * MIN"), its name, and up to three lines about it (see [episodeEyebrow], [episodeName]). Used in a
 * show's row of episodes and in a season's folder; cards in a row top-align. [fg] colours its name,
 * focus outline and placeholder symbol, [secondary] its small line and [body] what it's about: white,
 * white 60 % and white 70 % on the always-dark title page; the theme's text, and that at 85 %, in a
 * season's folder (Streaming's backdrop dims a picture only so far: muted words wouldn't read there).
 * [isNew]: put in Drive lately and not watched or started on any TV ([NewEpisodes.isNew]). [measuredMs]:
 * how long the player found it, for its length when TMDB doesn't say. [onLongClick] (a long press or
 * the Menu key) opens its menu, if any. The picture is a [TvCard]: growing with the focus redraws it,
 * without recomposing anything.
 */
@Composable
internal fun EpisodeCard(
    ep: DriveFile, token: String?, progress: Float?, watched: Boolean,
    modifier: Modifier = Modifier.width(EpisodeCardWidth),
    fg: Color = Palette.text,
    secondary: Color = fg.copy(alpha = 0.85f),
    body: Color = secondary,
    isNew: Boolean = false,
    measuredMs: Long? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Drive's own images need the Google token (and only they ever get it); TMDB's don't.
    val picture = (ep.posterUrl ?: ep.thumbnailLink?.let(DriveApi::big))?.takeIf { !isGoogle(it) || token != null }
    // Worked out once per episode (the file-name fallbacks run regexes), not on every redraw.
    val eyebrow = remember(ep.name, ep.episode, measuredMs) { episodeEyebrow(ep.episode, ep.name, measuredMs) }
    val name = remember(ep.name, ep.episode) { episodeName(ep.episode, ep.name) }
    Column(modifier) {
        TvCard(
            onClick = onClick,
            shape = EpisodeShape,
            // What a screen reader says for the card: its name (the words under it aren't part of it).
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = name },
            onLongClick = onLongClick,
            focusedScale = TvMotion.CardScale,
            borderWidth = 3.dp,
            focusedBorderColor = fg,
        ) {
            if (picture == null) {
                // No picture: the stand-in every missing picture has, dark in both themes (it's artwork).
                Box(Modifier.fillMaxSize().background(TitleCardBrush))
                TvIcon(Sym.PlayArrow, size = 40.dp, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
            } else if (LocalPosterPainter.current != null) {
                LocalPosterPainter.current!!(picture)?.let {
                    androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            } else {
                val request = remember(picture, token) {
                    ImageRequest.Builder(context).data(picture)
                        .apply { if (isGoogle(picture) && token != null) addHeader("Authorization", "Bearer $token") }
                        .size(400, 225).crossfade(150).build()
                }
                AsyncImage(
                    model = request, contentDescription = null, contentScale = ContentScale.Crop,
                    // The dark stand-in while it loads, and if it can't.
                    placeholder = EpisodeStandIn, error = EpisodeStandIn,
                    // The card clips to its rounded shape already: no second clip (and layer) per card.
                    clipToBounds = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Part-watched: a bar along the bottom, in the blue of art (the same in both themes).
            if (progress != null && progress > 0.01f && !watched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(ArtAccent))
            }
            if (watched) Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { TvIcon(Sym.Check, size = 18.dp, tint = Color(0xFF0B1220)) }
            // New, and nobody's watched it: NEW, top left (never with a ✓).
            else if (isNew) StatusBadge("NEW", BadgeKind.NEW, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(top = 10.dp, start = 4.dp)) {
            // The small line always takes its room (" " when nothing's known), so names line up across a row.
            Text(eyebrow, color = secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                name, color = fg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
            ep.episode?.overview?.let {
                Text(it, color = body, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private val EpisodeShape = RoundedCornerShape(18.dp)

/** The episode picture's stand-in while it loads or if it can't: [TitleCardBrush]. Made once. */
private val EpisodeStandIn = BrushPainter(TitleCardBrush)

/** One of a title page's buttons: which it is ([MAIN], [RESTART] or [OPEN]), its symbol, its words and what OK does. */
private class PageButton(val id: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val onClick: () -> Unit)

/** The page's main button (Play, Resume, the folders): first, white when focused, where the focus starts. */
private const val MAIN = "main"
/** Start over / Play from the start. */
private const val RESTART = "restart"
/** The folder itself, beside a film kept in one. */
private const val OPEN = "open"

/** Apple TV buttons: frosted glass, turning solid white with dark text when focused. */
@Composable
private fun DetailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FocusButton(
        onClick = onClick,
        shape = Shapes.pill,
        color = if (primary) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.16f), focusedColor = Color.White,
        content = Color.White, focusedContent = Color(0xFF0B1220),
        modifier = modifier,
        focusedScale = 1.06f,
    ) { tint ->
        Row(Modifier.padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TintIcon(icon, tint.color, size = 24.dp)
            RowText(label, tint.color, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
