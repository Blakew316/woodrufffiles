package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Starts something that resumes (a Continue watching tile, a title's Resume, an episode) the way it
 * should: once the family's other TVs' latest progress is in, so it starts where anyone last
 * stopped rather than where this TV last heard. Waits [FamilySync.RESUME_WAIT_MS] at most, and
 * usually not at all: Home, Streaming and a title's page fetch it as they open. The function given
 * runs after the wait, on the main thread, and works out then what to play (another TV may have gone
 * on to the next episode). One at a time: OK pressed again while one waits does nothing. Leaving
 * the screen meanwhile cancels it, and leaving Woodruff (Home on the remote) means it doesn't start.
 */
@Composable
internal fun rememberResume(): (start: () -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val waiting = remember { arrayOfNulls<Job>(1) }
    return remember(context, scope) {
        { start ->
            if (waiting[0]?.isActive != true) {
                // Undispatched: with nothing to wait for, it starts within this press, as before.
                waiting[0] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    FamilySync.awaitFresh(context)
                    if (context.inFront()) start()
                }
            }
        }
    }
}

/**
 * What a Continue watching tile ([tile]) plays once the other TVs' progress is in ([watching], as
 * merged): its show's entry now (another TV may have finished that episode and gone on to the next),
 * else the tile's own. A film is its own entry.
 */
internal fun latestOf(tile: RecentItem, watching: List<RecentItem>): RecentItem =
    watching.firstOrNull { RecentStore.showOf(it) == RecentStore.showOf(tile) } ?: tile

/** Whether the activity behind [this] is in front (a window over it, like a title's page, doesn't change that). */
internal fun Context.inFront(): Boolean {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        c = c.baseContext
    }
    return true
}
