package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Check
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.woodruff.tvexplorer.ui.theme.Shapes
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveState
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.cloud.SignInStep
import com.woodruff.tvexplorer.util.Qr
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.ui.components.FileGlyph
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.color
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.theme.CardStyle
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

@Composable
fun DriveScreen(vm: MainViewModel, drive: DriveViewModel) {
    val state by drive.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<DriveFile?>(null) }
    var searching by remember { mutableStateOf(false) }
    BackHandler { if (!drive.back()) vm.show(Screen.HOME) }

    // Load files, or start the phone sign-in server; stop that server when leaving the screen.
    DisposableEffect(Unit) {
        drive.start()
        onDispose { drive.stop() }
    }
    // Google's access tokens last an hour: keep the screen's current (its art, a show's episodes),
    // on coming back from the player and while the screen stays open.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { drive.refreshToken() }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5 * 60_000L)
            drive.refreshToken()
        }
    }

    if (!state.connected) {
        ConnectPanel(state, onReset = drive::resetSetup)
        return
    }

    val filter = state.filter
    // Movies / TV Shows top level: filtered and sorted; everywhere else as Drive lists it.
    val shown = remember(state.files, state.filterable, filter) {
        if (state.filterable) Library.apply(state.files, filter) else state.files
    }
    // A film in Movies / TV Shows plays on its own: no rolling on into the next title (and the
    // queue is never cut short of the one chosen). In a folder, the media around it queue up after it.
    val onOpen: (DriveFile) -> Unit = { f -> openDriveFile(context, f, if (state.filterable) listOf(f) else shown, drive::open, onOther = { menuFor = it }) }

    var moreOpen by remember { mutableStateOf(false) }
    // A title's detail page (Movies / TV Shows top level): choosing a poster opens it first, as on Apple TV.
    var detailFor by remember { mutableStateOf<DriveFile?>(null) }
    val choose: (DriveFile) -> Unit = { f -> if (state.filterable) detailFor = f else onOpen(f) }
    // The focused poster's art, for the backdrop.
    // Held as a State and read only inside the backdrop: moving between posters then redraws the
    // backdrop alone, not the whole screen and grid.
    val focusedArt = remember(state.place) { mutableStateOf<String?>(null) }
    // Whether the focus is in the header (choosing a category): the posters or rows that the choice
    // brings up then don't take it. Read only when they open, so no redraws when it changes.
    val headerFocused = remember { mutableStateOf(false) }
    if (state.library) PosterBackdrop(focusedArt, state.token)
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = if (state.library) 18.dp else 32.dp)) {
        if (state.library) {
            // Movies / TV Shows: title, filters and the Drive buttons share one short row, so the
            // posters start near the top of the screen.
            if (state.filterable) {
                // The library's top level: title, Search / Filter / More, and category tabs.
                Box(Modifier.onFocusChanged { headerFocused.value = it.hasFocus }) {
                    LibraryHeader(state.place.title, state.files, filter, drive::setFilter, onSearch = { searching = true }, onMore = { moreOpen = true })
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                // A show's folder (seasons, episodes): back, its name, More.
                if (state.stack.size > 1) ToolButton(Icons.Rounded.ArrowBack, null, { drive.back() }, modifier = Modifier.padding(end = 14.dp))
                Text(
                    state.place.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 18.dp).weight(1f),
                )
                ToolButton(Icons.Rounded.MoreHoriz, null, { moreOpen = true }, modifier = Modifier.padding(start = 10.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.brand_google_drive), contentDescription = null, modifier = Modifier.size(32.dp))
                Text(state.place.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                state.stack.joinToString("  ›  ") { it.title },
                color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 18.dp, bottom = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.stack.size > 1) ToolButton(Icons.Rounded.ArrowBack, null, { drive.back() })
                ToolButton(Icons.Rounded.AddToDrive, "My Drive", { drive.openRoot(DrivePlace.MyDrive) })
                ToolButton(Icons.Rounded.Movie, "All videos", { drive.openRoot(DrivePlace.Videos()) })
                ToolButton(Icons.Rounded.FolderShared, "Shared with me", { drive.openRoot(DrivePlace.SharedWithMe) })
                ToolButton(Icons.Rounded.Refresh, null, drive::refresh)
                ToolButton(Icons.Rounded.Logout, null, drive::signOut)
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.busy -> EmptyState("Loading from Google Drive…")
                state.error != null -> EmptyState(state.error!!)
                state.files.isEmpty() -> EmptyState("Nothing here")
                shown.isEmpty() -> EmptyState("No matches")
                // The Overview tab: a featured title and rows. ("All" is the whole library A–Z.)
                state.filterable && filter.overview -> key(state.place) {
                    // One per library: Movies and TV Shows each open at their own place, with their own focus.
                    LibraryRows(
                        state.files, state.token, placeKey = state.place.toString(),
                        isShows = state.stack.firstOrNull() == DrivePlace.TvShows,
                        canTakeFocus = { !headerFocused.value },
                        onChoose = choose, onPlay = onOpen, onMenu = { menuFor = it },
                        onFocusArt = { focusedArt.value = it },
                    )
                }
                else -> {
                    // Back to a list you've been in: the item you left from is focused (and scrolled to)
                    // again, instead of the first one.
                    val placeKey = state.place.toString()
                    val target = remember(state.place) { driveFocus[placeKey]?.takeIf { id -> shown.any { it.id == id } } ?: shown.first().id }
                    val first = remember(state.place) { FocusRequester() }
                    val grid = remember(state.place) { LazyGridState() }
                    // How far through each title is, read once per visit and on coming back from the player (not per tile).
                    var resumes by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    // (The first resume arrives with the first frame, when it was just read: skip it.)
                    var resumedOnce by remember { mutableStateOf(false) }
                    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (resumedOnce) resumes++ else resumedOnce = true }
                    val progressOf = remember(state.place, resumes) {
                        com.woodruff.tvexplorer.player.RecentStore(context).all().filter { it.progress < 0.97f }.associate { it.key to it.progress }
                    }
                    // Load the posters a couple of rows past the focus into the image cache, so they're
                    // already there when the grid scrolls to them instead of popping in.
                    val positions = remember(shown) { shown.withIndex().associate { (i, f) -> f.id to i } }
                    val prefetch: (String) -> Unit = { id ->
                        val at = positions[id]
                        if (at != null) prefetchPosters(context, shown, at + 1, 14, state.token)
                    }
                    LazyVerticalGrid(
                        state = grid,
                        // Movies / TV Shows: tall poster tiles, more per row.
                        columns = GridCells.Adaptive(if (state.library) 150.dp else 230.dp),
                        // Posters lift and grow when focused: leave them room so the edges aren't cut off.
                        contentPadding = if (state.library) PaddingValues(top = 20.dp, bottom = 56.dp, start = 18.dp, end = 18.dp)
                        else PaddingValues(top = 8.dp, bottom = 48.dp, start = 6.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (state.library) 30.dp else 22.dp),
                        verticalArrangement = Arrangement.spacedBy(if (state.library) 30.dp else 22.dp),
                    ) {
                        items(shown, key = { it.id }) { f ->
                            // animateItem: changing the sort or category glides the posters to their new
                            // places (and fades the ones coming and going) instead of jumping.
                            val mod = Modifier.animateItem().then(if (f.id == target) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        driveFocus[placeKey] = f.id
                                        if (state.library) { focusedArt.value = posterArt(f); prefetch(f.id) }
                                    }
                                }
                            if (state.library) PosterTile(
                                f, state.token, onClick = { choose(f) }, onLongClick = { menuFor = f }, modifier = mod,
                                label = if (state.filterable) Library.label(f) else null,
                                progress = progressOf[MediaRef.Drive(f.id).key],
                                watched = !f.isFolder && com.woodruff.tvexplorer.player.Watched.isWatched(context, MediaRef.Drive(f.id).key),
                            )
                            else DriveTile(f, state.token, onClick = { onOpen(f) }, onLongClick = { menuFor = f }, modifier = mod)
                        }
                    }
                    LaunchedEffect(state.place) {
                        val i = shown.indexOfFirst { it.id == target }
                        if (i > 0) runCatching { grid.scrollToItem(i) }
                        withFrameNanos {}
                        // Not while choosing a category in the header: the focus stays on the tabs.
                        if (!headerFocused.value) runCatching { first.requestFocus() }
                    }
                }
            }
        }
    }

    if (moreOpen) {
        ActionMenu(
            title = "Google Drive", subtitle = null, onDismiss = { moreOpen = false },
            actions = listOfNotNull(
                MenuAction("Refresh", Icons.Rounded.Refresh, run = drive::refresh),
                MenuAction("My Drive", Icons.Rounded.AddToDrive) { drive.openRoot(DrivePlace.MyDrive) },
                MenuAction("All videos", Icons.Rounded.Movie) { drive.openRoot(DrivePlace.Videos()) },
                MenuAction("Shared with me", Icons.Rounded.FolderShared) { drive.openRoot(DrivePlace.SharedWithMe) },
                MenuAction("Sign out of Google Drive", Icons.Rounded.Logout, destructive = true, run = drive::signOut),
            ),
        )
    }

    if (searching) {
        LibrarySearch(state.place.title, state.files, state.token, onOpen = { f -> searching = false; choose(f) }, onDismiss = { searching = false })
    }
    // Searching used to narrow the grid itself; it has its own screen now, so an old search left in
    // the saved filter would hide titles with nothing on screen saying why.
    LaunchedEffect(state.place, filter.query) { if (state.filterable && filter.query.isNotBlank()) drive.setFilter(filter.copy(query = "")) }

    detailFor?.let { f ->
        val token = state.token
        val isShow = state.stack.firstOrNull() == DrivePlace.TvShows
        TitleDetail(
            f, posterArt(f), token, all = state.files, isShow = isShow,
            artHeaders = { url -> if (isGoogle(url) && token != null) mapOf("Authorization" to "Bearer $token") else emptyMap() },
            onPlay = { fromStart ->
                detailFor = null
                if (fromStart) playFromStart(context, f, listOf(f)) else onOpen(f)
            },
            onPlayEpisode = { ep, queue, fromStart ->
                detailFor = null
                context.startActivity(PlayerActivity.intent(context, queue.map { MediaRef.Drive(it.id).key }, queue.map { it.name }, MediaRef.Drive(ep.id).key, positionMs = if (fromStart) 0L else null))
            },
            onPlayKey = { key, title ->
                detailFor = null
                context.startActivity(PlayerActivity.intent(context, listOf(key), listOf(title), key))
            },
            openLabel = if (isShow) "All folders" else "Open",
            onChoose = { detailFor = it },
            onOpen = { detailFor = null; onOpen(f) },
            onDismiss = { detailFor = null },
        )
    }

    menuFor?.let { f ->
        ActionMenu(
            title = f.name,
            subtitle = if (f.isFolder) "Folder" else "${Format.bytes(f.size)} · ${Format.date(f.modified)}",
            onDismiss = { menuFor = null },
            actions = buildList {
                if (f.isFolder) add(MenuAction("Open", Icons.Rounded.AddToDrive) { drive.open(DrivePlace.Folder(f.id, f.name)) })
                if (f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) add(MenuAction("Play", Icons.Rounded.PlayArrow) { onOpen(f) })
                if (f.kind == FileKind.IMAGE) add(MenuAction("View", Icons.Rounded.Visibility) { onOpen(f) })
                if (!f.isFolder && !f.isGoogleDoc) add(MenuAction("Download to this TV", Icons.Rounded.Download) { drive.download(f) })
                if (f.isGoogleDoc) add(MenuAction("Google Docs files can't be opened on TV", Icons.Rounded.Visibility) {})
            },
        )
    }
}

