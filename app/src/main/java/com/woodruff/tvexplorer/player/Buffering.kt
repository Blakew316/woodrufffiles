package com.woodruff.tvexplorer.player

import androidx.media3.common.Player

/**
 * What the loading ring over the film shows ([BufferingIndicator]): the turning ring, or not (a
 * sign-in that ended has nothing to wait for), and the words under it, if any. [detail] is a
 * second, quieter line.
 */
internal data class BufferingLook(val ring: Boolean, val caption: String?, val detail: String? = null)

/**
 * When the loading ring shows and what it says (specs/player-feedback.md §3.1, §3.2): nothing for a
 * wait shorter than [SHOW_AFTER_MS] (most seeks), then the ring alone, then "Still loading…" after
 * [STILL_LOADING_MS]; while the player is trying again after the connection or Google failed, the
 * ring with what's going on, whether or not it's paused. Driven by the player's own events and one
 * timer to the next change ([nextChangeMs]); nothing polls.
 */
internal object Buffering {
    /** Shorter waits show nothing: the ring would only flash. */
    const val SHOW_AFTER_MS = 500L
    const val STILL_LOADING_MS = 10_000L
    /** After this long without a connection it's worth looking at the TV's own Wi-Fi. */
    const val STILL_RECONNECTING_MS = 30_000L

    const val LOADING = "Loading…"
    const val STILL_LOADING = "Still loading…"
    const val RECONNECTING = "Reconnecting…"
    /** After [STILL_RECONNECTING_MS]: what's happening, and under it, quieter, what to do. */
    const val STILL_RECONNECTING = "Still reconnecting."
    const val CHECK_CONNECTION = "Check the TV’s internet connection."
    const val REFUSED = "Google Drive turned this down. Trying again…"
    const val SIGN_IN = "Sign in to Google Drive again"
    const val SIGN_IN_HOW = "Open Streaming on this TV and scan the code with your phone."

    /**
     * The look for a player that has been meant to play without playing for [waitingMs] (null: it
     * isn't waiting), trying again for [stalledMs] because of [stall] (null: it isn't). [reduced]:
     * Reduce motion is on, so the ring stands still and words always say what's happening.
     * Null: nothing on screen.
     */
    fun look(waitingMs: Long?, stall: Stall?, stalledMs: Long, reduced: Boolean): BufferingLook? = when (stall) {
        Stall.SIGNED_OUT -> BufferingLook(ring = false, caption = SIGN_IN, detail = SIGN_IN_HOW)
        Stall.NETWORK ->
            if (stalledMs >= STILL_RECONNECTING_MS) BufferingLook(ring = true, caption = STILL_RECONNECTING, detail = CHECK_CONNECTION)
            else BufferingLook(ring = true, caption = RECONNECTING)
        Stall.BUSY -> BufferingLook(ring = true, caption = RECONNECTING)
        Stall.REFUSED -> BufferingLook(ring = true, caption = REFUSED)
        null -> when {
            waitingMs == null || waitingMs < SHOW_AFTER_MS -> null
            waitingMs >= STILL_LOADING_MS -> BufferingLook(ring = true, caption = STILL_LOADING)
            else -> BufferingLook(ring = true, caption = if (reduced) LOADING else null)
        }
    }

    /** How long until [look] changes by itself with the same inputs; null when it won't. */
    fun nextChangeMs(waitingMs: Long?, stall: Stall?, stalledMs: Long): Long? = when (stall) {
        null -> waitingMs?.let { waited -> listOf(SHOW_AFTER_MS, STILL_LOADING_MS).firstOrNull { it > waited }?.minus(waited) }
        Stall.NETWORK -> (STILL_RECONNECTING_MS - stalledMs).takeIf { it > 0 }
        else -> null
    }
}

/**
 * Since when the player has been meant to play without playing: loading the film's start, a seek,
 * or a stall. The moment is kept through a failed try and the try straight after it (an error, and
 * the player preparing again at once), so the ring doesn't blink off and on between them. Playing,
 * pausing, being ready or reaching the end clears it.
 */
internal class WaitClock {
    var since: Long? = null
        private set

    /** Updates it from the player's [playWhenReady], [state] and [isPlaying] at [now]. */
    fun update(playWhenReady: Boolean, state: Int, isPlaying: Boolean, now: Long) {
        when {
            isPlaying || !playWhenReady || state == Player.STATE_READY || state == Player.STATE_ENDED -> since = null
            state == Player.STATE_BUFFERING -> if (since == null) since = now
            // Idle: between a failed try and the next one. Whatever was started is kept.
        }
    }

    /**
     * How long it has waited at [now], while it's loading ([state] buffering) or between a failed try
     * and the next (idle, the wait kept: see [update]); null otherwise.
     */
    fun waitedMs(state: Int, now: Long): Long? =
        since?.takeIf { state == Player.STATE_BUFFERING || state == Player.STATE_IDLE }?.let { now - it }

    fun reset() {
        since = null
    }

    /**
     * A film asked to play is held back a moment before it loads (the subtitle files beside it being
     * found, a USB drive waking up): the wait starts now, at the press, not once it starts loading,
     * so the ring comes [Buffering.SHOW_AFTER_MS] after OK. Idle meanwhile, it counts as waiting
     * ([waitedMs]); paused, [update] clears it, as for any wait.
     */
    fun startNow(now: Long) {
        if (since == null) since = now
    }
}
