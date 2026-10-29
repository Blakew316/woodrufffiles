package com.woodruff.tvexplorer.ui.motion

import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Holding an arrow: Android repeats it about 20 times a second. A repeat is let through only once the
 * previous move has been drawn (two frames since it) and a minimum gap has passed; repeats that are
 * already stale are dropped. So presses never queue up, and the focus stops the moment the button is
 * let go. Single presses always go through.
 */
object DpadGate {
    private const val STALE_MS = 100L
    private const val MIN_SIDEWAYS_MS = 90L   // Left/Right held: about 11 moves a second
    private const val MIN_UPDOWN_MS = 130L    // Up/Down moves a whole row or shelf
    private var lastMoveAt = 0L
    private var framesSinceMove = 2
    // The press last answered. The same press can reach a second gate (a dialog's own, and one put
    // inside it): it gets the same answer there, instead of looking like a repeat that came too soon.
    private var seenTime = -1L
    private var seenDown = -1L
    private var seenCode = -1
    private var seenRepeat = -1
    private var seenDrop = false
    private val counter = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (++framesSinceMove < 2) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** True when [event] is a repeat to drop. Main thread only (key events are). */
    fun shouldDrop(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val minGap = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> MIN_SIDEWAYS_MS
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> MIN_UPDOWN_MS
            else -> return false
        }
        if (event.eventTime == seenTime && event.downTime == seenDown && event.keyCode == seenCode && event.repeatCount == seenRepeat) {
            return seenDrop
        }
        seenTime = event.eventTime; seenDown = event.downTime; seenCode = event.keyCode; seenRepeat = event.repeatCount
        val now = SystemClock.uptimeMillis()
        seenDrop = event.repeatCount > 0 &&
            (now - event.eventTime > STALE_MS || framesSinceMove < 2 || now - lastMoveAt < minGap)
        if (seenDrop) return true
        lastMoveAt = now
        framesSinceMove = 0
        Choreographer.getInstance().run { removeFrameCallback(counter); postFrameCallback(counter) }
        return false
    }
}

private val dropRepeat: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { DpadGate.shouldDrop(it.nativeKeyEvent) }

/** The same for a dialog window, whose keys never pass through the activity's dispatchKeyEvent. */
fun Modifier.dpadGate(): Modifier = onPreviewKeyEvent(dropRepeat)
