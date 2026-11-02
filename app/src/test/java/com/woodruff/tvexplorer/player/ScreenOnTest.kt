package com.woodruff.tvexplorer.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a film keeps the TV's screen on: playing or about to, and for the first minutes of reconnecting. */
class ScreenOnTest {
    @Test fun theScreenStaysOnForTheFirstMinutesOfReconnecting() {
        assertTrue(keepScreenOn(playing = true, state = Player.STATE_READY, stalledMs = null))
        assertTrue(keepScreenOn(true, Player.STATE_BUFFERING, null))
        // Paused, ended, or between tries with nothing going on: the TV's screensaver may come on.
        assertFalse(keepScreenOn(false, Player.STATE_READY, null))
        assertFalse(keepScreenOn(true, Player.STATE_ENDED, null))
        assertFalse(keepScreenOn(true, Player.STATE_IDLE, null))
        // Idle between tries after the Wi-Fi dropped: on for five minutes, then as before.
        assertTrue(keepScreenOn(true, Player.STATE_IDLE, 0))
        assertTrue(keepScreenOn(true, Player.STATE_IDLE, RECONNECTING_SCREEN_MS - 1))
        assertFalse(keepScreenOn(true, Player.STATE_IDLE, RECONNECTING_SCREEN_MS))
        assertFalse(keepScreenOn(false, Player.STATE_IDLE, 1_000))
        assertEquals(5 * 60_000L, RECONNECTING_SCREEN_MS)
    }
}
