package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SmartDisplay
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.util.Handoff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.StorageRoot
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.ui.Location
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.Chip
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.SectionTitle
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.UsageBar
import com.woodruff.tvexplorer.ui.components.color
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.theme.CardStyle
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private data class Tile(val title: String, val subtitle: String?, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

/** Landing page: greeting, Continue watching, cloud, storage, library and tools, as TV-style rows. */
@Composable
fun HomeScreen(vm: MainViewModel, roots: List<StorageRoot>) {
    val context = LocalContext.current
    val store = remember { RecentStore(context) }
    var recent by remember { mutableStateOf(store.continueWatching()) }
    var showICloud by remember { mutableStateOf(false) }
    var removeFor by remember { mutableStateOf<RecentItem?>(null) }
    // Coming back from the player: pick up the position it just saved.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recent = store.continueWatching() }

    val cloud = listOf(
        Tile("Cameras", "Command center", Icons.Filled.Videocam, Palette.danger) { vm.show(Screen.CAMERAS) },
        Tile("Google Drive", "Stream & download", Icons.Filled.AddToDrive, Palette.drive) { vm.show(Screen.DRIVE) },
        Tile("iCloud", "Mail & files from iPhone", Icons.Filled.Cloud, Palette.icloud) { showICloud = true },
        Tile("Mail", "iCloud, Gmail & more", Icons.Filled.Email, Palette.accent) { vm.show(Screen.MAIL) },
    )
    val library = listOf(
        FileKind.VIDEO to "Videos", FileKind.AUDIO to "Music", FileKind.IMAGE to "Photos",
        FileKind.DOCUMENT to "Documents", FileKind.APK to "APK files", FileKind.ARCHIVE to "Archives",
    ).map { (kind, title) -> Tile(title, null, kind.icon(), kind.color()) { vm.open(Location.Category(kind, title)) } } +
        Tile("Downloads", null, Icons.Filled.Download, Palette.accent) { vm.openDownloads() }
    val watch = listOf(
        Tile("YouTube", "Opens the YouTube app", Icons.Filled.SmartDisplay, Color(0xFFFF0033)) { Handoff.launch(context, Handoff.YOUTUBE) },
        Tile("Apple Music", "Opens Apple Music", Icons.Filled.MusicNote, Color(0xFFFA2D48)) { Handoff.launch(context, Handoff.APPLE_MUSIC) },
        Tile("Spotify", "Opens Spotify", Icons.Filled.LibraryMusic, Color(0xFF1ED760)) { Handoff.launch(context, Handoff.SPOTIFY) },
        Tile("Photo slideshow", "Your photos, full screen", Icons.Filled.Slideshow, Palette.drive) { SlideshowActivity.start(context) },
    )
    val tools = listOf(
        Tile("Notes", "Lists & reminders", Icons.Filled.EditNote, Palette.accent) { vm.show(Screen.NOTES) },
        Tile("Phone", "Send files, messages, links", Icons.Filled.PhoneIphone, Palette.accent) { vm.show(Screen.TRANSFER) },
        Tile("Apps", "Open, back up, uninstall", Icons.Filled.Apps, Palette.apk) { vm.show(Screen.APPS) },
        Tile("Settings", "Keys, accounts, theme", Icons.Filled.Settings, Palette.muted) { vm.show(Screen.SETTINGS) },
    )

    LazyColumn(
        contentPadding = PaddingValues(top = 36.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        item { Header() }
        if (recent.isNotEmpty()) {
            item {
                Shelf("Continue watching") {
                    items(recent, key = { it.key }) { r ->
                        ContinueCard(
                            r,
                            onClick = {
                                context.startActivity(PlayerActivity.intent(context, listOf(r.key), listOf(r.title), r.key))
                            },
                            onLongClick = { removeFor = r },
                        )
                    }
                }
            }
        }
        item { Shelf("Cloud") { items(cloud, key = { it.title }) { TileCard(it, wide = true) } } }
        item { Shelf("Watch & listen") { items(watch, key = { it.title }) { TileCard(it, wide = true) } } }
        item { Shelf("On this TV") { items(roots, key = { it.dir.absolutePath }) { StorageCard(it) { vm.openFolder(it.dir) } } } }
        item { Shelf("Library") { items(library, key = { it.title }) { TileCard(it) } } }
        item { Shelf("Tools") { items(tools, key = { it.title }) { TileCard(it, wide = true) } } }
    }

    if (showICloud) ICloudDialog(onDismiss = { showICloud = false }, onStart = { showICloud = false; vm.show(Screen.TRANSFER) })
    removeFor?.let { r ->
        ActionMenu(
            title = r.title, subtitle = "${(r.progress * 100).toInt()}% watched",
            onDismiss = { removeFor = null },
            actions = listOf(
                MenuAction("Play from where you left off", Icons.Filled.PlayArrow) {
                    context.startActivity(PlayerActivity.intent(context, listOf(r.key), listOf(r.title), r.key))
                },
                MenuAction("Remove from Continue watching", Icons.Filled.Delete, destructive = true) {
                    store.remove(r.key); recent = store.continueWatching()
                },
            ),
        )
    }
}

@Composable
private fun Header() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(greeting, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text("What are we watching tonight?", style = MaterialTheme.typography.titleMedium, color = Palette.muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), style = MaterialTheme.typography.headlineMedium)
            Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(now)), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
    }
}