/** Asks the image loader for [count] posters from [from] on, at the tiles' size (so they land in the memory cache). */
private fun prefetchPosters(context: Context, files: List<DriveFile>, from: Int, count: Int, token: String?) {
    val loader = coil.Coil.imageLoader(context)
    for (i in from until minOf(files.size, from + count)) {
        val art = posterArt(files[i]) ?: continue
        if (isGoogle(art) && token == null) continue
        loader.enqueue(
            ImageRequest.Builder(context).data(art)
                .apply { if (isGoogle(art) && token != null) addHeader("Authorization", "Bearer $token") }
                .size(320, 480).build(),
        )
    }
}

/** "Start over" on a detail page: the same queue as playing it, but from the beginning. */
private fun playFromStart(context: Context, f: DriveFile, all: List<DriveFile>) {
    val queue = around(f, all.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO })
    context.startActivity(PlayerActivity.intent(context, queue.map { MediaRef.Drive(it.id).key }, queue.map { it.name }, MediaRef.Drive(f.id).key, positionMs = 0L))
}

/**
 * Up to 300 of [items] around [f], always including it. (The first 300 of a long folder left out
 * anything further down, and the player then started the first item instead of the one chosen.)
 */
private fun around(f: DriveFile, items: List<DriveFile>): List<DriveFile> {
    val at = items.indexOfFirst { it.id == f.id }
    return if (at < 0) listOf(f) else items.subList(maxOf(0, at - 150), minOf(items.size, at + 150))
}

