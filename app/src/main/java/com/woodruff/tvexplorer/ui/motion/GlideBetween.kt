package com.woodruff.tvexplorer.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Shows [target]'s content; when [target] changes, the new content glides in while the old glides
 * out over it, both on springs, and the old leaves the composition once it's gone. Everything moves
 * in the contents' drawing layers ([layer]: alpha, scale, a shift), faded with ModulateAlpha (no
 * offscreen copy of a whole screen), so nothing is composed or laid out again per frame.
 *
 * [instant]: the old content goes at once instead (the new one still glides in). [forward]: whether
 * [target] lies ahead of the one it replaces (a page opened), for sliding the right way. [holder]: a
 * modifier for each content's holder, the one on show (false) or one gliding out (true). [fill]: the
 * holders fill this (screens, pages) rather than wrapping their content (a title).
 */
@Composable
fun <T> GlideBetween(
    target: T,
    modifier: Modifier = Modifier,
    enter: AnimationSpec<Float> = TvMotion.ScreenIn,
    exit: AnimationSpec<Float> = TvMotion.ScreenOut,
    animateFirst: Boolean = false,
    fill: Boolean = true,
    instant: (from: T, to: T) -> Boolean = { _, _ -> false },
    forward: (from: T, to: T) -> Boolean = { _, _ -> true },
    holder: (leaving: Boolean) -> Modifier = { Modifier },
    layer: GraphicsLayerScope.(shown: Float, entering: Boolean, forward: Boolean) -> Unit,
    content: @Composable (T) -> Unit,
) {
    val shows = remember { mutableStateListOf(Showing(target, if (animateFirst) 0f else 1f, forward = true)) }
    val front = shows.last()
    if (front.value != target) {
        // Worked out as the change is composed (as Crossfade does), so the new content is there in
        // this very frame.
        val ahead = forward(front.value, target)
        if (instant(front.value, target)) {
            shows.clear()
            shows.add(Showing(target, 0f, ahead))
        } else {
            for (s in shows) if (!s.leaving) { s.leaving = true; s.forward = ahead }
            // Back to one still gliding out: it turns around from where it is.
            val back = shows.firstOrNull { it.value == target }
            if (back != null) {
                shows.remove(back)
                back.leaving = false
                back.forward = ahead
                shows.add(back)
            } else {
                shows.add(Showing(target, 0f, ahead))
            }
        }
    }
    Box(modifier) {
        for (s in shows) {
            key(s) {
                val leaving = s.leaving
                Box(
                    holder(leaving)
                        .then(if (fill) Modifier.fillMaxSize() else Modifier)
                        .graphicsLayer {
                            layer(s.shown.value, !leaving, s.forward)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                ) { content(s.value) }
                LaunchedEffect(leaving) {
                    if (leaving) {
                        s.shown.animateTo(0f, exit)
                        shows.remove(s)
                    } else {
                        s.shown.animateTo(1f, enter)
                    }
                }
            }
        }
    }
}

/** One content [GlideBetween] shows: how much of it shows, and whether it's on its way out (and which way). */
private class Showing<T>(val value: T, start: Float, var forward: Boolean) {
    val shown = Animatable(start, visibilityThreshold = 0.002f)
    var leaving = false
}
