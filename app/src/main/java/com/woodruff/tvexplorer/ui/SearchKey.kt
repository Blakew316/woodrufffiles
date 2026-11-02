package com.woodruff.tvexplorer.ui

import android.view.KeyEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The remote's Search button (and voice search, if a TV ever hands that key to apps), pressed
 * anywhere in Woodruff's main window: a screen, a title page, a menu, the filters, a confirmation.
 *
 * Each pop-up is a window of its own, so its keys never pass through the main window: a Search press
 * there used to fall through to Android, which on a TV opens its own assistant over Woodruff. Now
 * the main window ([Host]) and every pop-up of its (see [com.woodruff.tvexplorer.ui.motion.DialogWindows])
 * hand it here instead, and whoever opens Search collects [presses]. The player keeps the TV's own
 * behaviour: it isn't a [Host].
 */
object SearchKey {
    /** A window whose Search presses, and those of its pop-ups, open Woodruff's Search. */
    interface Host

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One per press of Search (a held button counts once). */
    val presses: SharedFlow<Unit> = requests.asSharedFlow()

    /**
     * Takes [event] if it's the Search key: its first press asks for Search, and every part of it
     * (press, repeats, release) is used up here, so Android doesn't act on it too. False for any
     * other key. Main thread.
     */
    fun take(event: KeyEvent): Boolean {
        if (!isSearch(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) requests.tryEmit(Unit)
        return true
    }

    /** Whether [keyCode] is the remote's Search or voice-search button. */
    fun isSearch(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
}