/** Folders navigate, media streams in the built-in player/viewer, anything else offers its menu. */
private fun openDriveFile(context: Context, f: DriveFile, all: List<DriveFile>, openFolder: (DrivePlace) -> Unit, onOther: (DriveFile) -> Unit) {
    when (f.kind) {
        FileKind.FOLDER -> openFolder(DrivePlace.Folder(f.id, f.name))
        FileKind.VIDEO, FileKind.AUDIO -> {
            val queue = around(f, all.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO })
            context.startActivity(PlayerActivity.intent(context, queue.map { MediaRef.Drive(it.id).key }, queue.map { it.name }, MediaRef.Drive(f.id).key))
        }
        FileKind.IMAGE -> {
            val images = around(f, all.filter { it.kind == FileKind.IMAGE })
            context.startActivity(ImageViewerActivity.intent(context, images.map { MediaRef.Drive(it.id).key }, MediaRef.Drive(f.id).key, images.map { it.name }))
        }
        else -> onOther(f)
    }
}

/** The artwork a poster tile shows (and the backdrop behind the grid uses): its poster, else the Drive preview frame. */
internal fun posterArt(f: DriveFile): String? = f.posterUrl ?: f.thumbnailLink?.takeIf { f.kind == FileKind.VIDEO }?.let(DriveApi::big)

