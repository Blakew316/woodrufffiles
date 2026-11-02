package com.woodruff.tvexplorer.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the loading ring shows over the film, and what it says (specs/player-feedback.md §3.1, §3.2). */
class BufferingTest {
    private fun look(waitingMs: Long?, stall: Stall? = null, stalledMs: Long = 0, reduced: Boolean = false) =
        Buffering.look(waitingMs, stall, stalledMs, reduced)

    @Test fun aShortWaitShowsNothing() {
        assertNull(look(waitingMs = null))
        assertNull(look(waitingMs = 0))
        assertNull(look(waitingMs = 499))
    }

    @Test fun aLongerWaitShowsTheRingAloneThenSaysItsStillLoading() {
        assertEquals(BufferingLook(ring = true, caption = null), look(waitingMs = 500))
        assertEquals(BufferingLook(ring = true, caption = null), look(waitingMs = 9_999))
        assertEquals(BufferingLook(ring = true, caption = "Still loading…"), look(waitingMs = 10_000))
        assertEquals(BufferingLook(ring = true, caption = "Still loading…"), look(waitingMs = 120_000))
    }

    @Test fun withReduceMotionTheStillRingAlwaysSaysWhatsHappening() {
        assertNull(look(waitingMs = 300, reduced = true))
        assertEquals(BufferingLook(ring = true, caption = "Loading…"), look(waitingMs = 500, reduced = true))
        assertEquals("Still loading…", look(waitingMs = 10_000, reduced = true)?.caption)
        assertEquals("Reconnecting…", look(waitingMs = null, stall = Stall.NETWORK, reduced = true)?.caption)
    }

    @Test fun tryingAgainSaysWhyWhetherOrNotItsWaitingToPlay() {
        // Paused while trying again (waiting null): the ring and its words stay, it's still trying.
        for (waiting in listOf(null, 0L, 600L, 15_000L)) {
            assertEquals(BufferingLook(ring = true, caption = "Reconnecting…"), look(waiting, Stall.NETWORK, stalledMs = 0))
            assertEquals(BufferingLook(ring = true, caption = "Reconnecting…"), look(waiting, Stall.BUSY, stalledMs = 0))
            assertEquals(
                BufferingLook(ring = true, caption = "Google Drive turned this down. Trying again…"),
                look(waiting, Stall.REFUSED, stalledMs = 0),
            )
        }
    }

    @Test fun afterHalfAMinuteWithoutAConnectionItPointsAtTheTvsInternet() {
        assertEquals(BufferingLook(ring = true, caption = "Reconnecting…"), look(null, Stall.NETWORK, stalledMs = 29_999))
        assertEquals(
            BufferingLook(ring = true, caption = "Still reconnecting.", detail = "Check the TV’s internet connection."),
            look(null, Stall.NETWORK, stalledMs = 30_000),
        )
        // Not when it's Google that's busy or refusing: the TV's own connection is fine then.
        assertEquals("Reconnecting…", look(null, Stall.BUSY, stalledMs = 300_000)?.caption)
        assertEquals("Google Drive turned this down. Trying again…", look(null, Stall.REFUSED, stalledMs = 300_000)?.caption)
    }

    @Test fun aSignInThatEndedIsACardWithNoRing() {
        val card = look(waitingMs = 20_000, stall = Stall.SIGNED_OUT)
        assertEquals(
            BufferingLook(ring = false, caption = "Sign in to Google Drive again", detail = "Open Streaming on this TV and scan the code with your phone."),
            card,
        )
        assertEquals(card, look(waitingMs = null, stall = Stall.SIGNED_OUT, reduced = true))
    }

    @Test fun theWordsNeverHaveCodesOrJargon() {
        val words = listOf(
            Buffering.LOADING, Buffering.STILL_LOADING, Buffering.RECONNECTING, Buffering.STILL_RECONNECTING,
            Buffering.CHECK_CONNECTION, Buffering.REFUSED, Buffering.SIGN_IN, Buffering.SIGN_IN_HOW,
        )
        for (w in words) {
            for (banned in listOf("error", "fail", "buffer", "retry", "please", "'", "...")) {
                assertEquals("“$w” contains “$banned”", -1, w.lowercase().indexOf(banned))
            }
        }
    }

