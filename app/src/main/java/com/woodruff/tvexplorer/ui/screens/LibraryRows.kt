package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.bringWholeIntoView
import com.woodruff.tvexplorer.ui.motion.posterRowState
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState
import com.woodruff.tvexplorer.ui.motion.tvFocusMotion
import com.woodruff.tvexplorer.ui.theme.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the focus was in each library's rows (row name to title id), to put it back on return. */
private val rowsFocus = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

/**
 * Movies / TV Shows as a streaming service shows them: a featured title across the top, then rows
 * — Continue watching, Recently added, Top rated, and one per genre. Shown when nothing is filtered;
 * picking a category or a sort switches to the full grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryRows(
    files: List<DriveFile>,
    token: String?,
    /** Which library this is, for putting the focus back where it was. */
    placeKey: String,
    /** TV Shows (its folders are shows) rather than Movies. */
    isShows: Boolean,
    /** Whether to take the focus on opening: not while it's in the header (choosing a category). */
    canTakeFocus: () -> Boolean,
    onChoose: (DriveFile) -> Unit,
    /** Play a film straight away (the featured title's Play). */
    onPlay: (DriveFile) -> Unit,
    onMenu: (DriveFile) -> Unit,
    /** The focused title's art, for the backdrop behind the screen. */
    onFocusArt: (String?) -> Unit,
    /**
     * Whether a title has its ✓ (a film kept in its own folder: that film's, once known). Asked by
     * each tile as it composes, so a ✓ changing redraws the tiles (see DriveScreen), not the rows.
     */
    seen: (DriveFile) -> Boolean = { false },
    /** Changes when ✓s or progress may have changed while here (marked from a menu, or another TV's). */
    marks: State<Int>,
    /** A title taking (true) or losing the focus, banner included: a folder's listing is fetched ahead. */
    onRest: (DriveFile, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    // Whether to take the focus once the rows are in: decided as the Overview opens. The rows can
    // arrive a few frames later, and by then the window may have put the focus in the header itself
    // (a screen chosen in the side menu gets it after two frames). Read without observing it.
    val takeFocus = remember { Snapshot.withoutReadObservation { canTakeFocus() } }
    // What's being watched, as of opening — in progress or up next, one per show: the rows keep
    // their titles while you're here, so a film finished meanwhile doesn't pull its tile (and the
    // focus) out from under you...
    val recentAtOpen = remember { RecentStore(context).continueWatching() }
    // ...while the progress bars refresh on coming back from the player and when ✓s change. Read by
    // the tiles alone: a change redraws them, not the rows.
    val progress = rememberProgress(marks)
    // The rows, what each Continue watching tile stands for, and the featured titles, worked out on a
    // background thread (sorting a big library into rows held the main thread for frames on opening);
    // again when the listing changes (TMDB's details arriving), the rows shown meanwhile staying put.
    // Coming back to the same listing (from a show's folders, say), the rows worked out last time for
    // it show at once, with nothing worked out again.
    val page by produceState(remember { lastOverview[placeKey]?.takeIf { it.isFor(files, recentAtOpen) }?.page }, files) {
        if (value != null && lastOverview[placeKey]?.isFor(files, recentAtOpen) == true) return@produceState
        val day = Library.today()
        value = withContext(Dispatchers.Default) {
            // An episode whose name doesn't say its show ("S01E013_Pilot"): the show it's known to sit
            // under. Names are matched by keys worked out ahead, off the main thread (matchKeyOf).
            EpisodeShows.load(context)
            val shows = EpisodeShows.map.value
            overviewPage(files, recentAtOpen, shows, ::matchKeyOf, day).also { lastOverview[placeKey] = WorkedOut(files, recentAtOpen, shows, day, it) }
        }
    }
    // Shown once both are in, a frame or two after opening, so the tiles come with their bars. The
    // place to restore and the list's position are worked out from these first rows.
    val progressRead by remember { derivedStateOf { progress.value != null } }
    val shown = page
    if (shown == null || !progressRead) return
    val rows = shown.rows
    val playing = shown.playing
    val featured = shown.featured
    // Coming back (from a show's folders, say): the row and title you left from.
    val restore = remember { restoreAt(rows, rowsFocus[placeKey]) }
    val heroFocus = remember { FocusRequester() }
    val firstTile = remember { FocusRequester() }
    val restoreTile = remember { FocusRequester() }
    val heroRows = if (featured.isNotEmpty()) 1 else 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = restore?.let { it.first + heroRows } ?: 0)
    val scope = rememberCoroutineScope()
    // A featured folder being looked in for its film: OK pressed again meanwhile doesn't play it twice.
    var findingFilm by remember { mutableStateOf<Job?>(null) }
    // Each row's scroll position, kept while it's off screen.
    val rowStates = remember { HashMap<String, LazyListState>() }
    // The column follows the focus as the app's lists do (keeping it clear of the edges), but only as
    // far as it can scroll: back up on the banner, the one scroll comes to rest at the top rather
    // than aiming past it and stopping dead there. The rows set their own (PivotScroll, below).
    val appScroll = LocalBringIntoViewSpec.current
    val columnScroll = remember(appScroll, list) { WithinEnds(appScroll, list) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides columnScroll) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (featured.isNotEmpty()) item(key = "hero", contentType = "hero") {
                // Focus on the featured title shows all of it, never half of it tucked under the header:
                // in the one scroll the focus makes (the banner asks to be shown whole), not a second
                // scroll to the top after it.
                Box(Modifier.bringWholeIntoView().onFocusChanged {
                    if (it.hasFocus) rowsFocus.remove(placeKey)
                    else findingFilm?.cancel() // moved on before its film was found: it doesn't start later
                }) {
                    HeroCarousel(
                        featured, token, isShows, heroFocus, onRest = onRest,
                        onPlay = { f ->
                            if (!f.isFolder) onPlay(f)
                            else if (isShows) onChoose(f)
                            // A film kept in its own folder (Plex style): play it, as its title page
                            // does; that page instead when the folder has no video or can't be listed.
                            else if (findingFilm?.isActive != true) findingFilm = scope.launch {
                                val film = filmIn(context, token, f)
                                if (film != null) onPlay(film) else onChoose(f)
                            }
                        },
                        onDetails = onChoose,
                    )
                }
            }
            rows.forEachIndexed { r, (title, items) ->
                item(key = "row:$title", contentType = "row") {
                    // The row's whole visible width is composed ahead when the column prefetches it. Back
                    // in a row: the title left from shows first, where the focus rests in a row (below),
                    // so it takes the focus without the row moving at all.
                    val rowState = rowStates.getOrPut(title) {
                        posterRowState(firstVisibleItemIndex = if (restore != null && restore.first == r) restore.second else 0)
                    }
                    // The focused poster rests at the row's leading edge, in line with its title and the
                    // first poster, as in the Apple TV app: each step along glides the whole row instead
                    // of walking the focus to the far edge first (and the row eases into its end).
                    PivotScroll(rowState, Pivot(offset = 18.dp), TvMotion.PosterRowGlide) {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, bottom = 2.dp))
                            LazyRow(
                                state = rowState,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(26.dp),
                            ) {
                                itemsIndexed(items, key = { _, f -> f.id }) { c, f ->
                                    val key = MediaRef.Drive(f.id).key
                                    // In Continue watching: what the tile stands for — its progress, or what's next.
                                    val now = playing[f.id]
                                    val requester = when {
                                        restore != null && restore.first == r && restore.second == c -> restoreTile
                                        r == 0 && c == 0 -> firstTile
                                        else -> null
                                    }
                                    PosterTile(
                                        f, token, onClick = { onChoose(f) }, onLongClick = { onMenu(f) },
                                        modifier = Modifier.width(150.dp)
                                            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    rowsFocus[placeKey] = title to f.id
                                                    onFocusArt(posterArt(f))
                                                }
                                                onRest(f, it.isFocused)
                                            },
                                        label = Library.label(f),
                                        // Both read here, in the tile: a ✓ or a bar changing redraws the tiles alone.
                                        progress = if (now?.upNext == true) null else progress.value?.get(now?.key ?: key),
                                        watched = seen(f),
                                        caption = now?.takeIf { it.upNext }?.let { upNextCaption(it.title) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        val target = when {
            restore != null -> restoreTile
            featured.isNotEmpty() -> heroFocus
            else -> firstTile
        }
        repeat(10) {
            withFrameNanos {}
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/**
 * [spec] (the app's way of following the focus) for [list], but never past the list's ends: a
 * distance it can't scroll stopped the animation dead where the list ran out (as PivotScroll's
 * lists, it now eases into the end instead).
 */
@OptIn(ExperimentalFoundationApi::class)
private class WithinEnds(private val spec: BringIntoViewSpec, private val list: LazyListState) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = spec.scrollAnimationSpec

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        val forward = when {
            last == null -> 0f
            last.index < info.totalItemsCount - 1 -> Float.POSITIVE_INFINITY
            else -> (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat().coerceAtLeast(0f)
        }
        val backward = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else Float.POSITIVE_INFINITY
        return spec.calculateScrollDistance(offset, size, containerSize).coerceIn(-backward, forward)
    }
}

/** The Overview last worked out for each library (by its place), and what it was worked out from. */
private val lastOverview = java.util.concurrent.ConcurrentHashMap<String, WorkedOut>()

private class WorkedOut(val files: List<DriveFile>, val recent: List<RecentItem>, val shows: Map<String, String>, val day: Long, val page: OverviewPage) {
    /** Whether it's what [overviewPage] would give now: the same listing, watching and day. */
    fun isFor(files: List<DriveFile>, recent: List<RecentItem>): Boolean =
        this.files === files && this.recent == recent && shows === EpisodeShows.map.value && day == Library.today()
}

/** The Overview as it shows: its rows, what each Continue watching tile stands for, and the featured titles. */
internal class OverviewPage(
    val rows: List<Pair<String, List<DriveFile>>>,
    /** Continue watching's tiles by title id: the film itself, or the show's episode in progress or up next. */
    val playing: Map<String, RecentItem>,
    val featured: List<DriveFile>,
)

/**
 * The Overview of [files]: Continue watching (what's in [recent], newest first, found in the library
 * as [Library.continuing] finds it; up to 20), then Recently added, Top rated and the genres; and the
 * five featured titles of [day]. Pure, so it can run on a background thread (and is unit tested).
 */
internal fun overviewPage(
    files: List<DriveFile>, recent: List<RecentItem>, shows: Map<String, String>, matchKey: (String) -> String, day: Long,
): OverviewPage {
    val watching = Library.continuing(files, recent, shows, matchKey).take(20)
    return OverviewPage(
        Library.overview(files, watching.map { it.first }),
        watching.associate { (f, r) -> f.id to r },
        Library.featured(files, day),
    )
}

/**
 * Where the focus goes back to in [rows], as (row, title) indexes: [saved] is the row's name and the
 * title's id as last focused. Null when there's none, or that row or title is gone.
 */
internal fun restoreAt(rows: List<Pair<String, List<DriveFile>>>, saved: Pair<String, String>?): Pair<Int, Int>? {
    val (row, id) = saved ?: return null
    val r = rows.indexOfFirst { it.first == row }
    val c = rows.getOrNull(r)?.second?.indexOfFirst { it.id == id } ?: -1
    return if (r >= 0 && c >= 0) r to c else null
}

/**
 * The film kept in [folder], picked as its title page picks it ([folderFilm]); null for a collection
 * or a film in parts, when there's no video, or when the folder can't be listed. Its listing as
 * last seen when there is one (fetched ahead while the banner had the focus), else listed now.
 */
private suspend fun filmIn(context: Context, token: String?, folder: DriveFile): DriveFile? =
    (FolderCache.inMemory(folder.id) ?: withContext(Dispatchers.IO) { FolderCache.peek(context, folder.id) } ?: FolderCache.list(context, token, folder))
        ?.let(::folderFilm)

/**
 * The banner's picture of [f] — its wide art, else its poster — at the size the banner shows it;
 * null when there's none it may show (Drive-hosted art needs the token, which only Google gets).
 */
private fun heroRequest(context: Context, f: DriveFile, token: String?): ImageRequest? {
    val art = Library.info(f).backdrop ?: posterArt(f) ?: return null
    if (token == null && isGoogle(art)) return null
    return ImageRequest.Builder(context).data(art)
        .apply { if (isGoogle(art) && token != null) addHeader("Authorization", "Bearer $token") }
        .size(1280, 720).build()
}

/**
 * The featured titles, Apple TV style: one at a time across the top — its wide art in a rounded
 * frame, name, year and genres, synopsis — gliding to the next every 12 seconds, with dots for
 * where it is among them. Only the picture and words cross-fade (each faded as it's drawn, see
 * [FadeBetween]); the shade over the picture stays put, and the Play and Details buttons stay put
 * (and keep the focus) and always act on the title showing.
 */
@Composable
private fun HeroCarousel(
    items: List<DriveFile>, token: String?, isShows: Boolean, focus: FocusRequester,
    /** The title showing taking (true) or losing the focus with the banner: a folder's listing is fetched ahead. */
    onRest: (DriveFile, Boolean) -> Unit,
    onPlay: (DriveFile) -> Unit, onDetails: (DriveFile) -> Unit,
) {
    val context = LocalContext.current
    var index by remember(items) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val latestToken by rememberUpdatedState(token)
    // Paused while a title's page, a menu or the filters cover it (they take the window's focus):
    // unseen, it went on loading and cross-fading art behind them.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(items, windowFocused) {
        while (windowFocused && items.size > 1) {
            kotlinx.coroutines.delay(10_000)
            // The next title's picture, fetched a couple of seconds ahead, so it fades in whole.
            heroRequest(context, items[(index + 1) % items.size], latestToken)?.let { coil.Coil.imageLoader(context).enqueue(it) }
            kotlinx.coroutines.delay(2_000) // a calm pace: 12 s a title
            index = (index + 1) % items.size
        }
    }
    val current = items[index.coerceIn(0, items.lastIndex)]
    // The title showing while the banner has the focus: a folder's listing is fetched ahead, so its
    // Play finds the film at once (and its page opens complete).
    DisposableEffect(current, focused) {
        if (focused) onRest(current, true)
        onDispose { if (focused) onRest(current, false) }
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(300.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black)
            .onFocusChanged { focused = it.hasFocus },
    ) {
        val fade = remember { androidx.compose.animation.core.tween<Float>(700, easing = androidx.compose.animation.core.FastOutSlowInEasing) }
        FadeBetween(current, fade, label = "featured") { f ->
            // The same request as the one fetched ahead, so it's a memory-cache hit.
            val request = remember(f, token) { heroRequest(context, f, token) }
            if (request != null) {
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        // The same shade under every title's words: drawn once, outside the fades.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.6f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
        FadeBetween(current, fade, label = "featured words") { f ->
            val info = remember(f) { Library.info(f) }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.55f).padding(start = 36.dp, bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(info.year?.toString(), info.genres.take(2).joinToString(", ").ifBlank { null }, info.rating?.let { "★ %.1f".format(it) })
                    if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
                    info.overview?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HeroButton(Sym.PlayArrow, if (current.isFolder && isShows) "Episodes" else "Play", Modifier.focusRequester(focus)) { onPlay(current) }
            HeroButton(Sym.Info, "Details", Modifier) { onDetails(current) }
        }
        if (items.size > 1) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.indices.forEach { i ->
                Box(
                    Modifier.size(if (i == index) 10.dp else 8.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.White.copy(alpha = if (i == index) 1f else 0.35f)),
                )
            }
        }
    }
}

@Composable
private fun HeroButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        // Grows on focus as a layer (tv-material's own growth recomposed it every frame).
        modifier = modifier.tvFocusMotion(rememberFocusMotionState(), focusedScale = 1.06f),
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color(0xFF0B1220),
        ),
        scale = surfacePressScale(1.06f),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 22.dp)
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
