package com.woodruff.tvexplorer.nowplaying

import android.os.SystemClock

/**
 * What the Apple TV plugged into this TV says about its own screen. Woodruff on the Apple TV tells this TV
 * (POST /api/appletv, with the pairing key, as the family's phones and Macs do) every 10 s or so while it's the
 * app on the Apple TV's screen, each time for the next [DEFAULT_SECONDS] or so, and that it isn't once it leaves
 * (for the background, or the Apple TV's screen saver). What it said lasts that long and no longer: an Apple TV
 * switched off, or off the network, stops counting by itself. Timed on the TV's steady clock (elapsedRealtime),
 * which standby doesn't stop and the time of day doesn't move.
 *
 * The Apple TV can't see which input this TV is on: what it says is weighed against what plays on this TV
 * itself ([showsAppleTv]).
 */
object AppleTvScreen {
    /** The shortest and the longest one word from the Apple TV lasts, and how long one that doesn't say lasts. */
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60
    const val DEFAULT_SECONDS = 25

    /** Until when (elapsedRealtime) Woodruff is on the Apple TV's screen; 0: it isn't. */
    @Volatile private var until = 0L

    /**
     * The Apple TV said Woodruff is on its screen ([on]) for the next [forSeconds] (kept within 5 to 60), or that
     * it isn't, at [now]. The latest word counts.
     */
    fun said(on: Boolean, forSeconds: Int = DEFAULT_SECONDS, now: Long = SystemClock.elapsedRealtime()) {
        until = if (on) now + forSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS) * 1000L else 0L
    }

    /** Whether Woodruff is on the Apple TV's screen at [now]: what the Apple TV last said, for as long as it said. */
    fun onScreen(now: Long = SystemClock.elapsedRealtime()): Boolean = until != 0L && now < until
}
