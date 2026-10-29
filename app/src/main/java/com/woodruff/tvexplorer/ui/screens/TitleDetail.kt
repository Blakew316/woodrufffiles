package com.woodruff.tvexplorer.ui.screens

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
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.MediaRef
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
import com.woodruff.tvexplorer.ui.motion.dpadGate
import com.woodruff.tvexplorer.ui.motion.posterRowState
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState
import com.woodruff.tvexplorer.ui.motion.tvFocusMotion
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.util.Format
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
 * watched). A film in a collection lists the collection's other films the family has; "More like
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
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A dialog's keys never pass the activity's repeat gate: held arrows are gated here.
        Box(Modifier.fillMaxSize().dpadGate()) {
            // One page per title: a title picked from "More like this" starts afresh, with nothing (cast,
            // seasons, the first episode, scroll, focus) carried over from the one before.
            key(f.id) {
                // Eases in (fade and a gentle zoom) rather than popping up.
                com.woodruff.tvexplorer.ui.ScreenEnter {
                    TitlePage(f, art, token, all, isShow, artHeaders, onPlay, onPlayEpisode, onPlayKey, openLabel, onOpen, onChoose)
                }
            }
        }
    }
}

@Composable
private fun TitlePage(
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
) {
    val context = LocalContext.current
    val info = remember { Library.info(f) }
    // Bumped when a ✓ is changed here (an episode, a whole season): the ✓s, the progress bars and
    // the show's Resume are read again.
    var marks by remember { mutableIntStateOf(0) }
    // What's been played, read once for the page (and again when a ✓ changes here): the places to
    // resume from and the lengths below all come from this one read.
    val recent = remember(marks) { RecentStore(context).all() }
    val resumeMs = remember { if (f.isFolder) 0L else resumeAt(recent, MediaRef.Drive(f.id).key) }
    // What's in the folder: a show's seasons (or its episodes), or the film kept in it. The last
    // listing shows at once (from memory, or from disk a moment later, even after a restart) while
    // a fresh one loads behind it.
    val inside by produceState<List<DriveFile>?>(remember { if (f.isFolder) FolderCache.inMemory(f.id) else emptyList() }) {
        if (!f.isFolder) return@produceState
        if (value == null) value = withContext(Dispatchers.IO) { FolderCache.peek(context, f.id) }
        value = FolderCache.list(context, token, f) ?: value ?: emptyList()
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
    val resumeEpisode = remember(recent) {
        if (!isShow) null
        else {
            EpisodeShows.load(context)
            val known = EpisodeShows.map.value
            showResume(recent) { r -> known[r.key] == f.id || r.key in listedAtOpen || matchKeyOf(r.title) == showKey }
        }
    }
    // TMDB's details (length, age rating, collection, cast), from its disk cache after the first time.
    val details by produceState<Tmdb.Details?>(null) {
        val id = info.tmdbId ?: return@produceState
        value = Tmdb.details(context, id, info.tv)
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
    // The page opens on the season holding the episode to go on with (when that's known), else the
    // first. Worked out once, when the seasons are first known, so a fresh listing never moves it.
    val resumeAtOpen = remember { resumeEpisode }
    val (startSeason, resumeSeason) = remember(seasons != null) {
        val list = seasons
        val r = resumeAtOpen
        val held = if (list == null || r == null) null
        else seasonHolding(list.map { it.first }, list.map { listedKeys(it.second, inside) }, r.key, r.title)
        (held ?: firstSeason) to held
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
                if (k !in refreshed) {
                    val listed = FolderCache.list(context, token, folder)
                    if (listed != null) {
                        refreshed += k
                        val eps = stills(context, listed, f.name, folder.name)
                        // Only when something changed: the cards (and the focus on one) stay put.
                        if (seasonEps[k] != eps) seasonEps[k] = eps
                    } else if (k !in seasonEps) seasonEps[k] = emptyList()
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
    val filmResumeMs = remember(film) { film?.let { resumeAt(recent, MediaRef.Drive(it.id).key) } ?: 0L }
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
    // The episode a show's main button plays, and its season folder, for its length.
    val mainEpisode: Pair<String, String?>? = when {
        !isShow || !f.isFolder -> null
        resumeEpisode != null -> resumeEpisode.title to resumeSeason?.let { seasons?.getOrNull(it)?.second?.name }
        firstEpisode != null -> firstEpisode.name to seasons?.getOrNull(firstSeason)?.second?.name
        else -> null
    }
    val episodeMinutes by produceState<Int?>(null, mainEpisode) {
        value = null
        val (name, folder) = mainEpisode ?: return@produceState
        value = Tmdb.episodeRuntime(context, f.name, name, folder)
    }
    // What the main button plays, for when it would end if started now: its length (exactly, once
    // it's been started; else TMDB's), and where it starts. The clock is added where it's shown.
    val (playMs, fromMs) = when {
        isShow && f.isFolder -> when {
            resumeEpisode != null -> playLength(resumeEpisode.durationMs, episodeMinutes) to (if (resumeEpisode.upNext) 0L else resumeEpisode.positionMs)
            firstEpisode != null -> playLength(durationOf(recent, firstEpisode), episodeMinutes) to 0L
            else -> null to 0L
        }
        film != null -> playLength(durationOf(recent, film), details?.runtime) to filmResumeMs
        f.isFolder -> null to 0L
        else -> playLength(durationOf(recent, f), details?.runtime) to resumeMs
    }
    // Its line is kept while TMDB's lengths load (a title TMDB knows), so the rows below don't move.
    val holdEnds = info.tmdbId != null && when {
        isShow && f.isFolder -> resumeEpisode != null || firstEpisode != null
        f.isFolder -> film != null
        else -> true
    }
    // Long press (or Menu) on an episode or a season's tab: Mark as watched / unwatched.
    var episodeMenu by remember { mutableStateOf<DriveFile?>(null) }
    var seasonMenu by remember { mutableStateOf<Pair<String, List<DriveFile>>?>(null) }
    val primary = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
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
        isShow -> resumeEpisode != null || firstEps != null || seasons?.isEmpty() == true
        else -> inside != null
    }
    LaunchedEffect(ready) {
        if (focused || !ready) return@LaunchedEffect
        repeat(10) {
            withFrameNanos {}
            if (runCatching { primary.requestFocus() }.isSuccess) { focused = true; return@LaunchedEffect }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = info.backdrop ?: art
        if (wide != null) {
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
                    // The buttons, all on one row.
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        when {
                            isShow && f.isFolder -> {
                                if (resumeEpisode != null) {
                                    val code = episodeCode(resumeEpisode.title)
                                    // Up next (the episode after one just finished) plays from its start.
                                    val label = if (resumeEpisode.upNext) code?.let { "Play $it" } ?: "Play next episode" else code?.let { "Resume $it" } ?: "Resume"
                                    DetailButton(Sym.PlayArrow, label, primary = true, Modifier.focusRequester(primary)) {
                                        // From its season when that's listed here, so the rest of the season follows it.
                                        val found = seasonEps.values.firstNotNullOfOrNull { eps -> eps.firstOrNull { MediaRef.Drive(it.id).key == resumeEpisode.key }?.let { it to eps } }
                                        if (found != null) onPlayEpisode(found.first, found.second, resumeEpisode.upNext)
                                        else onPlayKey(resumeEpisode.key, resumeEpisode.title)
                                    }
                                    if (firstEpisode != null) DetailButton(Sym.Replay, "Play from the start", primary = false) { onPlayEpisode(firstEpisode, firstEps.orEmpty(), true) }
                                } else if (firstEpisode != null) {
                                    DetailButton(Sym.PlayArrow, "Play " + (episodeCode(firstEpisode.name) ?: "first episode"), primary = true, Modifier.focusRequester(primary)) { onPlayEpisode(firstEpisode, firstEps.orEmpty(), true) }
                                }
                                // The folders only when no episode was found to play (never beside Play).
                                if (resumeEpisode == null && firstEpisode == null && (firstEps != null || seasons?.isEmpty() == true)) {
                                    DetailButton(Sym.FolderOpen, openLabel, primary = true, Modifier.focusRequester(primary), onOpen)
                                }
                            }
                            film != null -> {
                                if (filmResumeMs > 60_000) {
                                    DetailButton(Sym.PlayArrow, "Resume from ${Format.duration(filmResumeMs)}", primary = true, Modifier.focusRequester(primary)) { onPlayEpisode(film, listOf(film), false) }
                                    DetailButton(Sym.Replay, "Start over", primary = false) { onPlayEpisode(film, listOf(film), true) }
                                } else {
                                    DetailButton(Sym.PlayArrow, "Play", primary = true, Modifier.focusRequester(primary)) { onPlayEpisode(film, listOf(film), false) }
                                }
                                DetailButton(Sym.FolderOpen, openLabel, primary = false, Modifier, onOpen)
                            }
                            f.isFolder -> DetailButton(Sym.FolderOpen, openLabel, primary = true, Modifier.focusRequester(primary), onOpen)
                            resumeMs > 60_000 -> {
                                DetailButton(Sym.PlayArrow, "Resume from ${Format.duration(resumeMs)}", primary = true, Modifier.focusRequester(primary)) { onPlay(false) }
                                DetailButton(Sym.Replay, "Start over", primary = false) { onPlay(true) }
                            }
                            else -> DetailButton(Sym.PlayArrow, "Play", primary = true, Modifier.focusRequester(primary)) { onPlay(false) }
                        }
                    }
                }
                // When it would end, started now.
                if (playMs != null || holdEnds) EndsAt(playMs, fromMs)
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
                    // While a season loads, its row keeps its room (a card's 16:9 picture and a line of
                    // its name), so the page below doesn't jump up and back down.
                    if (eps == null) Box(Modifier.height(208.dp), contentAlignment = Alignment.CenterStart) {
                        if (slow) Text("Loading episodes…", color = Color.White.copy(alpha = 0.6f))
                    }
                    else if (eps.isEmpty()) Text("No episodes in this folder", color = Color.White.copy(alpha = 0.6f))
                    // Each season's row starts at its beginning, or at the episode to go on with.
                    else key(season) {
                        val at = eps.indexOfFirst { MediaRef.Drive(it.id).key == resumeEpisode?.key }
                        val row = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, at - 1))
                        PivotScroll(row, RowPivot) {
                            LazyRow(
                                state = row,
                                horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 16.dp),
                            ) {
                                items(eps, key = { it.id }) { ep ->
                                    val key = MediaRef.Drive(ep.id).key
                                    // Read again when a ✓ is changed here.
                                    val seen = remember(marks, key) { Watched.isWatched(context, key) }
                                    EpisodeCard(
                                        ep, token,
                                        progress = recent.firstOrNull { it.key == key }?.progress,
                                        watched = seen,
                                        fg = Color.White,
                                        onLongClick = { episodeMenu = ep },
                                    ) { onPlayEpisode(ep, eps, false) }
                                }
                            }
                        }
                    }
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
                                    s, token, onClick = { onChoose(s) }, onLongClick = { onChoose(s) }, modifier = Modifier.width(130.dp), label = Library.label(s),
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
                                PosterTile(s, token, onClick = { onChoose(s) }, onLongClick = { onChoose(s) }, modifier = Modifier.width(130.dp), label = Library.label(s))
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
        ActionMenu(
            title = episodeCardLabel(ep.name), subtitle = null, onDismiss = { episodeMenu = null },
            actions = listOf(
                if (seen) MenuAction("Mark as unwatched", Sym.RadioButtonUnchecked) { setWatched(listOf(key), false) }
                else MenuAction("Mark as watched", Sym.CheckCircle) { setWatched(listOf(key), true) },
            ),
        )
    }
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

/** A season's key in the page's lists: its folder's id, "" for episodes in the show's own folder. */
private fun seasonKey(folder: DriveFile?): String = folder?.id ?: ""

/** Where a focused card or tab comes to rest as a row scrolls: at the row's start, Apple TV style. */
private val RowPivot = Pivot(offset = 16.dp)

/**
 * "Ends at 11:34 PM": when something [lengthMs] long would end if started now, from [startMs] into
 * it (a blank line while its length isn't known). The clock ticks in here, once a minute, so only
 * this line is composed again for it, not the whole page.
 */
@Composable
private fun EndsAt(lengthMs: Long?, startMs: Long) {
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
    Text(
        endsAt(now, lengthMs, startMs)?.let { "Ends at " + clock.format(Date(it)) } ?: " ",
        color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp),
    )
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

/** An episode's card label from its file name: "S1 E3 · The Title" ("Show.S01E03.The.Title.1080p.mkv"). */
internal fun episodeCardLabel(fileName: String): String {
    val code = episodeCode(fileName)
    val name = fileName.substringBeforeLast('.').let { n ->
        // "Show.S01E03.The.Title.1080p" → "The Title" when there's a name after the code.
        CODE_THEN_NAME.find(n)?.groupValues?.get(2)
            ?.replace(DOTS, " ")?.replace(RELEASE_TAGS, "")?.trim()
            ?.ifBlank { null } ?: n
    }
    return listOfNotNull(code, name.takeIf { it != code }).joinToString(" · ")
}

// Compiled once: every episode card and Continue watching caption works these out as it composes.
private val CODE_SE = Regex("""(?i)s(\d{1,2})\s*e(\d{1,3})""")
private val CODE_X = Regex("""(?i)(?<!\d)(\d{1,2})x(\d{2})(?!\d)""")
private val CODE_THEN_NAME = Regex("""(?i)(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{2})[\s._-]*(.*)""")
private val DOTS = Regex("""[._]""")
private val RELEASE_TAGS = Regex("""(?i)\b(\d{3,4}p|x26[45]|h\.?26[45]|web[- ]?dl|webrip|bluray|hdtv|aac|ddp?5\.1)\b.*""")

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
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // Grows on focus as a layer (tv-material's own growth recomposed it every frame).
        modifier = Modifier.tvFocusMotion(rememberFocusMotionState(), focusedScale = 1.06f).onMenuKey(onLongClick),
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B1220),
        ),
        scale = surfacePressScale(1.06f),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/**
 * An episode: its picture (TMDB's, else Drive's frame from the video), the code and name, a
 * progress bar or a ✓. Used in a show's row of episodes and in a season's folder. [fg] colors its
 * name, focus outline and placeholder symbol: the theme's text color in a season's folder, white on
 * the always-dark title page. [onLongClick] (a long press or the Menu key) opens its menu, if any.
 * The picture is a [TvCard]: growing with the focus redraws it, without recomposing anything.
 */
