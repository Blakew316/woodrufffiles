package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.color
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

/** The remote's Menu key (or the "≡" key on some remotes) opens the action menu, like a long press. */
fun Modifier.onMenuKey(action: () -> Unit) = onKeyEvent {
    if (it.type == KeyEventType.KeyUp && it.key == Key.Menu) { action(); true } else false
}

private fun FileItem.subtitle(): String = buildString {
    if (isDirectory) append(if (childCount >= 0) "$childCount items" else "Folder")
    else append(Format.bytes(size))
    append("  ·  ")
    append(Format.date(lastModified))
}

@Composable
fun FileRow(
    item: FileItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onMenuKey(onLongClick),
        colors = ListItemDefaults.colors(
            selectedContainerColor = Palette.accent.copy(alpha = 0.25f),
            focusedContainerColor = Palette.surfaceHigh,
            focusedContentColor = Palette.text,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.01f),
        leadingContent = {
            if (selecting) TvIcon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, tint = Palette.accent)
            else TvIcon(item.kind.icon(), tint = item.kind.color())
        },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.subtitle(), maxLines = 1, color = Palette.muted) },
    )
}

@Composable
fun FileTile(
    item: FileItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onMenuKey(onLongClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.accent.copy(alpha = 0.25f) else Palette.surface,
            focusedContainerColor = Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContentColor = Palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Palette.background),
                contentAlignment = Alignment.Center,
            ) {
                TvIcon(item.kind.icon(), size = 48.dp, tint = item.kind.color())
                if (item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO) {
                    AsyncImage(
                        model = item.file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (selecting) {
                    TvIcon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        tint = Palette.accent,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Row { Text(item.subtitle(), maxLines = 1, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.muted, style = MaterialTheme.typography.titleMedium)
    }
}