    @Test fun theNextChangeIsTimedNotPolled() {
        assertEquals(500L, Buffering.nextChangeMs(0, null, 0))
        assertEquals(200L, Buffering.nextChangeMs(300, null, 0))
        assertEquals(9_500L, Buffering.nextChangeMs(500, null, 0))
        assertEquals(1L, Buffering.nextChangeMs(9_999, null, 0))
        assertNull(Buffering.nextChangeMs(10_000, null, 0))
        assertNull(Buffering.nextChangeMs(null, null, 0))
        assertEquals(30_000L, Buffering.nextChangeMs(null, Stall.NETWORK, 0))
        assertEquals(5_000L, Buffering.nextChangeMs(700, Stall.NETWORK, 25_000))
        assertNull(Buffering.nextChangeMs(null, Stall.NETWORK, 30_000))
        for (s in listOf(Stall.BUSY, Stall.REFUSED, Stall.SIGNED_OUT)) assertNull(Buffering.nextChangeMs(700, s, 0))
    }

    @Test fun theWaitStartsWhenItLoadsWhileMeantToPlay() {
        val clock = WaitClock()
        clock.update(playWhenReady = true, state = Player.STATE_BUFFERING, isPlaying = false, now = 1_000)
        assertEquals(1_000L, clock.since)
        // Still loading a moment later: the same wait.
        clock.update(playWhenReady = true, state = Player.STATE_BUFFERING, isPlaying = false, now = 1_400)
        assertEquals(600L, clock.waitedMs(Player.STATE_BUFFERING, 1_600))
        clock.update(playWhenReady = true, state = Player.STATE_READY, isPlaying = true, now = 1_700)
        assertNull(clock.since)
    }

    @Test fun aFailedTryAndTheNextKeepTheSameWaitSoTheRingDoesNotBlink() {
        val clock = WaitClock()
        clock.update(true, Player.STATE_BUFFERING, false, now = 0)
        // The connection failed (idle), and the player prepares again at once.
        clock.update(true, Player.STATE_IDLE, false, now = 3_000)
        assertEquals(3_000L, clock.waitedMs(Player.STATE_IDLE, 3_000))
        clock.update(true, Player.STATE_BUFFERING, false, now = 3_010)
        assertEquals(12_000L, clock.waitedMs(Player.STATE_BUFFERING, 12_000))
    }

    @Test fun pausingEndsTheWaitAndPlayingAgainStartsANewOne() {
        val clock = WaitClock()
        clock.update(true, Player.STATE_BUFFERING, false, now = 0)
        clock.update(false, Player.STATE_BUFFERING, false, now = 2_000)
        assertNull(clock.since)
        // Play pressed while it's still loading: the ring comes back half a second later, not at once.
        clock.update(true, Player.STATE_BUFFERING, false, now = 5_000)
        assertEquals(400L, clock.waitedMs(Player.STATE_BUFFERING, 5_400))
    }

    @Test fun pausedLoadingIsNoWait() {
        // A seek while paused loads too, but nothing is waiting to play: no ring.
        val clock = WaitClock()
        clock.update(playWhenReady = false, state = Player.STATE_BUFFERING, isPlaying = false, now = 0)
        assertNull(clock.since)
        clock.update(true, Player.STATE_BUFFERING, false, now = 0)
        clock.update(true, Player.STATE_ENDED, false, now = 100)
        assertNull(clock.since)
        clock.update(true, Player.STATE_BUFFERING, false, now = 200)
        clock.reset()
        assertNull(clock.since)
    }

    @Test fun aFilmHeldBackForItsSubtitleFilesShowsTheRingHalfASecondAfterOk() {
        // OK on a USB film: idle while its folder is looked in (a drive waking up), then it loads.
        val clock = WaitClock()
        clock.startNow(now = 0)
        clock.update(playWhenReady = true, state = Player.STATE_IDLE, isPlaying = false, now = 0)
        assertNull(look(clock.waitedMs(Player.STATE_IDLE, 400)))
        assertEquals(BufferingLook(ring = true, caption = null), look(clock.waitedMs(Player.STATE_IDLE, 500)))
        assertEquals(500L, Buffering.nextChangeMs(clock.waitedMs(Player.STATE_IDLE, 0), null, 0))
        // Loading at last, a second after OK: the same wait goes on, not a new one from there.
        clock.update(true, Player.STATE_BUFFERING, false, now = 1_000)
        assertEquals(1_200L, clock.waitedMs(Player.STATE_BUFFERING, 1_200))
        // Started again from its spot: a wait already going is kept.
        clock.startNow(now = 1_100)
        assertEquals(0L, clock.since)
    }

    @Test fun aFilmHeldBackWhilePausedShowsNoRing() {
        // Put back paused after an update, or come back to paused: nothing is waiting to play.
        val clock = WaitClock()
        clock.startNow(now = 0)
        clock.update(playWhenReady = false, state = Player.STATE_IDLE, isPlaying = false, now = 0)
        assertNull(clock.waitedMs(Player.STATE_IDLE, 2_000))
        // Nothing started: idle is no wait.
        assertNull(WaitClock().waitedMs(Player.STATE_IDLE, 5_000))
    }
}