@Composable
private fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column {
        SectionTitle(title, Modifier.padding(start = 48.dp, bottom = 14.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            content = content,
        )
    }
}

@Composable
private fun ContinueCard(r: RecentItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ref = MediaRef.parse(r.key)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(340.dp).onMenuKey(onLongClick),
        shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Palette.background),
                contentAlignment = Alignment.Center,
            ) {
                TvIcon(if (ref is MediaRef.Drive) Icons.Filled.AddToDrive else FileKind.VIDEO.icon(), size = 48.dp, tint = if (ref is MediaRef.Drive) Palette.drive else Palette.video)
                if (ref is MediaRef.Local) {
                    AsyncImage(model = File(ref.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                // Darken the bottom so the play badge and progress read on bright frames.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(Palette.accent),
                    contentAlignment = Alignment.Center,
                ) { TvIcon(Icons.Filled.PlayArrow, size = 32.dp, tint = Palette.onAccent) }
                UsageBar(r.progress, Modifier.align(Alignment.BottomCenter))
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    (if (r.durationMs > 0) "${Format.duration(r.durationMs - r.positionMs)} left" else "Resume") +
                        if (ref is MediaRef.Drive) " · Google Drive" else "",
                    color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StorageCard(root: StorageRoot, onClick: () -> Unit) {
    val icon = when {
        !root.removable -> Icons.Filled.Storage
        root.title.contains("USB", ignoreCase = true) -> Icons.Filled.Usb
        else -> Icons.Filled.SdStorage
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.width(320.dp),
        shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvIcon(icon, size = 34.dp, tint = Palette.accent)
                Spacer(Modifier.weight(1f))
                if (root.removable) Chip(if (icon == Icons.Filled.Usb) "USB" else "Removable")
            }
            Spacer(Modifier.height(14.dp))
            Text(root.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            UsageBar(root.usedFraction)
            Spacer(Modifier.height(8.dp))
            Text(
                if (root.totalBytes > 0) "${Format.bytes(root.freeBytes)} free of ${Format.bytes(root.totalBytes)}" else root.dir.absolutePath,
                style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1,
            )
        }
    }
}

@Composable
private fun TileCard(tile: Tile, wide: Boolean = false) {
    Surface(
        onClick = tile.onClick,
        modifier = Modifier.size(width = if (wide) 280.dp else 176.dp, height = 140.dp),
        shape = CardStyle.shape(), colors = CardStyle.colors(), border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tile.color.copy(alpha = 0.18f), Color.Transparent)))) {
            Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                TvIcon(tile.icon, size = 40.dp, tint = tile.color)
                Column {
                    Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    tile.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * Apple offers no way for Android apps to sign in to iCloud, so this explains the reliable route:
 * the iPhone's own file picker can upload straight from iCloud Drive and Photos to "Send files".
 */
@Composable
private fun ICloudDialog(onDismiss: () -> Unit, onStart: () -> Unit) {
    DialogFrame("iCloud on your TV", onDismiss, width = 640) {
        Text(
            "iCloud Mail: add it in Settings → Mail with an app-specific password from account.apple.com.\n\n" +
                "iCloud Drive and Photos: Apple doesn't offer Android apps a way to sign in to iCloud Drive, so send files from your iPhone or iPad:",
            color = Palette.muted,
        )
        listOf(
            "1.  Choose Start below to show the Phone QR code.",
            "2.  Scan it with the iPhone Camera app (once: it stays paired).",
            "3.  In Files, tap Upload files, then Browse for iCloud Drive or Photo Library for your photos and videos.",
            "4.  Files land in the folder you picked, ready to play here.",
        ).forEach { Text(it) }
        ButtonRow {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
            Button(onClick = onStart) { Text("Start") }
        }
    }
}
