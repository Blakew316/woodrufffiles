package com.woodruff.tvexplorer.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState

/**
 * A button, pill or tile that turns solid when focused, Apple TV style (usually white with dark words), as
 * tv-material's clickable Surface drew these, without its per-frame work: that Surface animates its stacking
 * order as the focus comes and goes, recomposing itself and repainting the whole screen on every frame of it.
 * This is a [TvCard]: it grows as a layer ([focusedScale]), its fill ([color] to [focusedColor]) and border
 * fade between their two looks as layers, and [body] colours its words and symbols with the [RowTint] it gets
 * ([content] to [focusedContent], through RowText and TintIcon), which fade the same way. So a focus change
 * repaints the button alone. Words and symbols that ask the content colour instead get [content].
 */
@Composable
fun FocusButton(
    onClick: () -> Unit,
    shape: Shape,
    color: Color,
    focusedColor: Color,
    content: Color,
    focusedContent: Color,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusedScale: Float = 1.06f,
    borderWidth: Dp = 0.dp,
    borderColor: Color = Color.Transparent,
    focusedBorderColor: Color = borderColor,
    body: @Composable BoxScope.(RowTint) -> Unit,
) {
    val motion = rememberFocusMotionState()
    val tint = rememberRowTint(motion, content, focusedContent)
    TvCard(
        onClick = onClick,
        shape = shape,
        modifier = modifier,
        onLongClick = onLongClick,
        motion = motion,
        focusedScale = focusedScale,
        color = color,
        focusedColor = focusedColor,
        borderWidth = borderWidth,
        borderColor = borderColor,
        focusedBorderColor = focusedBorderColor,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) { body(tint) }
    }
}
