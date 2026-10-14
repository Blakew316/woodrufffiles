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
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

/** Tinted vector icon; follows the surrounding content color (which flips when focused). */
@Composable
fun TvIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/**
 * iOS-style app-icon badge: a white glyph on a rounded square ("squircle") filled with a soft
 * top-to-bottom gradient of [color], like icons on the Home Screen and in iOS Settings.
 */
@Composable
fun IconBadge(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.verticalGradient(listOf(lighten(color, 0.22f), color))),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size * 0.62f), colorFilter = ColorFilter.tint(Color.White))
    }
}

private fun lighten(c: Color, amount: Float) = Color(
    red = c.red + (1f - c.red) * amount,
    green = c.green + (1f - c.green) * amount,
    blue = c.blue + (1f - c.blue) * amount,
    alpha = c.alpha,
)

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

fun FileKind.color(): Color = when (this) {
    FileKind.FOLDER -> Palette.folder
    FileKind.IMAGE -> Palette.image
    FileKind.VIDEO -> Palette.video
    FileKind.AUDIO -> Palette.audio
    FileKind.DOCUMENT, FileKind.TEXT -> Palette.document
    FileKind.ARCHIVE -> Palette.archive
    FileKind.APK -> Palette.apk
    FileKind.OTHER -> Palette.muted
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
        colors = ButtonDefaults.colors(
            containerColor = Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContainerColor = Palette.accent,
            focusedContentColor = Palette.onAccent,
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
                .background(if (fraction > 0.9f) Palette.danger else Palette.accent),
        )
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
