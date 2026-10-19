package com.woodruff.tvexplorer.ui.screens

import android.content.Context
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

    if (!state.connected) {
        ConnectPanel(state, onReset = drive::resetSetup)
        return
    }

    val filter = state.filter
    // Movies / TV Shows top level: filtered and sorted; everywhere else as Drive lists it.
    val shown = remember(state.files, state.filterable, filter) {
        if (state.filterable) Library.apply(state.files, filter) else state.files
    }
    val onOpen: (DriveFile) -> Unit = { f -> openDriveFile(context, f, shown, drive::open, onOther = { menuFor = it }) }

    var moreOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = if (state.library) 18.dp else 32.dp)) {
        if (state.library) {
            // Movies / TV Shows: title, filters and the Drive buttons share one short row, so the
            // posters start near the top of the screen.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                if (state.stack.size > 1) ToolButton(Icons.Rounded.ArrowBack, null, { drive.back() }, modifier = Modifier.padding(end = 14.dp))
                Text(
                    state.place.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 18.dp).then(if (state.filterable) Modifier else Modifier.weight(1f)),
                )
                // Filters on the library's top level only (a season folder just lists its episodes).
                if (state.filterable) {
                    if (state.files.isNotEmpty()) LibraryFilters(state.files, filter, drive::setFilter, onSearch = { searching = true }, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                }
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
                else -> {
                    // Back to a list you've been in: the item you left from is focused (and scrolled to)
                    // again, instead of the first one.
                    val placeKey = state.place.toString()
                    val target = remember(state.place) { driveFocus[placeKey]?.takeIf { id -> shown.any { it.id == id } } ?: shown.first().id }
                    val first = remember(state.place) { FocusRequester() }
                    val grid = remember(state.place) { LazyGridState() }
                    LazyVerticalGrid(
                        state = grid,
                        // Movies / TV Shows: tall poster tiles, more per row.
                        columns = GridCells.Adaptive(if (state.library) 150.dp else 230.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, start = 6.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(shown, key = { it.id }) { f ->
                            val mod = (if (f.id == target) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { if (it.isFocused) driveFocus[placeKey] = f.id }
                            if (state.library) PosterTile(
                                f, state.token, onClick = { onOpen(f) }, onLongClick = { menuFor = f }, modifier = mod,
                                label = if (state.filterable) Library.label(f) else null,
                            )
                            else DriveTile(f, state.token, onClick = { onOpen(f) }, onLongClick = { menuFor = f }, modifier = mod)
                        }
                    }
                    LaunchedEffect(state.place) {
                        val i = shown.indexOfFirst { it.id == target }
                        if (i > 0) runCatching { grid.scrollToItem(i) }
                        withFrameNanos {}
                        runCatching { first.requestFocus() }
                    }
                }
            }
        }
    }

    if (moreOpen) {
        ActionMenu(
            title = "Google Drive", subtitle = null, onDismiss = { moreOpen = false },
            actions = listOf(
                MenuAction("Refresh", Icons.Rounded.Refresh, run = drive::refresh),
                MenuAction("My Drive", Icons.Rounded.AddToDrive) { drive.openRoot(DrivePlace.MyDrive) },
                MenuAction("All videos", Icons.Rounded.Movie) { drive.openRoot(DrivePlace.Videos()) },
                MenuAction("Shared with me", Icons.Rounded.FolderShared) { drive.openRoot(DrivePlace.SharedWithMe) },
                MenuAction("Sign out of Google Drive", Icons.Rounded.Logout, destructive = true, run = drive::signOut),
            ),
        )
    }

    if (searching) {
        TextInputDialog(
            "Search ${state.place.title}", filter.query, "Search",
            onConfirm = { q -> drive.setFilter(filter.copy(query = q.trim())); searching = false },
            onDismiss = { searching = false },
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

/** Folders navigate, media streams in the built-in player/viewer, anything else offers its menu. */
private fun openDriveFile(context: Context, f: DriveFile, all: List<DriveFile>, openFolder: (DrivePlace) -> Unit, onOther: (DriveFile) -> Unit) {
    when (f.kind) {
        FileKind.FOLDER -> openFolder(DrivePlace.Folder(f.id, f.name))
        FileKind.VIDEO, FileKind.AUDIO -> {
            val queue = all.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO }.take(300)
            context.startActivity(PlayerActivity.intent(context, queue.map { MediaRef.Drive(it.id).key }, queue.map { it.name }, MediaRef.Drive(f.id).key))
        }
        FileKind.IMAGE -> {
            val images = all.filter { it.kind == FileKind.IMAGE }.take(300)
            context.startActivity(ImageViewerActivity.intent(context, images.map { MediaRef.Drive(it.id).key }, MediaRef.Drive(f.id).key, images.map { it.name }))
        }
        else -> onOther(f)
    }
}

/**
 * Movie / show poster: 2:3 artwork filling the tile, title over a dark fade at the bottom.
 * Without artwork, a title card in the brand colors.
 */
@Composable
internal fun PosterTile(f: DriveFile, token: String?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier, label: String? = null) {
    val context = LocalContext.current
    val title = label ?: f.name.substringBeforeLast('.').takeIf { !f.isFolder } ?: f.name
    // Posters already show their title, so the label only appears on focus; frames and title cards always have it.
    val poster = f.posterUrl != null && (token != null || !isGoogle(f.posterUrl))
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onMenuKey(onLongClick).aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
        shape = CardStyle.shape(),
        colors = CardStyle.colors(),
        border = CardStyle.border(),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.background)))) {
            // Real art (own poster or TMDB), else the Drive preview frame, else a title card.
            val art = f.posterUrl ?: f.thumbnailLink?.takeIf { f.kind == FileKind.VIDEO }?.let(DriveApi::big)
            if (art == null || (token == null && isGoogle(art))) {
                // Title card: large initial and the name, so tiles stay readable without art.
                Text(
                    title.trim().take(1).uppercase(), style = MaterialTheme.typography.displayLarge,
                    color = Palette.accent.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center),
                )
            } else if (LocalPosterPainter.current != null) {
                LocalPosterPainter.current!!(art)?.let {
                    androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(art)
                        // The Google token only ever goes to Google's own image servers, never to TMDB.
                        .apply { if (isGoogle(art) && token != null) addHeader("Authorization", "Bearer $token") }
                        .size(450, 675).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!poster || focused) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Only set by the UI gallery, which renders headless and can't wait on Coil: decodes art synchronously. */
internal val LocalPosterPainter = androidx.compose.runtime.staticCompositionLocalOf<((String) -> androidx.compose.ui.graphics.painter.Painter?)?> { null }

private fun isGoogle(url: String): Boolean {
    val host = android.net.Uri.parse(url).host.orEmpty()
    return host.endsWith(".googleusercontent.com") || host.endsWith(".google.com") || host.endsWith(".googleapis.com")
}

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
                if (f.thumbnailLink != null && token != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(f.thumbnailLink).addHeader("Authorization", "Bearer $token").build(),
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
