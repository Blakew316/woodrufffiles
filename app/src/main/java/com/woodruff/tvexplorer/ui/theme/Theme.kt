package com.woodruff.tvexplorer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** "Home cinema" palette: deep blue-black, warm amber accent for focus and actions. */
object Palette {
    val background = Color(0xFF080B10)
    val backgroundTop = Color(0xFF131B28)
    val surface = Color(0xFF121822)
    val surfaceHigh = Color(0xFF1C2531)
    val outline = Color(0xFF2A3544)
    val text = Color(0xFFF2F4F7)
    val muted = Color(0xFF93A1B4)
    val accent = Color(0xFFFFB547)
    val accentSoft = Color(0x33FFB547)
    val onAccent = Color(0xFF1F1300)
    val danger = Color(0xFFFF7B72)
    val success = Color(0xFF5DD39E)
    val folder = Color(0xFFFFC857)
    val video = Color(0xFFFF8A65)
    val audio = Color(0xFFC792EA)
    val image = Color(0xFF7FD4A0)
    val document = Color(0xFF74B9FF)
    val archive = Color(0xFFBCAAA4)
    val apk = Color(0xFFA5D86E)
    val drive = Color(0xFF4C8DF6)
    val icloud = Color(0xFF8EC5FF)

    /** Full-screen backdrop: a soft lift at the top fading to near-black. */
    val backdrop = Brush.verticalGradient(listOf(backgroundTop, background, background))
}

object Shapes {
    val card = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(50)
}

/** Shared look for every focusable card: lifts, brightens and gets an amber outline on focus. */
object CardStyle {
    @Composable fun colors() = ClickableSurfaceDefaults.colors(
        containerColor = Palette.surface,
        contentColor = Palette.text,
        focusedContainerColor = Palette.surfaceHigh,
        focusedContentColor = Palette.text,
        pressedContainerColor = Palette.surfaceHigh,
    )

    @Composable fun border() = ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(1.dp, Palette.outline), shape = Shapes.card),
        focusedBorder = Border(BorderStroke(3.dp, Palette.accent), shape = Shapes.card),
    )

    @Composable fun shape() = ClickableSurfaceDefaults.shape(Shapes.card)
}

@Composable
fun TvExplorerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.onAccent,
            secondary = Palette.drive,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.muted,
            error = Palette.danger,
        ),
        content = content,
    )
}
