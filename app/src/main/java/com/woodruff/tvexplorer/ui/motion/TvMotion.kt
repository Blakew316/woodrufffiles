@file:OptIn(ExperimentalFoundationApi::class)

package com.woodruff.tvexplorer.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every motion number in one place, Apple TV style: quick in, a soft settle, never behind the remote.
 * Focus and scrolling run on springs, so a quick reversal curves instead of snapping (a spring keeps
 * its speed when its target changes).
 */
object TvMotion {
    /**
     * Focus arriving: there in about 12 drawn frames with a barely-there settle. Short on purpose: on a
     * TV's slow graphics chip every animated frame is a whole frame to draw.
     */
    val FocusIn: AnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 900f)
    /** Focus leaving: no bounce. */
    val FocusOut: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1000f)
    const val PosterScale = 1.10f
    const val CardScale = 1.06f
    const val RailScale = 1.04f
    const val RowScale = 1.03f
    val PosterLift: Dp = 4.dp
    /**
     * Scrolling to the focus: critically damped, so it never overshoots a list's end. When presses come
     * fast the running spring is retargeted (speed kept), so a held arrow glides instead of jerking.
     */
    val RowScroll: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1200f)
    val ColumnScroll: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1000f)
    /**
     * A poster row gliding along so the focused poster rests at its leading edge (PivotScroll), as in
     * the Apple TV app: softer than [RowScroll], so every step right reads as the row gliding, and
     * critically damped, so it eases into the row's end instead of being cut off there.
     */
    val PosterRowGlide: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f)
    /** A poster row is about 5 posters wide: all of it is composed ahead, while the TV is idle (+1). */
    const val PosterRowPrefetch = 6

    // ---- The focus platter: one highlight gliding from row to row (FocusPlatter) ----------------

    /**
     * The platter gliding from the row the focus leaves to the row it lands on: a glide you can see
     * (90 % of the way in 140 ms, within 1 % of it by 200) that overshoots by half a percent and
     * settles back, as tvOS's focus does (FocusPlatterTest holds it to these numbers). Each row's own
     * focus (its growth, its words turning dark) runs on the same spring, so they change as the
     * platter arrives, and lists with a platter scroll on it too, so the platter and the rows under it
     * move as one.
     */
    const val GlideDamping = 0.86f
    const val GlideStiffness = 550f
    val Glide: SpringSpec<Float> = spring(dampingRatio = GlideDamping, stiffness = GlideStiffness)
    /** The platter showing up on the row the focus comes into a list at: a quick fade as it grows from [PlatterAppearScale]. */
    const val PlatterInStiffness = 900f
    /** The platter fading out where it is as the focus leaves the list (both critically damped). */
    const val PlatterOutStiffness = 1100f
    const val PlatterAppearScale = 0.94f

    // ---- Screens, pages and banners, in their drawing layers (fades with ModulateAlpha) ----------

    /** A screen chosen in the side menu coming in: a fade and a gentle zoom from [ScreenEnterScale]. */
    val ScreenIn: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 380f)
    /** The screen it replaces fading out (and shrinking a touch, to [ScreenLeaveScale]): quicker. */
    val ScreenOut: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 800f)
    const val ScreenEnterScale = 0.97f
    const val ScreenLeaveScale = 0.985f
    /** A Settings page fading in as it slides [PageShift] of its width in from the side; the old one goes the other way. */
    val PageIn: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 500f)
    val PageOut: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1000f)
    const val PageShift = 0.1f
    /** The side menu gliding in from the left edge, the screen behind it dimming with it, and back. */
    val RailReveal: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 420f)
    /** A banner (a notice, a message) gliding in from its edge with a soft settle; going, quicker and without one. */
    val BannerIn: SpringSpec<Float> = spring(dampingRatio = GlideDamping, stiffness = 380f)
    val BannerOut: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)

    // ---- Windows: dialogs, pop-ups, side sheets and the player's screens ------------------------
    // WindowManager animates these on the system's own thread, so they stay smooth while the app is
    // busy building the new window. They're res/anim files (the system can't run Kotlin); the numbers
    // below are the same ones, and WindowMotionTest checks the files against them.

    /** Apple's ease-out, off quickly and gliding to a stop (res/interpolator/tv_glide.xml). */
    val GlideEase: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    /** A window arriving: its zoom (and slide) take this long, */
    const val PresentMs = 300
    /** its fade a little less, so it's solid while it settles. */
    const val PresentFadeMs = 200
    /** Going: quicker, the same moves in reverse. */
    const val DismissMs = 180
    /** A dialog or pop-up comes in from just larger than itself (tvOS's alerts do), and leaves toward [DialogDismissScale]. */
    const val DialogScale = 1.06f
    const val DialogDismissScale = 1.04f
    /** A full-screen page (a title, search, the player) grows in from just smaller, as screens do, and shrinks back going. */
    const val PageScale = 0.97f
    /** A side sheet slides in from the right edge by this share of the screen's width (and back out). */
    const val SheetShiftPercent = 7
}