/**
 * Movie / show poster, Apple TV style: 2:3 artwork that, when focused, lifts off the page: a
 * little bigger, raised, with a soft shadow beneath and a gentle sheen. No tilting or moving
 * art. The lift animates in the draw layer, so it costs no recomposition.
 * Without artwork, a title card in the brand colors.
 */
@Composable
internal fun PosterTile(
    f: DriveFile, token: String?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier, label: String? = null,
    /** How far through it is (0..1), for the bar along the bottom; null when not started. */
    progress: Float? = null,
    /** Watched to the end: a ✓ in the corner. */
    watched: Boolean = false,
) {
    val context = LocalContext.current
    val title = label ?: f.name.substringBeforeLast('.').takeIf { !f.isFolder } ?: f.name
    // Posters already show their title, so the label only appears on focus; frames and title cards always have it.
    val poster = f.posterUrl != null && (token != null || !isGoogle(f.posterUrl))
    var focused by remember { mutableStateOf(false) }
    // 0 → 1: how far "up" the tile is.
    val lift = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        // A soft spring rather than a timed curve: it rises and settles like something with weight.
        if (focused) lift.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
        else lift.animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium))
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // Drawn above its neighbours while lifted, so they don't cover its grown edges.
        modifier = modifier.zIndex(if (focused) 1f else 0f).onMenuKey(onLongClick).aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused }
            // The shadow it casts on the page: a soft dark oval below the tile, painted from a
            // gradient made once (cheaper than a real elevation shadow while it animates).
            .drawWithCache {
                val shadow = Brush.radialGradient(
                    listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.25f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.62f),
                    radius = size.maxDimension * 0.7f,
                )
                onDrawBehind {
                    val l = lift.value.coerceIn(0f, 1f)
                    if (l > 0.01f) drawOval(
                        shadow, alpha = l,
                        topLeft = androidx.compose.ui.geometry.Offset(-size.width * 0.22f, size.height * 0.08f + 14.dp.toPx() * l),
                        size = androidx.compose.ui.geometry.Size(size.width * 1.44f, size.height * 1.08f),
                    )
                }
            }
            .graphicsLayer {
                // Lifted: a little bigger and raised off its shadow.
                val sc = 1f + 0.12f * lift.value
                scaleX = sc; scaleY = sc
                translationY = -6.dp.toPx() * lift.value
            },
        shape = CardStyle.shape(),
        colors = CardStyle.colors(),
        // No focus outline or extra zoom: the lift is the focus.
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = Shapes.card),
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), shape = Shapes.card),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.background)))
                // The sheen: a soft highlight near the top while lifted. The gradient is made once per
                // size (no allocations while animating).
                .drawWithCache {
                    val r = size.maxDimension * 0.75f
                    val glint = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent), center = androidx.compose.ui.geometry.Offset.Zero, radius = r)
                    onDrawWithContent {
                        drawContent()
                        val l = lift.value.coerceIn(0f, 1f) * 0.6f
                        if (l > 0.01f) {
                            val cx = size.width * 0.5f
                            val cy = size.height * 0.2f
                            translate(cx, cy) { drawCircle(glint, radius = r, center = androidx.compose.ui.geometry.Offset.Zero, alpha = l) }
                        }
                    }
                },
        ) {
            val art = posterArt(f)
            // The art stays put inside the frame; only the whole tile lifts.
            val depth = Modifier.fillMaxSize()
            if (art == null || (token == null && isGoogle(art))) {
                // Title card: large initial and the name, so tiles stay readable without art.
                Text(
                    title.trim().take(1).uppercase(), style = MaterialTheme.typography.displayLarge,
                    color = Palette.accent.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center),
                )
            } else if (LocalPosterPainter.current != null) {
                LocalPosterPainter.current!!(art)?.let {
                    androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = depth)
                }
            } else {
                // Built once per tile (not on every focus change that recomposes it).
                val request = remember(art, token) {
                    ImageRequest.Builder(context).data(art)
                        // The Google token only ever goes to Google's own image servers, never to TMDB.
                        .apply { if (isGoogle(art) && token != null) addHeader("Authorization", "Bearer $token") }
                        .size(320, 480).crossfade(150).build() // the tile's real size on a 1080p TV: faster decode, less memory
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = depth,
                )
            }
            if (!poster || focused) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Part-watched: a bar along the bottom edge, Netflix style.
            if (progress != null && progress > 0.01f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.accent))
            }
            // Watched: a ✓ badge top right.
            if (watched) Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) { TvIcon(Icons.Rounded.Check, size = 18.dp, tint = Color(0xFF0B1220)) }
        }
    }
}

