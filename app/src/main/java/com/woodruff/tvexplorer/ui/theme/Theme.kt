package com.woodruff.tvexplorer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Palette {
    val background = Color(0xFF0F1216)
    val surface = Color(0xFF161B22)
    val surfaceHigh = Color(0xFF1F2630)
    val text = Color(0xFFE6EDF3)
    val muted = Color(0xFF8B98A5)
    val accent = Color(0xFF4FC3F7)
    val onAccent = Color(0xFF00222F)
    val danger = Color(0xFFFF7B72)
    val folder = Color(0xFFFFC857)
    val video = Color(0xFFFF8A65)
    val audio = Color(0xFFBA68C8)
    val image = Color(0xFF81C784)
    val document = Color(0xFF64B5F6)
    val archive = Color(0xFFA1887F)
    val apk = Color(0xFF9CCC65)
}

@Composable
fun TvExplorerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.onAccent,
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
