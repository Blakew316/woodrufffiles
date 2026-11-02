package com.woodruff.tvexplorer.ui.motion

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Back from far down a long list goes to its top first, as on Apple TV and Netflix
 * (browsing-motion §2, §3.3): one press glides the list back to the top and puts the focus on the
 * first thing there; the next press does what Back did before (the side menu). Holding Up for ten
 * seconds through a big library is no longer the way back to the tabs, search and Filter.
 *
 * Only where Back used to open the side menu (a library's top level, the top of Family Memories):
 * inside a show's folders or a sub-folder, Back still goes up a folder.
 */
object BackToTop {
    /** A list this many lines (rows of posters) from its top, or closer, glides all the way there. */
    const val GLIDE_LINES = 8

    /**
     * Further away, it first jumps to this line, then glides from there: a long glide past dozens
     * of rows reads as a blur, and costs a frame for every row it composes on the way.
     */
    const val JUMP_TO_LINE = 3

    /** A second Back this soon after one that went to the top goes on to the next level, as on tvOS. */
    const val DOUBLE_PRESS_MS = 400L

    /** Frames to wait for the first thing at the top to be there to take the focus (as FocusWhenReady). */
    const val FOCUS_FRAMES = 10

    /** The line to jump to before gliding up from [line] (the first one showing), or null to glide from where it is. */
    fun jumpFirst(line: Int): Int? = if (line > GLIDE_LINES) JUMP_TO_LINE else null

    /** Whether a Back at [now] comes within [DOUBLE_PRESS_MS] of the one at [last] (null: none lately) that went to the top. */
    fun isDoublePress(last: Long?, now: Long): Boolean = last != null && now >= last && now - last < DOUBLE_PRESS_MS
}

/** Whether this list shows anything but its very top (its first item, from its first pixel). */
fun LazyListState.isScrolled(): Boolean = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** Whether this grid shows anything but its very top. */
fun LazyGridState.isScrolled(): Boolean = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** Glides this list to its top, by [BackToTop]'s rule (a jump first when it's far down). */
suspend fun LazyListState.glideToTop() {
    BackToTop.jumpFirst(firstVisibleItemIndex)?.let { scrollToItem(it) }
    animateScrollToItem(0)
}

/** Glides this grid to its top, by [BackToTop]'s rule, counting its lines (rows of posters), not its items. */
suspend fun LazyGridState.glideToTop() {
    val perLine = layoutInfo.maxSpan.coerceAtLeast(1)
    BackToTop.jumpFirst(firstVisibleItemIndex / perLine)?.let { scrollToItem(it * perLine) }
    animateScrollToItem(0)
}

/**
 * After a glide to the top: the focus onto the first of [targets] there to take it, waiting up to
 * [BackToTop.FOCUS_FRAMES] frames for one to be composed. Nothing at all if the focus has left the
 * list meanwhile ([holdsFocus] false: a second Back opened the side menu, say), so it never takes the
 * focus back from there. [orElse] when none of them takes it.
 */
suspend fun focusAtTop(holdsFocus: () -> Boolean, targets: List<FocusRequester>, orElse: () -> Unit) {
    repeat(BackToTop.FOCUS_FRAMES) {
        if (!holdsFocus()) return
        if (targets.any { it.takeFocus() }) return
        withFrameNanos {}
    }
    if (holdsFocus()) orElse()
}

/** Asks for the focus; false when this isn't composed yet or the focus couldn't move there. */
private fun FocusRequester.takeFocus(): Boolean = runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

/**
 * Back while [scrolled] says the list is away from its top (with the focus in it) runs [toTop]: the
 * glide up and the focus onto the first thing there. A second Back within
 * [BackToTop.DOUBLE_PRESS_MS] goes on to [next] (the side menu) while the glide finishes underneath.
 * While [scrolled] is false it's off, and Back does what it did before.
 *
 * [scrolled] is read as its answer changes (scrolling, the focus moving), not in composition: only
 * this handler is composed again when it flips, never the list around it.
 */
@Composable
fun BackToTopHandler(scrolled: () -> Boolean, next: () -> Unit, toTop: suspend CoroutineScope.() -> Unit) {
    val latest by rememberUpdatedState(scrolled)
    val enabled by remember { derivedStateOf { latest() } }
    val scope = rememberCoroutineScope()
    // When the last Back that went to the top was pressed (null: not lately).
    val lastPress = remember { arrayOfNulls<Long>(1) }
    BackHandler(enabled) {
        val now = SystemClock.uptimeMillis()
        if (BackToTop.isDoublePress(lastPress[0], now)) {
            lastPress[0] = null
            next()
        } else {
            lastPress[0] = now
            scope.launch { toTop() }
        }
    }
}
