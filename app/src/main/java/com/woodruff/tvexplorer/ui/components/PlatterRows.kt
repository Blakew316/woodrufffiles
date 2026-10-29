package com.woodruff.tvexplorer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import com.woodruff.tvexplorer.ui.motion.FocusMotionState
import com.woodruff.tvexplorer.ui.motion.FocusPlatter
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState

/** tv-material ListItem's corners, kept by the rows that took its place (and their platters). */
val ListRowShape = RoundedCornerShape(8.dp)

/**
 * A row's words and symbols coloured as they draw: [rest] off the focus, [focused] on the platter,
 * cross-fading with the row's own focus ([motion]). Asked while drawing, so the focus recomposes nothing.
 */
@Stable
class RowTint(private val motion: FocusMotionState, val rest: Color, val focused: Color) {
    /** The row's colour now. */
    val color: ColorProducer = ColorProducer { now() }
    /** At 80 %, as tv-material's ListItem drew its leading symbol and supporting lines. */
    val soft: ColorProducer = ColorProducer { now().let { it.copy(alpha = it.alpha * 0.8f) } }

    private fun now(): Color = lerp(rest, focused, motion.fraction.coerceIn(0f, 1f))
}

@Composable
fun rememberRowTint(motion: FocusMotionState, rest: Color, focused: Color): RowTint =
    remember(motion, rest, focused) { RowTint(motion, rest, focused) }

/**
 * Text in a row on a platter: the surrounding text style with [style] over it, as tv-material's Text
 * has, but coloured by [color] while it draws, so a row taking the focus redraws its words instead of
 * composing them again.
 */
@Composable
fun RowText(
    text: String,
    color: ColorProducer,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val merged = LocalTextStyle.current.merge(style)
    BasicText(
        text,
        modifier,
        style = if (fontWeight != null) merged.copy(fontWeight = fontWeight) else merged,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        color = color,
    )
}

/**
 * A list row on a [FocusPlatter], laid out as tv-material's ListItem was (its heights, its 16 x 12 dp
 * padding, a 32 dp leading slot, the headline in titleMedium and supporting lines in bodySmall) but
 * with no focused fill of its own: the list's platter glides under it. Its words and symbols take
 * [content] off the focus and [focusedContent] on the platter: through the [RowTint] the slots get
 * (cross-fading as they draw), or, for words that don't change, through the content colour, which
 * stays [content] (at 80 % in the leading and supporting slots, as the ListItem had). [container]:
 * its own fill off the focus (a selected row's), fading out as the platter arrives.
 */
@Composable
fun PlatterListItem(
    platter: FocusPlatter,
    onClick: () -> Unit,
    content: Color,
    focusedContent: Color,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    container: Color = Color.Transparent,
    focusedScale: Float = ListItemFocusScale,
    leading: (@Composable (RowTint) -> Unit)? = null,
    supporting: (@Composable (RowTint) -> Unit)? = null,
    headline: @Composable (RowTint) -> Unit,
) {
    val motion = rememberFocusMotionState()
    val tint = rememberRowTint(motion, content, focusedContent)
    TvCard(
        onClick = onClick,
        shape = ListRowShape,
        modifier = modifier,
        onLongClick = onLongClick,
        motion = motion,
        focusedScale = focusedScale,
        color = container,
        focusedColor = container.copy(alpha = 0f),
        platter = platter,
    ) {
        val minHeight = when {
            supporting != null -> 64.dp
            leading != null -> 56.dp
            else -> 48.dp
        }
        Row(
            Modifier.defaultMinSize(minHeight = minHeight).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides content.copy(alpha = content.alpha * 0.8f)) { leading(tint) }
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalContentColor provides content) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) { headline(tint) }
                }
                if (supporting != null) {
                    CompositionLocalProvider(LocalContentColor provides content.copy(alpha = content.alpha * 0.8f)) {
                        ProvideTextStyle(MaterialTheme.typography.bodySmall) { supporting(tint) }
                    }
                }
            }
        }
    }
}
