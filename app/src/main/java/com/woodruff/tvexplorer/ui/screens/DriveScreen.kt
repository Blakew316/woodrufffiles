package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import android.content.Context
import androidx.compose.foundation.layout.height
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
import com.woodruff.tvexplorer.ui.theme.Shapes
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.LinearEasing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveState
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.cloud.SignInStep
import com.woodruff.tvexplorer.util.Qr
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.ui.components.FileGlyph
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.motion.FocusMotionState
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.DialogWindowMotion
import com.woodruff.tvexplorer.ui.motion.WindowMotion
import com.woodruff.tvexplorer.ui.motion.dpadGate
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState
import com.woodruff.tvexplorer.ui.motion.tvFocusMotion
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

@Composable
fun DriveScreen(vm: MainViewModel, drive: DriveViewModel) {
    // Without the download progress and messages (AppRoot shows those): a download's progress,
    // every 150 ms, no longer recomposes this screen and its posters.
    val state by drive.screen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<DriveFile?>(null) }
    var searching by remember { mutableStateOf(false) }
    // Back goes up a folder; at a library's top it brings up the sidebar, as on Apple TV.
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler { if (!drive.back()) openSidebar() }

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
    // Read once here and handed down: the tiles don't read the screen's state themselves, so a
    // change to it recomposes them only when what they show changed.
    val token = state.token
    val library = state.library
    val filterable = state.filterable
    // Movies, where a folder is a film kept in it (in TV Shows, a show or a season).
    val films = state.stack.firstOrNull() == DrivePlace.Movies
    // Mark as watched / unwatched (Watched can't be observed) and another TV's ✓s and progress
    // merged in: either way the posters' ✓s and progress bars are read again — by the tiles, which
    // read this count as they compose, so a ✓ redraws them and not the grid or the rows around them.
    val marks = remember { mutableIntStateOf(0) }
    val synced = FamilySync.updates.collectAsStateWithLifecycle()
    val marked = remember { derivedStateOf { marks.intValue + synced.value } }
    // Reads [marked] as well: a tile asking while it composes is composed again when a ✓ changes.
    val seen: (DriveFile) -> Boolean = remember(films) { { f: DriveFile -> marked.value; posterWatched(context, f, films) } }
    // Movies / TV Shows top level: filtered and sorted; everywhere else as Drive lists it.
    val shown = remember(state.files, filterable, filter, films) {
        when {
            !filterable -> state.files
            // The Overview shows rows, worked out on their own: no sorting the whole library for it.
            filter.overview -> state.files
            // As watched when it was chosen: a ✓ marked meanwhile doesn't pull a title (and the focus) away.
            filter.unwatched -> Library.apply(state.files, filter, watchedFor(context, state.files, films))
            else -> Library.apply(state.files, filter)
        }
    }
    // For the handlers below, which read it when they run: they stay the same lambdas when it
    // changes, so the tiles holding them aren't recomposed for it.
    val latestShown by rememberUpdatedState(shown)
    // A film in Movies / TV Shows plays on its own: no rolling on into the next title (and the
    // queue is never cut short of the one chosen). In a folder, the media around it queue up after it.
    val onOpen: (DriveFile) -> Unit = { f ->
        // An episode opened inside a show's folders: remember the show for the ones queued with it.
        val showFolder = (state.stack.getOrNull(1) as? DrivePlace.Folder)?.takeIf { state.stack.firstOrNull() == DrivePlace.TvShows }
        if (showFolder != null && f.kind == FileKind.VIDEO) {
            com.woodruff.tvexplorer.cloud.EpisodeShows.remember(context, latestShown.filter { it.kind == FileKind.VIDEO }.associate { MediaRef.Drive(it.id).key to showFolder.id })
        }
        openDriveFile(context, f, if (state.filterable) listOf(f) else latestShown, drive::open, onOther = { menuFor = it })
    }

    var moreOpen by remember { mutableStateOf(false) }
    // In the Filter panel at a library's top; the More menu in a show's folders. Made once: a new
    // list on every recomposition recomposed the header with it.
    val driveActions = remember(drive) {
        listOf(
            MenuAction("Refresh", Sym.Refresh, run = drive::refresh),
            MenuAction("My Drive", Sym.AddToDrive) { drive.openRoot(DrivePlace.MyDrive) },
            MenuAction("All videos", Sym.Movie) { drive.openRoot(DrivePlace.Videos()) },
            MenuAction("Shared with me", Sym.FolderShared) { drive.openRoot(DrivePlace.SharedWithMe) },
            MenuAction("Sign out of Google Drive", Sym.Logout, destructive = true, run = drive::signOut),
        )
    }
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
    // The folder (a show, a film kept in its own folder) the focus rests on at a library's top: after
    // half a second its listing is fetched — just the listing — so its title page, or the banner's
    // Play, has it at once. Moving on cancels it.
    val restingOn = remember(state.place) { mutableStateOf<DriveFile?>(null) }
    val rest: (DriveFile, Boolean) -> Unit = { f, focused ->
        if (focused) restingOn.value = f.takeIf { it.isFolder }
        else if (restingOn.value?.id == f.id) restingOn.value = null
    }
    LaunchedEffect(state.place) {
        androidx.compose.runtime.snapshotFlow { restingOn.value }.collectLatest { f ->
            if (f == null || FolderCache.inMemory(f.id) != null) return@collectLatest
            kotlinx.coroutines.delay(500)
            // Listed before (saved on disk): that copy will do, the title page refreshes it.
            val listed = withContext(Dispatchers.IO) { FolderCache.peek(context, f.id) }
                ?: FolderCache.list(context, state.token, f) ?: return@collectLatest
            // A film watched before: now that it's known, its folder's poster shows the ✓.
            val film = if (films) folderFilm(listed) else null
            if (film != null && Watched.isWatched(context, MediaRef.Drive(film.id).key)) marks.intValue++
        }
    }
    // Mark as watched (as finishing it does: the ✓, and off Continue watching), or unwatched again.
    // Both reach the family's other TVs too.
    val markWatched: (String, Boolean) -> Unit = { key, watched ->
        if (watched) { RecentStore(context).remove(key); Watched.mark(context, key) } else Watched.unmark(context, key)
        marks.intValue++
    }
    if (library) PosterBackdrop(focusedArt, token)
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = if (library) 18.dp else 32.dp)) {
        if (library) {
            // Movies / TV Shows: title, filters and the Drive buttons share one short row, so the
            // posters start near the top of the screen.
            if (filterable) {
                // The library's top level: Movies / TV Shows, the search field and Filter (with everything else).
                Box(Modifier.onFocusChanged { headerFocused.value = it.hasFocus }) {
                    LibraryHeader(
                        state.place.title, state.files, filter, drive::setFilter, onSearch = { searching = true }, more = driveActions,
                        // Streaming: Movies / TV Shows across the top, Netflix style.
                        switcher = {
                            com.woodruff.tvexplorer.ui.components.TopTabs(
                                listOf("Movies", "TV Shows"), selected = if (state.stack.firstOrNull() == DrivePlace.TvShows) 1 else 0,
                                onSelect = { i -> drive.openRoot(if (i == 0) DrivePlace.Movies else DrivePlace.TvShows) },
                            )
                        },
                    )
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                // A show's folder (seasons, episodes): back, its name, More.
                if (state.stack.size > 1) ToolButton(Sym.ArrowBack, null, { drive.back() }, modifier = Modifier.padding(end = 14.dp))
                Text(
                    state.place.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 18.dp).weight(1f),
                )
                ToolButton(Sym.MoreHoriz, null, { moreOpen = true }, modifier = Modifier.padding(start = 10.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvIcon(Sym.AddToDrive, size = 32.dp) // a plain symbol, not the colored logo
                Text(state.place.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                state.stack.joinToString("  ›  ") { it.title },
                color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            // The scroll clips at its ends, so the buttons get room inside it (shifted back into line),
            // or the focused first or last one's outline is cut off.
            Row(
                Modifier.padding(top = 18.dp, bottom = 18.dp).offset(x = (-12).dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.stack.size > 1) ToolButton(Sym.ArrowBack, null, { drive.back() })
                ToolButton(Sym.AddToDrive, "My Drive", { drive.openRoot(DrivePlace.MyDrive) })
                ToolButton(Sym.Movie, "All videos", { drive.openRoot(DrivePlace.Videos()) })
                ToolButton(Sym.FolderShared, "Shared with me", { drive.openRoot(DrivePlace.SharedWithMe) })
                ToolButton(Sym.Refresh, null, drive::refresh)
                ToolButton(Sym.Logout, null, drive::signOut)
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.busy -> EmptyState("Loading from Google Drive…")
                state.error != null -> EmptyState(state.error!!)
                state.files.isEmpty() -> EmptyState("Nothing here")
                shown.isEmpty() -> EmptyState("No matches")
                // The Overview tab: a featured title and rows. ("All" is the whole library A–Z.)
                filterable && filter.overview -> key(state.place) {
                    // One per library: Movies and TV Shows each open at their own place, with their own focus.
                    LibraryRows(
                        state.files, token, placeKey = state.place.toString(),
                        isShows = state.stack.firstOrNull() == DrivePlace.TvShows,
                        canTakeFocus = { !headerFocused.value },
                        onChoose = choose, onPlay = onOpen, onMenu = { menuFor = it },
                        onFocusArt = { focusedArt.value = it },
                        seen = seen, marks = marked, onRest = rest,
                    )
                }
                else -> {
                    // Back to a list you've been in: the item you left from is focused again (and the
                    // grid opens where it is), instead of the first one.
                    val placeKey = state.place.toString()
                    val target = remember(state.place) { driveFocus[placeKey]?.takeIf { id -> shown.any { it.id == id } } ?: shown.first().id }
                    val first = remember(state.place) { FocusRequester() }
                    // Made at the item to come back to: its row opens where the pivot puts a focused
                    // row, so taking the focus there scrolls nothing (scrolling there after the first
                    // frame was a visible jump).
                    val grid = remember(state.place) { LazyGridState(firstVisibleItemIndex = shown.indexOfFirst { it.id == target }.coerceAtLeast(0)) }
                    // How far through each title is: read off the main thread once per visit, on coming
                    // back from the player and when a ✓ changes; read by the tiles alone.
                    val progressOf = rememberProgress(marked, state.place)
                    // Load the posters a couple of rows past the focus into the image cache, so they're
                    // already there when the grid scrolls to them instead of popping in.
                    // Only once the focus rests: on every press of a held key, a dozen poster loads
                    // competed with the scrolling for the TV's processor.
                    val positions = remember(shown) { shown.withIndex().associate { (i, f) -> f.id to i } }
                    val prefetchFrom = remember(state.place) { androidx.compose.runtime.mutableIntStateOf(-1) }
                    LaunchedEffect(shown) {
                        androidx.compose.runtime.snapshotFlow { prefetchFrom.intValue }.collectLatest { at ->
                            if (at < 0) return@collectLatest
                            kotlinx.coroutines.delay(250)
                            prefetchPosters(context, shown, at + 1, 10, state.token)
                        }
                    }
                    val prefetch: (String) -> Unit = { id -> positions[id]?.let { prefetchFrom.intValue = it } }
                    // A season's episodes: wide picture cards (each episode's picture), as streaming
                    // apps show them, instead of tall poster tiles.
                    val episodeGrid = remember(shown, library, state.stack) {
                        library && state.stack.firstOrNull() == DrivePlace.TvShows && state.stack.size >= 2 &&
                            shown.count { it.kind == FileKind.VIDEO } * 2 > shown.size
                    }
                    // The focused row comes to rest at the top, where the first row sits (Apple TV
                    // style): every move down glides the grid by one row.
                    PivotScroll(grid, if (library) Pivot(offset = 30.dp) else Pivot(offset = 8.dp)) {
                        LazyVerticalGrid(
                            state = grid,
                            // Movies / TV Shows: tall poster tiles, more per row.
                            columns = GridCells.Adaptive(if (episodeGrid) 260.dp else if (library) 150.dp else 230.dp),
                            // Posters lift and grow when focused (about 17 dp up for the first row): leave them
                            // room so the top of the outline isn't cut off.
                            contentPadding = if (library) PaddingValues(top = 30.dp, bottom = 56.dp, start = 18.dp, end = 18.dp)
                            else PaddingValues(top = 8.dp, bottom = 48.dp, start = 6.dp, end = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (library) 30.dp else 22.dp),
                            verticalArrangement = Arrangement.spacedBy(if (library) 30.dp else 22.dp),
                        ) {
                            items(shown, key = { it.id }) { f ->
                                // animateItem: changing the sort or category glides the posters to their new
                                // places instead of jumping. No fades (each fading tile was drawn offscreen):
                                // titles coming and going just appear and go.
                                val mod = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    .then(if (f.id == target) Modifier.focusRequester(first) else Modifier)
                                    .onFocusChanged {
                                        if (it.hasFocus) {
                                            driveFocus[placeKey] = f.id
                                            if (library) { focusedArt.value = posterArt(f); if (!episodeGrid) prefetch(f.id) }
                                        }
                                        if (filterable) rest(f, it.hasFocus)
                                    }
                                if (episodeGrid && f.kind == FileKind.VIDEO) EpisodeCard(
                                    f, token,
                                    progress = progressOf.value?.get(MediaRef.Drive(f.id).key),
                                    watched = seen(f),
                                    modifier = mod,
                                    // Its menu: Play, Mark as watched / unwatched, Download.
                                    onLongClick = { menuFor = f },
                                    onClick = { choose(f) },
                                )
                                else if (library) PosterTile(
                                    f, token, onClick = { choose(f) }, onLongClick = { menuFor = f }, modifier = mod,
                                    label = if (filterable) Library.label(f) else null,
                                    progress = progressOf.value?.get(MediaRef.Drive(f.id).key),
                                    watched = seen(f),
                                )
                                else DriveTile(f, token, onClick = { onOpen(f) }, onLongClick = { menuFor = f }, modifier = mod)
                            }
                        }
                    }
                    LaunchedEffect(state.place) {
                        withFrameNanos {}
                        // Not while choosing a category in the header: the focus stays on the tabs.
                        if (!headerFocused.value) runCatching { first.requestFocus() }
                    }
                }
            }
        }
    }

    if (moreOpen) {
        ActionMenu(title = "Google Drive", subtitle = null, onDismiss = { moreOpen = false }, actions = driveActions)
    }

    if (searching) {
        LibrarySearch(state.place.title, state.files, token, onOpen = { f -> searching = false; choose(f) }, onDismiss = { searching = false })
    }
    // Searching used to narrow the grid itself; it has its own screen now, so an old search left in
    // the saved filter would hide titles with nothing on screen saying why.
    LaunchedEffect(state.place, filter.query) { if (state.filterable && filter.query.isNotBlank()) drive.setFilter(filter.copy(query = "")) }

    detailFor?.let { f ->
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
                // So Continue watching shows this title's poster for these, whatever the files are called.
                com.woodruff.tvexplorer.cloud.EpisodeShows.remember(context, queue.associate { MediaRef.Drive(it.id).key to f.id })
                context.startActivity(PlayerActivity.intent(context, queue.map { MediaRef.Drive(it.id).key }, queue.map { it.name }, MediaRef.Drive(ep.id).key, positionMs = if (fromStart) 0L else null))
            },
            onPlayKey = { key, title ->
                detailFor = null
                context.startActivity(PlayerActivity.intent(context, listOf(key), listOf(title), key))
            },
            openLabel = if (isShow) "All folders" else "Open",
            onChoose = { detailFor = it },
            onOpen = { detailFor = null; onOpen(f) },
            // Back on the posters: ✓s read again (marked on the page, or a film found in its folder there).
            onDismiss = { detailFor = null; marks.intValue++ },
        )
    }

    menuFor?.let { f ->
        // What Mark as watched acts on: the video, or the film kept in this folder — its folder listed
        // now if it hasn't been (the line appears once the film is known).
        val watchKey by produceState(if (library) posterWatchKey(f, films) else null, f) {
            if (value == null && library && films && f.isFolder) {
                val inside = withContext(Dispatchers.IO) { FolderCache.peek(context, f.id) } ?: FolderCache.list(context, token, f)
                value = inside?.let(::folderFilm)?.let { MediaRef.Drive(it.id).key }
            }
        }
        ActionMenu(
            title = f.name,
            subtitle = if (f.isFolder) "Folder" else "${Format.bytes(f.size)} · ${Format.date(f.modified)}",
            onDismiss = { menuFor = null },
            actions = buildList {
                if (f.isFolder) add(MenuAction("Open", Sym.AddToDrive) { drive.open(DrivePlace.Folder(f.id, f.name)) })
                if (f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) add(MenuAction("Play", Sym.PlayArrow) { onOpen(f) })
                if (f.kind == FileKind.IMAGE) add(MenuAction("View", Sym.Visibility) { onOpen(f) })
                // One line: whichever of the two it isn't.
                watchKey?.takeIf { f.isFolder || f.kind == FileKind.VIDEO }?.let { key ->
                    val watched = Watched.isWatched(context, key)
                    add(
                        if (watched) MenuAction("Mark as unwatched", Sym.RadioButtonUnchecked) { markWatched(key, false) }
                        else MenuAction("Mark as watched", Sym.CheckCircle) { markWatched(key, true) },
                    )
                }
                if (!f.isFolder && !f.isGoogleDoc) add(MenuAction("Download to this TV", Sym.Download) { drive.download(f) })
                if (f.isGoogleDoc) add(MenuAction("Google Docs files can't be opened on TV", Sym.Visibility) {})
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
 * The media key whose ✓ a library poster shows, and that Mark as watched sets: a file's own, or for
 * a film kept in its own folder ([films]: in Movies) that film's — only once the folder's listing is
 * in memory ([FolderCache.inMemory]): nothing is fetched or read from disk while drawing.
 */
internal fun posterWatchKey(f: DriveFile, films: Boolean): String? = when {
    !f.isFolder -> MediaRef.Drive(f.id).key
    films -> FolderCache.inMemory(f.id)?.let(::folderFilm)?.let { MediaRef.Drive(it.id).key }
    else -> null
}

/** Whether a library poster gets its ✓ (see [posterWatchKey]). */
internal fun posterWatched(context: Context, f: DriveFile, films: Boolean): Boolean =
    posterWatchKey(f, films)?.let { Watched.isWatched(context, it) } == true

/**
 * How far through each title is (see [progressByKey]), read on a background thread: on opening, on
 * coming back from the player, when [marks] changes (a ✓, another TV's progress) and when [key]
 * does (a new place). Until it's read, the last read (null the very first time), so a screen coming
 * back shows its bars at once. Hand the State itself to the tiles and read it there: a change then
 * redraws the tiles, not the grid or the rows around them.
 */
@Composable
internal fun rememberProgress(marks: androidx.compose.runtime.State<Int>, key: Any? = Unit): androidx.compose.runtime.State<Map<String, Float>?> {
    val context = LocalContext.current
    // Bumped on coming back from the player. (The first resume arrives with the first frame, when
    // it was just read: skipped.)
    val resumes = remember { mutableIntStateOf(0) }
    val resumedOnce = remember { BooleanArray(1) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (resumedOnce[0]) resumes.intValue++ else resumedOnce[0] = true
    }
    return produceState(remember { lastProgress }, key) {
        androidx.compose.runtime.snapshotFlow { resumes.intValue to marks.value }.collectLatest {
            value = withContext(Dispatchers.Default) { progressByKey(RecentStore(context).all()).also { lastProgress = it } }
        }
    }
}

/** The progress last read (see [rememberProgress]). */
@Volatile private var lastProgress: Map<String, Float>? = null

/** Each title part-way through, by media key, to its progress (0..1); finished ones (97% on) get no bar. */
internal fun progressByKey(recent: List<RecentItem>): Map<String, Float> =
    recent.filter { it.progress < 0.97f }.associate { it.key to it.progress }

/**
 * What the Unwatched filter leaves out. Movies: a film with its ✓ (a film in its own folder: that
 * film's, once known). TV Shows: a show already started — an episode known to sit under it has a ✓,
 * or something of it is in progress or up next.
 */
private fun watchedFor(context: Context, files: List<DriveFile>, films: Boolean): (DriveFile) -> Boolean {
    if (films) return { f -> posterWatched(context, f, films = true) }
    EpisodeShows.load(context)
    val started = Library.startedShows(files, RecentStore(context).all(), EpisodeShows.map.value, { Watched.isWatched(context, it) }, ::matchKeyOf)
    return { f -> f.id in started }
}

/**
 * Movie / show poster, Apple TV style: 2:3 artwork that, when focused, lifts off the page: a
 * little bigger, raised, with a soft shadow beneath. No tilting or moving art. A [TvCard]: the lift
 * is a layer transform and the rest follows the focus while drawing, so focusing it recomposes
 * nothing and nothing is drawn offscreen. Without artwork, a title card in the brand colors.
 */
@Composable
internal fun PosterTile(
    f: DriveFile, token: String?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier, label: String? = null,
    /** How far through it is (0..1), for the bar along the bottom; null when not started. */
    progress: Float? = null,
    /** Watched to the end: a ✓ in the corner. */
    watched: Boolean = false,
    /** A short line along the bottom, in place of a progress bar ("Next · S2 E5"). */
    caption: String? = null,
) {
    val context = LocalContext.current
    val title = label ?: f.name.substringBeforeLast('.').takeIf { !f.isFolder } ?: f.name
    // Posters already show their title, so the label only appears on focus; frames and title cards always have it.
    val poster = f.posterUrl != null && (token != null || !isGoogle(f.posterUrl))
    // The picture it can show (Drive-hosted art needs the token, which only Google gets).
    val picture = posterArt(f)?.takeIf { token != null || !isGoogle(it) }
    val motion = rememberFocusMotionState()
    TvCard(
        onClick = onClick,
        shape = Shapes.card,
        modifier = modifier.aspectRatio(2f / 3f),
        onLongClick = onLongClick,
        motion = motion,
        focusedScale = TvMotion.PosterScale,
        lift = TvMotion.PosterLift,
        shadow = true,
        // Nothing is painted under a picture: it covers the tile, and each fill under it was one more
        // pass over the tile's pixels on every frame (Movies is held up by the graphics chip). A
        // title card gets the card's glass, brightening with the focus.
        color = if (picture == null) Palette.surface else Color.Transparent,
        focusedColor = if (picture == null) Palette.surfaceHigh else Color.Transparent,
        // No focus outline or extra zoom: the lift is the focus.
        borderWidth = 1.dp,
        borderColor = Color.White.copy(alpha = 0.08f),
        focusedBorderColor = Color.White.copy(alpha = 0.35f),
    ) {
        if (picture == null) {
            // Title card: the brand gradient, a large initial and the name, so tiles stay readable without art.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.background))))
            Text(
                title.trim().take(1).uppercase(), style = MaterialTheme.typography.displayLarge,
                color = Palette.accent.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center),
            )
        } else if (LocalPosterPainter.current != null) {
            LocalPosterPainter.current!!(picture)?.let {
                androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        } else {
            // Built once per tile (not on every recomposition).
            val request = remember(picture, token) {
                ImageRequest.Builder(context).data(picture)
                    // The Google token only ever goes to Google's own image servers, never to TMDB.
                    .apply { if (isGoogle(picture) && token != null) addHeader("Authorization", "Bearer $token") }
                    .size(320, 480).crossfade(150).build() // the tile's real size on a 1080p TV: faster decode, less memory
            }
            // A picture that won't load leaves the title card's gradient, as it had under it before.
            val failed = remember(Palette.light) { BrushPainter(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.background))) }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = failed,
                // The card clips to its rounded shape already: no second clip (and layer) per tile.
                clipToBounds = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The name along the bottom: always on a frame or a title card; on a poster, whose art shows
        // its title, it fades in with the focus. Always laid out, so focusing measures nothing, and
        // faded while drawing (ModulateAlpha: no offscreen copy, and nothing drawn when hidden).
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (poster && caption == null) Modifier.fadeWithFocus(motion) else Modifier)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
        ) {
            Column {
                Text(
                    title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    // Over a caption (always shown), only the name fades.
                    modifier = if (poster && caption != null) Modifier.fadeWithFocus(motion) else Modifier,
                )
                if (caption != null) Text(
                    caption, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // Part-watched: a bar along the bottom edge, Netflix style.
        if (progress != null && progress > 0.01f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.accent))
        }
        // Watched: a ✓ badge top right.
        if (watched) Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { TvIcon(Sym.Check, size = 18.dp, tint = Color(0xFF0B1220)) }
    }
}

/**
 * Shows this as far as [motion] is focused, faded while drawing: the focus animation redraws it
 * without recomposing, and ModulateAlpha fades what it draws in place instead of drawing it into
 * an offscreen buffer first.
 */
private fun Modifier.fadeWithFocus(motion: FocusMotionState): Modifier = graphicsLayer {
    alpha = motion.fraction.coerceIn(0f, 1f)
    compositingStrategy = CompositingStrategy.ModulateAlpha
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
    // Faded as each picture draws (FadeBetween): Crossfade drew both full-screen pictures into
    // offscreen buffers on every frame of the fade.
    FadeBetween(shown, remember { tween<Float>(520, easing = androidx.compose.animation.core.FastOutSlowInEasing) }, label = "backdrop") { url ->
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
 * Compose's Crossfade, with each side faded as it draws (ModulateAlpha) instead of through an
 * offscreen buffer: Crossfade's plain alpha layer drew each side whole into a buffer first, on every
 * frame of the fade (two full-screen buffers for the backdrop). The same look for content drawn in
 * one piece, like a picture, or without overlaps, like lines of text.
 */
@Composable
internal fun <T> FadeBetween(
    targetState: T,
    animationSpec: FiniteAnimationSpec<Float>,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val transition = updateTransition(targetState, label)
    val visible = remember { mutableStateListOf<T>().apply { add(transition.currentState) } }
    if (transition.currentState == transition.targetState) {
        // Settled: only the one showing stays.
        if (visible.size != 1 || visible[0] != transition.targetState) visible.removeAll { it != transition.targetState }
    }
    if (transition.targetState !in visible) visible.add(transition.targetState)
    Box(modifier) {
        visible.forEach { state ->
            key(state) {
                val alpha by transition.animateFloat(transitionSpec = { animationSpec }, label = label) { if (it == state) 1f else 0f }
                Box(Modifier.graphicsLayer { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
                    content(state)
                }
            }
        }
    }
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
    // Narrowed on a background thread as you type (on the main thread, each letter held up a frame
    // in a big library). What shows is the last search worked out: its words and what matched.
    val found by produceState<Pair<String, List<DriveFile>>>("" to emptyList(), all, query.text) {
        val q = query.text.trim()
        val matches = if (q.isEmpty()) emptyList<DriveFile>()
        else withContext(Dispatchers.Default) { Library.apply(all, com.woodruff.tvexplorer.cloud.LibraryFilter(query = q)) }
        value = q to matches
    }
    val (searched, results) = found
    val grid = rememberLazyGridState()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        // Grows into place as it fades in, and back out, as the system moves its window (smooth even
        // while the page is still being built), so the page doesn't ease in itself.
        DialogWindowMotion(WindowMotion.Page)
        com.woodruff.tvexplorer.ui.components.FocusWhenReady(field)
        // A dialog's keys never pass the activity's repeat gate: held arrows are gated here.
        Box(Modifier.fillMaxSize().dpadGate()) {
            Box(Modifier.fillMaxSize().ambientBackdrop()) {
                Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 40.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvIcon(Sym.Search, size = 34.dp, tint = Palette.muted)
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
                            searched.isEmpty() -> "Type a title or a year. You can also type on your phone (Phone → Keyboard)."
                            results.isEmpty() -> "Nothing in $title matches \u201c$searched\u201d"
                            else -> "${results.size} result${if (results.size == 1) "" else "s"}"
                        },
                        color = Palette.muted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 14.dp, start = 4.dp),
                    )
                    // The focused row comes to rest where the first row sits, as in the library's grid.
                    PivotScroll(grid, Pivot(offset = 30.dp)) {
                        LazyVerticalGrid(
                            state = grid,
                            columns = GridCells.Adaptive(150.dp),
                            contentPadding = PaddingValues(top = 30.dp, bottom = 56.dp, start = 18.dp, end = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(30.dp),
                            verticalArrangement = Arrangement.spacedBy(30.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(results, key = { it.id }) { f ->
                                // Glides into place as the results change; no fades (each was drawn offscreen).
                                PosterTile(
                                    f, token, onClick = { onOpen(f) }, onLongClick = { onOpen(f) },
                                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null), label = Library.label(f),
                                )
                            }
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

/**
 * A Drive file outside the libraries: its picture (or symbol), name and size. The card's glass and
 * lit rim are CardStyle's; the rim, a gradient, is drawn with the content and the focus ring by the card.
 */
@Composable
private fun DriveTile(f: DriveFile, token: String?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    TvCard(
        onClick = onClick,
        shape = Shapes.card,
        modifier = modifier,
        onLongClick = onLongClick,
        focusedScale = TvMotion.CardScale,
        color = Palette.surface,
        focusedColor = Palette.surfaceHigh,
        borderWidth = 2.dp,
        focusedBorderColor = Palette.focusRim,
    ) {
        Column(Modifier.fillMaxWidth().border(1.dp, Palette.rim, Shapes.card)) {
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
                    ) { TvIcon(Sym.PlayArrow, size = 28.dp, tint = Palette.text) }
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(if (f.isFolder) f.name else Format.title(f.name), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
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
                TvIcon(Sym.AddToDrive, size = 32.dp) // a plain symbol, not the colored logo
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
            if (!setup) OutlinedButton(
                onClick = onReset,
                // Grows on focus as a layer (tv-material's own growth recomposed it every frame).
                modifier = Modifier.tvFocusMotion(rememberFocusMotionState(), focusedScale = 1.1f),
                scale = androidx.tv.material3.OutlinedButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f / 1.1f),
            ) { Text("Use a different Google Cloud client") }
        }
    }
}

private val QR_SIZE = 250.dp

/** Last focused item per Drive list (by place), for coming back to it with Back. */
private val driveFocus = java.util.concurrent.ConcurrentHashMap<String, String>()