/**
 * Behind Movies / TV Shows: the focused title's art, blown up and dimmed, so the posters float
 * over their own picture. The art is fetched tiny (48×72) and stretched, which makes it soft by
 * itself, without a full-screen blur effect that slow TV graphics chips stutter on. Changes
 * settle for a moment first, so fast scrolling doesn't keep swapping it.
 */
@Composable
private fun PosterBackdrop(art: androidx.compose.runtime.State<String?>, token: String?) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<String?>(null) }
    // Keyed by the State: each place has its own (keyed on Unit, it kept watching the first one and
    // the backdrop froze after the first folder). collectLatest: it changes once the focus rests,
    // not every 350 ms while you scroll (each change decodes and cross-fades a full-screen image).
    LaunchedEffect(art) {
        androidx.compose.runtime.snapshotFlow { art.value }.collectLatest { kotlinx.coroutines.delay(350); shown = it }
    }
    androidx.compose.animation.Crossfade(shown, animationSpec = tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "backdrop") { url ->
        if (url != null) {
            val painter = LocalPosterPainter.current
            if (painter != null) painter(url)?.let {
                androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else AsyncImage(
                model = ImageRequest.Builder(context).data(url)
                    .apply { if (isGoogle(url) && token != null) addHeader("Authorization", "Bearer $token") }
                    .size(48, 72).build(),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    // Dim it towards the page color, darker at the top where the header sits. Outside the cross-fade,
    // so only the picture fades, not a second veil.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.background.copy(alpha = 0.92f), Palette.background.copy(alpha = 0.66f), Palette.background.copy(alpha = 0.88f)))))
}

/**
 * Search, Apple TV style: its own full screen with a big search field at the top and the matching
 * posters below, updating with every letter (TV keyboard or the phone's Keyboard page). Down goes
 * from the field into the results; OK opens one; Back closes.
 */
@Composable
private fun LibrarySearch(title: String, all: List<DriveFile>, token: String?, onOpen: (DriveFile) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val field = remember { FocusRequester() }
    val results = remember(all, query.text) {
        if (query.text.isBlank()) emptyList()
        else Library.apply(all, com.woodruff.tvexplorer.cloud.LibraryFilter(query = query.text.trim()))
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        com.woodruff.tvexplorer.ui.components.FocusWhenReady(field)
        com.woodruff.tvexplorer.ui.ScreenEnter {
        Box(Modifier.fillMaxSize().ambientBackdrop()) {
            Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvIcon(Icons.Rounded.Search, size = 34.dp, tint = Palette.muted)
                    com.woodruff.tvexplorer.ui.components.RemoteTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Search $title",
                        placeholder = "Search $title",
                        onSubmit = {},
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        textModifier = Modifier.focusRequester(field),
                    )
                }
                Text(
                    when {
                        query.text.isBlank() -> "Type a title or a year. You can also type on your phone (Phone → Keyboard)."
                        results.isEmpty() -> "Nothing in $title matches \u201c${query.text.trim()}\u201d"
                        else -> "${results.size} result${if (results.size == 1) "" else "s"}"
                    },
                    color = Palette.muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(top = 22.dp, bottom = 56.dp, start = 18.dp, end = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { it.id }) { f ->
                        PosterTile(f, token, onClick = { onOpen(f) }, onLongClick = { onOpen(f) }, modifier = Modifier.animateItem(), label = Library.label(f))
                    }
                }
            }
        }
        }
    }
}

