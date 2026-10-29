package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.photos.VideoThumbs
import com.woodruff.tvexplorer.ui.components.FileGlyph
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.icon
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes
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

/**
 * A file in the list. A [TvCard] laid out as tv-material's ListItem was (its sizes, its colors), but
 * focus only redraws it: no offscreen drawing, nothing recomposed while it grows.
 */
@Composable
fun FileRow(
    item: FileItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvCard(
        onClick = onClick,
        shape = RowShape,
        modifier = modifier.semantics { this.selected = selected },
        onLongClick = onLongClick,
        focusedScale = 1.01f,
        color = if (selected) Palette.accentSoft else Color.Transparent,
        focusedColor = Palette.surfaceHigh,
        borderWidth = 2.dp,
        focusedBorderColor = Palette.accent,
    ) {
        // A selected row's words and symbols in the color tv-material gave them.
        CompositionLocalProvider(LocalContentColor provides if (selected) MaterialTheme.colorScheme.onSecondaryContainer else Palette.text) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Its symbol at 80%, as the list item drew it: here through the tint, not a layer.
                Box(Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.8f)) {
                        if (selecting) TvIcon(if (selected) Sym.CheckCircle else Sym.RadioButtonUnchecked)
                        else FileGlyph(item.name, item.kind, size = 34.dp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (item.isDirectory) item.name else Format.title(item.name), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(item.subtitle(), maxLines = 1, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A list row's corners, as tv-material's ListItem had them. */
private val RowShape = RoundedCornerShape(8.dp)

/**
 * A file in the grid: its picture (or symbol), name and size. The card's glass and lit rim are
 * CardStyle's; the rim, a gradient, is drawn with the content and the focus ring by the card.
 */
@Composable
fun FileTile(
    item: FileItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvCard(
        onClick = onClick,
        shape = Shapes.card,
        modifier = modifier,
        onLongClick = onLongClick,
        focusedScale = TvMotion.CardScale,
        color = if (selected) Palette.accentSoft else Palette.surface,
        focusedColor = Palette.surfaceHigh,
        borderWidth = 2.dp,
        focusedBorderColor = Palette.focusRim,
    ) {
        Column(Modifier.fillMaxWidth().border(1.dp, Palette.rim, Shapes.card)) {
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
                        tint = Color.White,
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

