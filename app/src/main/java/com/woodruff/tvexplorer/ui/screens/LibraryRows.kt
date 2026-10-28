package com.woodruff.tvexplorer.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Shapes
import java.util.Calendar
import kotlinx.coroutines.launch

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
) {
    val context = LocalContext.current
    // What's being watched, as of opening: the rows keep their titles while you're here, so a film
    // finished meanwhile doesn't pull its tile (and the focus) out from under you...
    val recentAtOpen = remember { RecentStore(context).all().filter { it.progress in 0.02f..0.97f } }
    // ...while the progress bars and ✓s refresh on coming back from the player. (The first resume
    // arrives with the first frame: nothing has changed by then.)
    var resumes by remember { mutableIntStateOf(0) }
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (resumedOnce) resumes++ else resumedOnce = true }
    val progressOf = remember(resumes) { RecentStore(context).all().filter { it.progress < 0.97f }.associate { it.key to it.progress } }

    val rows = remember(files) {
        val byKey = files.associateBy { MediaRef.Drive(it.id).key }
        // A film you're part-way through, or a show (or a film in its own folder) by name — the
        // folders' names are only worked out if something isn't found by its own key.
        val byName by lazy { files.filter { it.isFolder }.associateBy { Library.showMatchKey(it.name) } }
        val continuing = recentAtOpen.mapNotNull { r -> byKey[r.key] ?: byName[Library.showMatchKey(r.title)] }.distinctBy { it.id }
        val recentlyAdded = files.sortedByDescending { it.modified }.take(20)
        val rated = files.filter { Library.info(it).rating != null }.sortedByDescending { Library.info(it).rating }
        buildList {
            if (continuing.isNotEmpty()) add("Continue watching" to continuing.take(20))
            add("Recently added" to recentlyAdded)
            if (rated.size >= 5) add("Top rated" to rated.take(20))
            Library.genres(files).take(8).forEach { g ->
                val inGenre = files.filter { g in Library.info(it).genres }
                if (inGenre.size >= 4) add(g to inGenre.sortedByDescending { Library.info(it).rating ?: 0.0 }.take(24))
            }
        }
    }
    // Featured: five of the library's well-rated titles with wide art, picked at random — a new
    // five each day (the same five all day, on every TV).
    val featured = remember(files) {
        val pool = files.filter { Library.info(it).backdrop != null }
        val good = pool.filter { (Library.info(it).rating ?: 0.0) >= 6.5 }.let { if (it.size >= 5) it else pool }
        val today = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }
        good.shuffled(java.util.Random(today)).take(5)
    }
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
                }
            }) {
                HeroCarousel(
                    featured, token, isShows, heroFocus,
                    onPlay = { f -> if (f.isFolder) onChoose(f) else onPlay(f) },
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
                                    },
                                label = Library.label(f),
                                progress = progressOf[key],
                                watched = !f.isFolder && Watched.isWatched(context, key),
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
 * The featured titles, Apple TV style: one at a time across the top — its wide art in a rounded
 * frame, name, year and genres, synopsis — gliding to the next every 8 seconds, with dots for
 * where it is among them. Only the picture and words cross-fade: the Play and Details buttons stay
 * put (and keep the focus) and always act on the title showing.
 */
@Composable
private fun HeroCarousel(
    items: List<DriveFile>, token: String?, isShows: Boolean, focus: FocusRequester,
    onPlay: (DriveFile) -> Unit, onDetails: (DriveFile) -> Unit,
) {
    val context = LocalContext.current
    var index by remember(items) { mutableIntStateOf(0) }
    LaunchedEffect(items) {
        while (items.size > 1) {
            kotlinx.coroutines.delay(8_000)
            index = (index + 1) % items.size
        }
    }
    val current = items[index.coerceIn(0, items.lastIndex)]
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(300.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black)) {
        androidx.compose.animation.Crossfade(
            current,
            animationSpec = androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "featured",
        ) { f ->
            Box(Modifier.fillMaxSize()) {
                val info = remember(f) { Library.info(f) }
                val art = info.backdrop ?: posterArt(f)
                if (art != null && (token != null || !isGoogle(art))) {
                    val request = remember(art, token) {
                        ImageRequest.Builder(context).data(art)
                            .apply { if (isGoogle(art) && token != null) addHeader("Authorization", "Bearer $token") }
                            .size(1280, 720).build()
                    }
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
            HeroButton(Icons.Rounded.PlayArrow, if (current.isFolder && isShows) "Episodes" else "Play", Modifier.focusRequester(focus)) { onPlay(current) }
            HeroButton(Icons.Rounded.Info, "Details", Modifier) { onDetails(current) }
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