/** Only set by the UI gallery, which renders headless and can't wait on Coil: decodes art synchronously. */
internal val LocalPosterPainter = androidx.compose.runtime.staticCompositionLocalOf<((String) -> androidx.compose.ui.graphics.painter.Painter?)?> { null }

internal fun isGoogle(url: String): Boolean = com.woodruff.tvexplorer.cloud.isGoogleUrl(url)

@Composable
private fun DriveTile(f: DriveFile, token: String?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onMenuKey(onLongClick),
        shape = CardStyle.shape(),
        colors = CardStyle.colors(),
        border = CardStyle.border(),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Palette.background),
                contentAlignment = Alignment.Center,
            ) {
                FileGlyph(f.name, f.kind, size = 64.dp)
                val thumb = f.thumbnailLink
                if (thumb != null && token != null && isGoogle(thumb)) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(thumb).addHeader("Authorization", "Bearer $token").build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (f.kind == FileKind.VIDEO) {
                    Box(
                        Modifier.align(Alignment.Center).size(44.dp).clip(RoundedCornerShape(50)).background(Palette.background.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) { TvIcon(Icons.Rounded.PlayArrow, size = 28.dp, tint = Palette.text) }
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (f.isFolder) "Folder" else listOfNotNull(Format.bytes(f.size).takeIf { f.size > 0 }, Format.date(f.modified)).joinToString(" · "),
                    maxLines = 1, color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Phone sign-in screen. TVs give apps only ~960x540dp, so the QR code is laid out first at a
 * fixed size and the text takes whatever width is left (scrolling if needed). Never the reverse,
 * or the QR gets squeezed off-screen.
 */
@Composable
private fun ConnectPanel(state: DriveState, onReset: () -> Unit) {
    val setup = state.step == SignInStep.SETUP
    Row(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(QR_SIZE).clip(RoundedCornerShape(16.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val url = state.qrUrl
            if (url != null) {
                val qr = remember(url) { Qr.bitmap(url, 640).asImageBitmap() }
                Image(qr, contentDescription = "QR code", modifier = Modifier.fillMaxSize())
            } else {
                Text(if (state.error != null) "No QR code:\nsee the message" else "Preparing…", color = Color.Black)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.brand_google_drive), contentDescription = null, modifier = Modifier.size(32.dp))
                Text(
                    if (setup) "Set up Google Drive" else "Sign in with your phone",
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(if (setup) "Step 1 of 2" else "Step 2 of 2", color = Palette.accent, style = MaterialTheme.typography.labelLarge)
            Text(
                if (setup) "Scan the QR code with your phone (same Wi-Fi as this TV). It opens Settings, where you paste the Client ID " +
                    "and Client secret of your Google Cloud \"Web application\" OAuth client. You can also type them in Settings on the TV."
                else "Scan the QR code with your phone and sign in to Google. Keep the phone on the same Wi-Fi as this TV.",
                color = Palette.muted, style = MaterialTheme.typography.bodyMedium,
            )
            if (setup) state.setupAddress?.let {
                Text("Or open this on a computer:", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                Text(it, color = Palette.accent, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.busy) Text("Finishing sign-in…", color = Palette.accent)
            state.error?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodyMedium) }
            if (!setup) OutlinedButton(onClick = onReset) { Text("Use a different Google Cloud client") }
        }
    }
}

private val QR_SIZE = 250.dp

/** Last focused item per Drive list (by place), for coming back to it with Back. */
private val driveFocus = java.util.concurrent.ConcurrentHashMap<String, String>()