@Composable
internal fun EpisodeCard(
    ep: DriveFile, token: String?, progress: Float?, watched: Boolean,
    modifier: Modifier = Modifier.width(280.dp),
    fg: Color = com.woodruff.tvexplorer.ui.theme.Palette.text,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Drive's own images need the Google token (and only they ever get it); TMDB's don't.
    val picture = (ep.posterUrl ?: ep.thumbnailLink?.let(DriveApi::big))?.takeIf { !isGoogle(it) || token != null }
    Column(modifier) {
        TvCard(
            onClick = onClick,
            shape = EpisodeShape,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            onLongClick = onLongClick,
            focusedScale = TvMotion.CardScale,
            // The glass behind the play symbol. A picture covers the card, so nothing is painted under
            // it: the glass shows only while it loads (or if it can't), as the picture's stand-in.
            color = if (picture == null) EpisodeGlass else Color.Transparent,
            focusedColor = if (picture == null) Color.White.copy(alpha = 0.2f) else Color.Transparent,
            borderWidth = 3.dp,
            focusedBorderColor = fg,
        ) {
            if (picture != null) {
                val request = remember(picture, token) {
                    ImageRequest.Builder(context).data(picture)
                        .apply { if (isGoogle(picture) && token != null) addHeader("Authorization", "Bearer $token") }
                        .size(400, 225).crossfade(150).build()
                }
                val glass = remember { ColorPainter(EpisodeGlass) }
                AsyncImage(
                    model = request, contentDescription = null, contentScale = ContentScale.Crop,
                    placeholder = glass, error = glass,
                    // The card clips to its rounded shape already: no second clip (and layer) per card.
                    clipToBounds = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else TvIcon(Sym.PlayArrow, size = 40.dp, tint = fg.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
            if (progress != null && progress > 0.01f && !watched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(com.woodruff.tvexplorer.ui.theme.Palette.accent))
            }
            if (watched) Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { TvIcon(Sym.Check, size = 18.dp, tint = Color(0xFF0B1220)) }
        }
        val label = remember(ep.name) { episodeCardLabel(ep.name) }
        Text(label, color = fg, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
    }
}

private val EpisodeShape = RoundedCornerShape(18.dp)
private val EpisodeGlass = Color.White.copy(alpha = 0.1f)

/** Apple TV buttons: frosted glass, turning solid white with dark text when focused. */
@Composable
private fun DetailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        // Grows on focus as a layer (tv-material's own growth recomposed it every frame).
        modifier = modifier.tvFocusMotion(rememberFocusMotionState(), focusedScale = 1.06f),
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B1220),
        ),
        scale = surfacePressScale(1.06f),
    ) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 24.dp)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
        }
    }
}
