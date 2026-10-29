package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.photos.VideoThumbs
import com.woodruff.tvexplorer.ui.components.FileGlyph
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.theme.CardStyle
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format
import java.io.File

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
            selectedContainerColor = Palette.accentSoft,
            focusedContainerColor = Palette.surfaceHigh,
            focusedContentColor = Palette.text,
        ),
        border = ListItemDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.accent)),
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.01f),
        leadingContent = {
            if (selecting) TvIcon(if (selected) Sym.CheckCircle else Sym.RadioButtonUnchecked)
            else FileGlyph(item.name, item.kind, size = 34.dp)
        },
        headlineContent = { Text(if (item.isDirectory) item.name else Format.title(item.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        shape = CardStyle.shape(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.accentSoft else Palette.surface,
            focusedContainerColor = Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContentColor = Palette.text,
        ),
        border = CardStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(Palette.background),
                contentAlignment = Alignment.Center,
            ) {
                FileGlyph(item.name, item.kind, size = 64.dp)
                if (item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO) {
                    AsyncImage(
                        model = if (item.kind == FileKind.VIDEO) rememberVideoThumb(item.file) else item.file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (selecting) {
                    TvIcon(
                        if (selected) Sym.CheckCircle else Sym.RadioButtonUnchecked,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (item.isDirectory) item.name else Format.title(item.name), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Row { Text(item.subtitle(), maxLines = 1, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * A local video's tile picture: a small copy of its frame kept on disk (made once, still there
 * after a restart) instead of opening the video for every tile; the video itself when no frame
 * could be saved, as before.
 */
@Composable
internal fun rememberVideoThumb(video: File): ImageRequest {
    val context = LocalContext.current
    return remember(context, video) { VideoThumbs.request(context, video) }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.muted, style = MaterialTheme.typography.titleMedium)
    }
}

