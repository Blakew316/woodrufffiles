package com.woodruff.tvexplorer.ui.motion

import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.Window
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.woodruff.tvexplorer.util.Presence

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

private val dropRepeat: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = {
    // Its window's keys already count as use there (DialogWindows); this is for a dialog whose window
    // couldn't be followed, so its keys still hold an update back and the in-app slideshow off.
    Presence.pressed()
    DpadGate.shouldDrop(it.nativeKeyEvent)
}

/**
 * The same gate for a dialog window, whose keys never pass through the activity's dispatchKeyEvent.
 * Every Dialog root has it (title pages, menus, filters, search, every DialogFrame), and it's also
 * what makes the dialog one of Woodruff's windows ([DialogWindows]): its keys count as use, the
 * phone's remote and its TV Screen reach it, the remote's Search button works in it, and Settings →
 * Smoothness counts its frames. (A key passing both this and its window's gate gets the same answer
 * from each.)
 */
fun Modifier.dpadGate(): Modifier = this then DpadGateElement

private object DpadGateElement : ModifierNodeElement<DpadGateNode>() {
    override fun create() = DpadGateNode()
    override fun update(node: DpadGateNode) = Unit
    override fun hashCode(): Int = 0x6470_6164
    override fun equals(other: Any?): Boolean = other === this
    override fun InspectorInfo.inspectableProperties() { name = "dpadGate" }
}

/** Gates held arrows in a dialog, and registers the dialog's window while it's in the dialog. */
private class DpadGateNode : Modifier.Node(), KeyInputModifierNode, CompositionLocalConsumerModifierNode {
    private var window: Window? = null

    override fun onAttach() {
        // Inside a Compose Dialog, the view composing this sits in the dialog's own window.
        window = (currentValueOf(LocalView).parent as? DialogWindowProvider)?.window?.also(DialogWindows::register)
    }

    override fun onDetach() {
        window?.let(DialogWindows::unregister)
        window = null
    }

    override fun onPreKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean = dropRepeat(event)

    override fun onKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean = false
}
