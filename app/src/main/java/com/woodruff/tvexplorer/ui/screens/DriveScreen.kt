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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveState
import com.woodruff.tvexplorer.cloud.DriveViewModel
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

    val onOpen: (DriveFile) -> Unit = { f -> openDriveFile(context, f, state.files, drive::open, onOther = { menuFor = it }) }

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvIcon(Icons.Filled.AddToDrive, size = 32.dp, tint = Palette.drive)
            Text(state.place.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            state.stack.joinToString("  ›  ") { it.title },
            color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.stack.size > 1) ToolButton(Icons.Filled.ArrowBack, null, { drive.back() })
            ToolButton(Icons.Filled.AddToDrive, "My Drive", { drive.openRoot(DrivePlace.MyDrive) })
            ToolButton(Icons.Filled.Movie, "All videos", { drive.openRoot(DrivePlace.Videos()) })
            ToolButton(Icons.Filled.FolderShared, "Shared with me", { drive.openRoot(DrivePlace.SharedWithMe) })
            ToolButton(Icons.Filled.Refresh, null, drive::refresh)
            ToolButton(Icons.Filled.Logout, null, drive::signOut)
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.busy -> EmptyState("Loading from Google Drive…")
                state.error != null -> EmptyState(state.error!!)
                state.files.isEmpty() -> EmptyState("Nothing here")
                else -> {
                    val first = remember(state.place) { FocusRequester() }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(230.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, start = 6.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(state.files, key = { it.id }) { f ->
                            DriveTile(
                                f, state.token,
                                onClick = { onOpen(f) },
                                onLongClick = { menuFor = f },
                                modifier = if (f == state.files.first()) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                    LaunchedEffect(state.place) { runCatching { first.requestFocus() } }
                }
            }
        }
    }

    menuFor?.let { f ->
        ActionMenu(
            title = f.name,
            subtitle = if (f.isFolder) "Folder" else "${Format.bytes(f.size)} · ${Format.date(f.modified)}",
            onDismiss = { menuFor = null },
            actions = buildList {
                if (f.isFolder) add(MenuAction("Open", Icons.Filled.AddToDrive) { drive.open(DrivePlace.Folder(f.id, f.name)) })
                if (f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) add(MenuAction("Play", Icons.Filled.PlayArrow) { onOpen(f) })
                if (f.kind == FileKind.IMAGE) add(MenuAction("View", Icons.Filled.Visibility) { onOpen(f) })
                if (!f.isFolder && !f.isGoogleDoc) add(MenuAction("Download to this TV", Icons.Filled.Download) { drive.download(f) })
                if (f.isGoogleDoc) add(MenuAction("Google Docs files can't be opened on TV", Icons.Filled.Visibility) {})
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
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Palette.background),
                contentAlignment = Alignment.Center,
            ) {
                TvIcon(f.kind.icon(), size = 48.dp, tint = f.kind.color())
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
                    ) { TvIcon(Icons.Filled.PlayArrow, size = 28.dp, tint = Palette.text) }
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

@Composable
private fun ConnectPanel(state: DriveState, onReset: () -> Unit) {
    val setup = state.step == SignInStep.SETUP
    Row(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Palette.drive.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    TvIcon(Icons.Filled.AddToDrive, size = 38.dp, tint = Palette.drive)
                }
                Text(
                    if (setup) "Set up Google Drive" else "Sign in with your phone",
                    style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 18.dp),
                )
            }
            Text(
                if (setup) "Step 1 of 2. Scan the QR code with your phone (on the same Wi-Fi as this TV) and paste the " +
                    "Client ID and Client secret of your Google Cloud \"Web application\" OAuth client."
                else "Step 2 of 2. Scan the QR code with your phone and sign in to Google. Your phone must be on the same Wi-Fi as this TV. " +
                    "Woodruff Shows can only read your files, never change or delete them.",
                color = Palette.muted,
            )
            if (setup) state.setupAddress?.let {
                Text("No phone camera? Open this on a computer:", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                Text(it, color = Palette.accent, style = MaterialTheme.typography.titleMedium)
            }
            if (state.busy) Text("Finishing sign-in…", color = Palette.accent)
            state.error?.let { Text(it, color = Palette.danger) }
            if (!setup) {
                OutlinedButton(onClick = onReset) { Text("Use a different Google Cloud client") }
            }
        }
        state.qrUrl?.let { url ->
            val qr = remember(url) { Qr.bitmap(url, 640).asImageBitmap() }
            Image(
                qr, contentDescription = "QR code",
                modifier = Modifier.size(340.dp).clip(RoundedCornerShape(18.dp)).background(Color.White),
            )
        }
    }
}
