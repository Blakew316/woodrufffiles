package com.woodruff.tvexplorer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil
import kotlin.math.roundToInt
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

    // Cool navy (dark) and blue-white (light) to sit under the Woodruff Family blues.
    val background get() = pick(0xFF060A12, 0xFFF2F6FA)
    val backgroundTop get() = pick(0xFF10213A, 0xFFFFFFFF)
    // Liquid Glass: panels and cards are tinted glass over the glowing backdrop (see [drawAmbient]),
    // not solid slabs. Brighter glass when focused.
    val surface get() = pick(0x1CFFFFFF, 0x8CFFFFFF)
    val surfaceHigh get() = pick(0x33FFFFFF, 0xD9FFFFFF)
    val outline get() = pick(0x26FFFFFF, 0x2414202E)
    /** Frosted, nearly opaque glass for things that sit over busy content: dialogs, the open menu, banners. */
    val glass get() = pick(0xEB142236, 0xF2F8FBFE)
    /** The glass edge: a light rim, brightest along the top as if lit from above. */
    val rim get() = if (light) Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0x1414202E)))
        else Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x0FFFFFFF)))
    /** A focused card's rim: bright white glass edge (deeper blue in light mode, for contrast). */
    val focusRim get() = pick(0xD9FFFFFF, 0xFF3A7AB0)
    val text get() = pick(0xFFF2F6FA, 0xFF14202E)
    val muted get() = pick(0xFF93A6BC, 0xFF56677A)
    // Woodruff Family blue (logo #5592BF): lighter on dark backgrounds, deeper on light ones,
    // so text on and next to it stays readable. Dark mode puts navy text on the light blue.
    val accent get() = pick(0xFF6FB3E6, 0xFF3A7AB0)
    val accentSoft get() = pick(0x336FB3E6, 0x223A7AB0)
    val onAccent get() = pick(0xFF06203A, 0xFFFFFFFF)
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

/**
 * The app's backdrop: the base gradient plus two large, soft pools of colored light, so the
 * glass panels above have something to tint and the screen never reads as a flat slab.
 *
 * Painted once per size and theme into a small opaque picture, a quarter of the screen's width
 * and height, which every frame stretches (smoothed) over the screen: one opaque copy, where the
 * TV's graphics chip used to blend three full-screen gradients over each other on every frame.
 * The glows are so soft that the smaller picture looks the same.
 */
fun Modifier.ambientBackdrop(): Modifier = drawWithCache {
    val small = backdropPictureSize(size.width, size.height)
    val picture = ImageBitmap(small.width, small.height, ImageBitmapConfig.Argb8888, hasAlpha = false)
    CanvasDrawScope().draw(this, layoutDirection, Canvas(picture), small.toSize()) { drawAmbient() }
    val screen = IntSize(size.width.roundToInt(), size.height.roundToInt())
    onDrawBehind { drawImage(picture, dstSize = screen, filterQuality = FilterQuality.Low) }
}

/** The base gradient and the two glows, laid out on whatever size this draws into. */
private fun DrawScope.drawAmbient() {
    val a = if (Palette.light) Color(0x553A7AB0) else Color(0x663A7AB0)
    val b = if (Palette.light) Color(0x3352B6A8) else Color(0x4D5B4BB5)
    drawRect(Palette.backdrop)
    drawRect(Brush.radialGradient(listOf(a, Color.Transparent), center = Offset(size.width * 0.12f, size.height * 0.05f), radius = size.maxDimension * 0.55f))
    drawRect(Brush.radialGradient(listOf(b, Color.Transparent), center = Offset(size.width * 0.92f, size.height * 0.95f), radius = size.maxDimension * 0.6f))
}

/** The backdrop picture's size for a [width] x [height] px screen: a quarter each way, at least 1 px. */
internal fun backdropPictureSize(width: Float, height: Float): IntSize =
    IntSize(ceil(width / 4f).toInt().coerceAtLeast(1), ceil(height / 4f).toInt().coerceAtLeast(1))

/** A glass panel: tinted glass fill, clipped to [shape], with the lit rim. */
fun Modifier.glassPanel(shape: Shape, fill: Color? = null): Modifier =
    clip(shape).background(fill ?: Palette.surface).border(1.dp, Palette.rim, shape)

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
    val card = RoundedCornerShape(26.dp)
    val pill = RoundedCornerShape(50)
}

/** Shared look for every focusable card: glass that brightens and gains a bright rim on focus. */
object CardStyle {
    @Composable fun colors() = ClickableSurfaceDefaults.colors(
        containerColor = Palette.surface,
        contentColor = Palette.text,
        focusedContainerColor = Palette.surfaceHigh,
        focusedContentColor = Palette.text,
        pressedContainerColor = Palette.surfaceHigh,
    )

    @Composable fun border() = ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(1.dp, Palette.rim), shape = Shapes.card),
        focusedBorder = Border(BorderStroke(2.dp, Palette.focusRim), shape = Shapes.card),
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
    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        // Text placed straight on the backdrop (not inside a Surface) should follow the theme too.
        CompositionLocalProvider(LocalContentColor provides Palette.text, content = content)
    }
}
