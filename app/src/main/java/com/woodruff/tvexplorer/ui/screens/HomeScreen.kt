package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.StorageRoot
import com.woodruff.tvexplorer.ui.Location
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.UsageBar
import com.woodruff.tvexplorer.ui.components.color
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

private data class Tile(val title: String, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

/** Landing page: storage devices, library shortcuts and tools, each as a horizontal row. */
@Composable
fun HomeScreen(vm: MainViewModel, roots: List<StorageRoot>) {
    val library = listOf(
        FileKind.VIDEO to "Videos", FileKind.AUDIO to "Music", FileKind.IMAGE to "Pictures",
        FileKind.DOCUMENT to "Documents", FileKind.APK to "APK files", FileKind.ARCHIVE to "Archives",
    ).map { (kind, title) -> Tile(title, kind.icon(), kind.color()) { vm.open(Location.Category(kind, title)) } } +
        Tile("Downloads", Icons.Filled.Download, Palette.accent) { vm.openDownloads() }

    val tools = listOf(
        Tile("Apps", Icons.Filled.Apps, Palette.apk) { vm.show(Screen.APPS) },
        Tile("Wireless transfer", Icons.Filled.Wifi, Palette.accent) { vm.show(Screen.TRANSFER) },
    )

    LazyColumn(
        contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item { Section("Storage") { items(roots, key = { it.dir.absolutePath }) { StorageCard(it) { vm.openFolder(it.dir) } } } }
        item { Section("Library") { items(library, key = { it.title }) { TileCard(it) } } }
        item { Section("Tools") { items(tools, key = { it.title }) { TileCard(it) } } }
    }
}

@Composable
private fun Section(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
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
        modifier = Modifier.width(300.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            focusedContainerColor = Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContentColor = Palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(Modifier.padding(20.dp)) {
            TvIcon(icon, size = 36.dp, tint = Palette.accent)
            Spacer(Modifier.height(12.dp))
            Text(root.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            UsageBar(root.usedFraction)
            Spacer(Modifier.height(8.dp))
            Text(
                if (root.totalBytes > 0) "${Format.bytes(root.freeBytes)} free of ${Format.bytes(root.totalBytes)}" else root.dir.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TileCard(tile: Tile) {
    Surface(
        onClick = tile.onClick,
        modifier = Modifier.size(width = 170.dp, height = 130.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            focusedContainerColor = Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContentColor = Palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            TvIcon(tile.icon, size = 40.dp, tint = tile.color)
            Spacer(Modifier.height(20.dp))
            Text(tile.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}
