package com.woodruff.tvexplorer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * App colors for both themes. Every color is a getter over [light], which is Compose state, so
 * flipping the switch recolors the whole UI immediately without restarting.
 */
object Palette {
    var light by mutableStateOf(false)

    private fun pick(dark: Long, lightValue: Long) = Color(if (light) lightValue else dark)

    val background get() = pick(0xFF080B10, 0xFFF4F1EA)
    val backgroundTop get() = pick(0xFF131B28, 0xFFFFFFFF)
    val surface get() = pick(0xFF121822, 0xFFFFFFFF)
    val surfaceHigh get() = pick(0xFF1C2531, 0xFFEDE6D8)
    val outline get() = pick(0xFF2A3544, 0xFFD8D0C0)
    val text get() = pick(0xFFF2F4F7, 0xFF1B1F24)
    val muted get() = pick(0xFF93A1B4, 0xFF5B6570)
    // Darker amber on light backgrounds so it still reads; text on it flips to white.
    val accent get() = pick(0xFFFFB547, 0xFFB86E00)
    val accentSoft get() = pick(0x33FFB547, 0x26B86E00)
    val onAccent get() = pick(0xFF1F1300, 0xFFFFFFFF)
    val danger get() = pick(0xFFFF7B72, 0xFFD1242F)
    val success get() = pick(0xFF5DD39E, 0xFF1E9E63)
    val folder get() = pick(0xFFFFC857, 0xFFD99A00)
    val video get() = pick(0xFFFF8A65, 0xFFE0603A)
    val audio get() = pick(0xFFC792EA, 0xFF9C4DCC)
    val image get() = pick(0xFF7FD4A0, 0xFF2E9D5B)
    val document get() = pick(0xFF74B9FF, 0xFF2D7FD3)
    val archive get() = pick(0xFFBCAAA4, 0xFF8D6E63)
    val apk get() = pick(0xFFA5D86E, 0xFF6A9F2A)
    val drive get() = pick(0xFF4C8DF6, 0xFF1A73E8)
    val icloud get() = pick(0xFF8EC5FF, 0xFF3B8FE0)

    /** Full-screen backdrop: a soft lift at the top fading into the background. */
    val backdrop get() = Brush.verticalGradient(listOf(backgroundTop, background, background))
}

/** Remembers the light/dark choice across launches. */
object ThemeSetting {
    private const val PREFS = "settings"
    private const val KEY = "light_mode"

    fun load(context: Context) {
        Palette.light = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun toggle(context: Context) {
        Palette.light = !Palette.light
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, Palette.light).apply()
    }
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
    val scheme = if (Palette.light) lightColorScheme(
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
    ) else darkColorScheme(
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
        )
    MaterialTheme(colorScheme = scheme) {
        // Text placed straight on the backdrop (not inside a Surface) should follow the theme too.
        CompositionLocalProvider(LocalContentColor provides Palette.text, content = content)
    }
}
