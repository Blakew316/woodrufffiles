package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.ui.components.TvIcon
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
    /** Whether a title has its ✓ (a film kept in its own folder: that film's, once known). */
    seen: (DriveFile) -> Boolean = { false },
    /** Changes when ✓s or progress may have changed while here (marked from a menu, or another TV's). */
    marks: Int = 0,
    /** A title taking (true) or losing the focus, banner included: a folder's listing is fetched ahead. */
    onRest: (DriveFile, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    // What's being watched, as of opening — in progress or up next, one per show: the rows keep
    // their titles while you're here, so a film finished meanwhile doesn't pull its tile (and the
    // focus) out from under you...
    val recentAtOpen = remember { RecentStore(context).continueWatching() }
    // ...while the progress bars and ✓s refresh on coming back from the player. (The first resume
    // arrives with the first frame: nothing has changed by then.)
    var resumes by remember { mutableIntStateOf(0) }
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (resumedOnce) resumes++ else resumedOnce = true }
    val progressOf = remember(resumes, marks) { RecentStore(context).all().filter { it.progress < 0.97f }.associate { it.key to it.progress } }

    // The rows, and what each Continue watching tile stands for: the film, or the show's episode in
    // progress or up next.
    val (rows, playing) = remember(files) {
        // An episode whose name doesn't say its show ("S01E013_Pilot"): the show it's known to sit
        // under. Names are matched by keys worked out ahead, off the main thread (matchKeyOf).
        EpisodeShows.load(context)
        val watching = Library.continuing(files, recentAtOpen, EpisodeShows.map.value, ::matchKeyOf).take(20)
        // Then Recently added, Top rated and the genres, each title in one row only.
        Library.overview(files, watching.map { it.first }) to watching.associate { (f, r) -> f.id to r }
    }
    // Featured: five of the library's well-rated titles with wide art, a new five each day.
    val featured = remember(files) { Library.featured(files, Library.today()) }
    // Coming back (from a show's folders, say): the row and title you left from.
    val restore = remember {
        rowsFocus[placeKey]?.let { (row, id) ->
            val r = rows.indexOfFirst { it.first == row }
            val c = rows.getOrNull(r)?.second?.indexOfFirst { it.id == id } ?: -1
            if (r >= 0 && c >= 0) r to c else null
        }
    }
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
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (featured.isNotEmpty()) item(key = "hero") {
            // Focus on the featured title shows all of it (scrolled to the top), never half of it
            // tucked under the header.
            Box(Modifier.onFocusChanged {
                if (it.hasFocus) {
                    rowsFocus.remove(placeKey)
                    // After the focus's own scroll has started (a frame later), so this one wins.
                    scope.launch { withFrameNanos {}; list.animateScrollToItem(0) }
                } else findingFilm?.cancel() // moved on before its film was found: it doesn't start later
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
            item(key = "row:$title") {
                val rowState = rowStates.getOrPut(title) {
                    LazyListState(firstVisibleItemIndex = if (restore != null && restore.first == r) (restore.second - 1).coerceAtLeast(0) else 0)
                }
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
                                progress = if (now?.upNext == true) null else progressOf[now?.key ?: key],
                                watched = seen(f),
                                caption = now?.takeIf { it.upNext }?.let { upNextCaption(it.title) },
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!canTakeFocus()) return@LaunchedEffect
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
 * where it is among them. Only the picture and words cross-fade: the Play and Details buttons stay
 * put (and keep the focus) and always act on the title showing.
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
        androidx.compose.animation.Crossfade(
            current,
            animationSpec = androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "featured",
        ) { f ->
            Box(Modifier.fillMaxSize()) {
                val info = remember(f) { Library.info(f) }
                // The same request as the one fetched ahead, so it's a memory-cache hit.
                val request = remember(f, token) { heroRequest(context, f, token) }
                if (request != null) {
                    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.6f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
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
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color(0xFF0B1220),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 22.dp)
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
