package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import com.woodruff.tvexplorer.R
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
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SmartDisplay
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
import com.woodruff.tvexplorer.ui.components.IconBadge
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

/** [brand] is the real app icon (drawable) for third-party services; our own features use [icon]. */
private data class Tile(
    val title: String,
    val subtitle: String?,
    val icon: ImageVector,
    val color: Color,
    val brand: Int? = null,
    val onClick: () -> Unit,
)

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
        Tile("Cameras", null, Icons.Rounded.Videocam, Palette.accent, R.drawable.brand_google_home) { vm.show(Screen.CAMERAS) },
        Tile("Google Drive", null, Icons.Rounded.AddToDrive, Palette.drive, R.drawable.brand_google_drive) { vm.show(Screen.DRIVE) },
        Tile("iCloud", null, Icons.Rounded.Cloud, Palette.icloud, R.drawable.brand_icloud) { showICloud = true },
        Tile("Mail", null, Icons.Rounded.Email, Palette.accent) { vm.show(Screen.MAIL) },
    )
    val library = listOf(
        FileKind.VIDEO to "Videos", FileKind.AUDIO to "Music", FileKind.IMAGE to "Photos",
        FileKind.DOCUMENT to "Documents", FileKind.APK to "APK files", FileKind.ARCHIVE to "Archives",
    ).map { (kind, title) -> Tile(title, null, kind.icon(), kind.color()) { vm.open(Location.Category(kind, title)) } } +
        Tile("Downloads", null, Icons.Rounded.Download, Palette.accent) { vm.openDownloads() }
    val watch = listOf(
        Tile("YouTube", null, Icons.Rounded.SmartDisplay, Color(0xFFFF0033), R.drawable.brand_youtube) { Handoff.launch(context, Handoff.YOUTUBE) },
        Tile("Apple Music", null, Icons.Rounded.MusicNote, Color(0xFFFA2D48), R.drawable.brand_apple_music) { Handoff.launch(context, Handoff.APPLE_MUSIC) },
        Tile("Spotify", null, Icons.Rounded.LibraryMusic, Color(0xFF1ED760), R.drawable.brand_spotify) { Handoff.launch(context, Handoff.SPOTIFY) },
        Tile("Photo slideshow", null, Icons.Rounded.Slideshow, Palette.drive) { SlideshowActivity.start(context) },
    )
    val tools = listOf(
        Tile("Notes", null, Icons.Rounded.EditNote, Palette.accent) { vm.show(Screen.NOTES) },
        Tile("Phone", null, Icons.Rounded.PhoneIphone, Palette.accent) { vm.show(Screen.TRANSFER) },
        Tile("Apps", null, Icons.Rounded.Apps, Palette.apk) { vm.openApps(Screen.HOME) },
        Tile("Settings", null, Icons.Rounded.Settings, Palette.muted) { vm.show(Screen.SETTINGS) },
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
                MenuAction("Play from where you left off", Icons.Rounded.PlayArrow) {
                    context.startActivity(PlayerActivity.intent(context, listOf(r.key), listOf(r.title), r.key))
                },
                MenuAction("Remove from Continue watching", Icons.Rounded.Delete, destructive = true) {
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
                if (ref is MediaRef.Drive) Image(painterResource(R.drawable.brand_google_drive), contentDescription = null, modifier = Modifier.size(48.dp))
                else TvIcon(FileKind.VIDEO.icon(), size = 48.dp, tint = Palette.video)
                if (ref is MediaRef.Local) {
                    AsyncImage(model = File(ref.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                // Darken the bottom so the play badge and progress read on bright frames.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(Palette.accent),
                    contentAlignment = Alignment.Center,
                ) { TvIcon(Icons.Rounded.PlayArrow, size = 32.dp, tint = Palette.onAccent) }
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
        !root.removable -> Icons.Rounded.Storage
        root.title.contains("USB", ignoreCase = true) -> Icons.Rounded.Usb
        else -> Icons.Rounded.SdStorage
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
                if (root.removable) Chip(if (icon == Icons.Rounded.Usb) "USB" else "Removable")
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
                if (tile.brand != null) Image(painterResource(tile.brand), contentDescription = null, modifier = Modifier.size(46.dp))
                else IconBadge(tile.icon, tile.color, size = 46.dp)
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