/** Where the focused item's leading edge comes to rest: [fraction] of the visible length, plus [offset]. */
data class Pivot(val fraction: Float = 0f, val offset: Dp = 0.dp)

/**
 * Scrolls so the focused item sits at a fixed spot (the pivot), like Apple TV, instead of walking the
 * focus to the edge and then sliding. Near the ends of a list the distance is clamped to what can
 * still scroll, so the spring eases into the end instead of being cut off there (a scroll that isn't
 * used up stops the animation abruptly).
 */
internal class PivotBringIntoViewSpec(
    private val fraction: Float,
    private val offsetPx: Float,
    override val scrollAnimationSpec: AnimationSpec<Float>,
    private val forwardRoom: () -> Float,
    private val backwardRoom: () -> Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        pivotDistance(offset, size, containerSize, fraction, offsetPx, forwardRoom(), backwardRoom())
}

/** The scroll that brings an item at [offset] (of [size]) to the pivot, within what can still scroll. */
internal fun pivotDistance(
    offset: Float, size: Float, containerSize: Float, fraction: Float, offsetPx: Float, forward: Float, backward: Float,
): Float {
    val pivot = (fraction * containerSize + offsetPx).coerceIn(0f, containerSize)
    // An item too big to fit after the pivot ends flush with the far edge (or starts at the near one).
    val target = if (size <= containerSize - pivot) pivot else (containerSize - size).coerceAtLeast(0f)
    return (offset - target).coerceIn(-backward.coerceAtLeast(0f), forward.coerceAtLeast(0f))
}

/** Pivot scrolling for a lazy row or column (and anything scrolling inside it that doesn't set its own). */
@Composable
fun PivotScroll(state: LazyListState, pivot: Pivot, animation: AnimationSpec<Float> = TvMotion.RowScroll, content: @Composable () -> Unit) =
    ProvidePivot(state, pivot, animation, { state.forwardRoom() }, { state.backwardRoom() }, content)

/** Pivot scrolling for a lazy grid. */
@Composable
fun PivotScroll(state: LazyGridState, pivot: Pivot, animation: AnimationSpec<Float> = TvMotion.ColumnScroll, content: @Composable () -> Unit) =
    ProvidePivot(state, pivot, animation, { state.forwardRoom() }, { state.backwardRoom() }, content)

/** Pivot scrolling for a plain scrolling column or row. */
@Composable
fun PivotScroll(state: ScrollState, pivot: Pivot, animation: AnimationSpec<Float> = TvMotion.ColumnScroll, content: @Composable () -> Unit) =
    ProvidePivot(state, pivot, animation, { (state.maxValue - state.value).toFloat() }, { state.value.toFloat() }, content)

@Composable
private fun ProvidePivot(
    key: Any, pivot: Pivot, animation: AnimationSpec<Float>,
    forward: () -> Float, backward: () -> Float, content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val spec = remember(key, pivot, animation, density) {
        PivotBringIntoViewSpec(pivot.fraction, with(density) { pivot.offset.toPx() }, animation, forward, backward)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

private fun LazyListState.forwardRoom(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
}

private fun LazyListState.backwardRoom(): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else Float.POSITIVE_INFINITY

private fun LazyGridState.forwardRoom(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    val end = if (info.orientation == Orientation.Vertical) last.offset.y + last.size.height else last.offset.x + last.size.width
    return (end + info.afterContentPadding - info.viewportEndOffset).toFloat()
}

private fun LazyGridState.backwardRoom(): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else Float.POSITIVE_INFINITY

/**
 * A poster row's scroll state that has its whole visible width composed ahead when the row is
 * prefetched (by default only its first 2 items are, so the rest were built in the frame it slid in).
 */
fun posterRowState(firstVisibleItemIndex: Int = 0): LazyListState =
    LazyListState(firstVisibleItemIndex, 0, LazyListPrefetchStrategy(TvMotion.PosterRowPrefetch))
