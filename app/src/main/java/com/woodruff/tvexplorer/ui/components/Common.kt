package com.woodruff.tvexplorer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.ui.theme.Palette

/**
 * Focuses [requester] as soon as its item is on screen, trying for a few frames. Use it inside a
 * dialog's content: the dialog's window composes a frame or two after the code that opens it, so a
 * request made from outside (or at once) can find nothing attached yet and silently do nothing.
 */
@Composable
fun FocusWhenReady(requester: androidx.compose.ui.focus.FocusRequester, key: Any? = Unit) {
    androidx.compose.runtime.LaunchedEffect(requester, key) {
        repeat(10) {
            androidx.compose.runtime.withFrameNanos {}
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/** Tinted vector icon; follows the surrounding content color (which flips when focused). */
@Composable
fun TvIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/**
 * A well-known folder's own symbol (as the Mac's Finder shows Movies, Pictures, Music…), else a
 * plain folder.
 */
private fun folderGlyph(name: String): ImageVector = when (name.lowercase()) {
    "movies", "videos", "movie", "video", "tv shows" -> Icons.Rounded.Movie
    "pictures", "photos", "screenshots", "wallpapers" -> Icons.Rounded.Image
    "dcim", "camera" -> Icons.Rounded.PhotoCamera
    "music", "podcasts", "audiobooks", "ringtones" -> Icons.Rounded.MusicNote
    "download", "downloads" -> Icons.Rounded.Download
    "documents", "docs" -> Icons.Rounded.Description
    else -> Icons.Rounded.Folder
}

/** The icon for any file or folder: an iOS-style folder, or a colored badge for files. */
@Composable
fun FileGlyph(name: String, kind: FileKind, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    // Plain symbols in the content color, Apple style (no colored badges); a little inside [size].
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        TvIcon(if (kind == FileKind.FOLDER) folderGlyph(name) else kind.icon(), size = size * 0.8f)
    }
}

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.FOLDER -> Icons.Rounded.Folder
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.AUDIO -> Icons.Rounded.AudioFile
    FileKind.DOCUMENT -> Icons.Rounded.Description
    FileKind.TEXT -> Icons.Rounded.TextSnippet
    FileKind.ARCHIVE -> Icons.Rounded.Archive
    FileKind.APK -> Icons.Rounded.Android
    FileKind.OTHER -> Icons.Rounded.InsertDriveFile
}


/** Compact toolbar button: icon plus optional label. */
@Composable
fun ToolButton(icon: ImageVector, label: String?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        // Glass pill with the lit rim; white with dark content when focused, as on Apple TV.
        border = ButtonDefaults.border(border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Palette.rim), shape = com.woodruff.tvexplorer.ui.theme.Shapes.pill)),
        colors = ButtonDefaults.colors(
            containerColor = Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Palette.background,
        ),
    ) {
        TvIcon(icon, size = 20.dp)
        if (label != null) Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Small rounded label, e.g. "4K" or "USB". */
@Composable
fun Chip(text: String, color: Color = Palette.accent, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Section heading used on Home and the cloud screens. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
        color = Palette.text,
        modifier = modifier,
    )
}

/** Thin usage bar for storage cards. */
@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.surfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                // Always blue: near the end of a film is not a warning.
                .background(Palette.accent),
        )
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
