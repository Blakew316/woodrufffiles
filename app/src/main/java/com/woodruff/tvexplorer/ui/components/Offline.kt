package com.woodruff.tvexplorer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay

/**
 * How long a failure to reach Google stays unsaid: most TVs waking from standby reconnect within it,
 * so until then what was on screen stays, silently. Then the calm offline words.
 */
const val OFFLINE_GRACE_MS = 4_000L

/** How long "Back online" shows once a retry gets through, before the words fade away. */
const val BACK_ONLINE_MS = 2_000L

/**
 * Whether [offline] has held for [OFFLINE_GRACE_MS] (from when this first saw it, or [since] when the
 * state knows when it began): only then do a screen's offline words show. Back to false at once when
 * it clears. A coroutine delay while it waits, nothing while online.
 */
@Composable
fun rememberOfflineShown(offline: Boolean, since: Long = 0L): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(offline, since) {
        if (!offline) { shown = false; return@LaunchedEffect }
        val left = if (since > 0) since + OFFLINE_GRACE_MS - System.currentTimeMillis() else OFFLINE_GRACE_MS
        if (left > 0) delay(left)
        shown = true
    }
    return shown && offline
}

/**
 * The words for a TV that can't get through, the same everywhere (calm, never red, never "sign in
 * again"): "Offline" when the TV has no working network, "Can't reach Google" when the network is
 * fine and Google doesn't answer.
 */
object OfflineWords {
    const val OFFLINE = "Offline"
    const val RECONNECTING = "Reconnecting…"
    const val BACK_ONLINE = "Back online"

    /** In full: "Offline" or "Can't reach Google right now" ([google]: the network is fine, Google isn't answering). */
    fun full(google: Boolean) = if (google) "Can’t reach Google right now" else OFFLINE

    /** Where room is short (a search field's end): "Offline" or "Can't reach Google". */
    fun short(google: Boolean) = if (google) "Can’t reach Google" else OFFLINE
}

/**
 * A search field's trailing status ([SearchBar]): a symbol and a word or two saying why what it
 * searches is the saved copy. [good]: the news is good ("Back online"), and the symbol shows it.
 */
@Immutable
data class FieldStatus(val icon: ImageVector, val words: String, val good: Boolean = false)
