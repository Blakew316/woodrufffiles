package com.woodruff.tvexplorer.update

import com.woodruff.tvexplorer.update.OnScreen.Film
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When an update may restart Woodruff with nobody asked ([Updater.quietNow]). */
class QuietNowTest {
    private val minute = 60_000L

    private fun quiet(sinceUse: Long, film: Film = Film.NONE, busy: Boolean = false, screenOn: Boolean = true, live: Boolean = false) =
        Updater.quietNow(sinceUse, film, busy, screenOn, live)

    @Test fun twoMinutesWithoutAKeyAnywhereIsQuiet() {
        assertFalse(quiet(Updater.IDLE_MS - 1))
        assertTrue(quiet(Updater.IDLE_MS))
    }

    @Test fun aFilmPlayingIsNeverInterrupted() {
        assertFalse(quiet(10 * 60 * minute, Film.PLAYING))
        // Not even with the screen off (some TVs play the sound on with the picture off).
        assertFalse(quiet(10 * 60 * minute, Film.PLAYING, screenOn = false))
    }

    @Test fun aPausedFilmWaitsHalfAnHourForSomeoneToComeBack() {
        assertFalse(quiet(Updater.IDLE_MS, Film.PAUSED))
        assertFalse(quiet(Updater.PAUSED_IDLE_MS - 1, Film.PAUSED))
        assertTrue(quiet(Updater.PAUSED_IDLE_MS, Film.PAUSED))
        // With the TV off nobody is watching: it may go at once (and comes back paused).
        assertTrue(quiet(0, Film.PAUSED, screenOn = false))
    }

    @Test fun aCopyOrATransferIsNeverCutShort() {
        assertFalse(quiet(10 * 60 * minute, busy = true))
        assertFalse(quiet(10 * 60 * minute, busy = true, screenOn = false))
    }

    @Test fun withTheScreenOffNobodyIsWatching() = assertTrue(quiet(0, screenOn = false))

    @Test fun theDoorbellsCameraUpIsNeverRestartedUnder() {
        // Someone looking at who's at the door, however long without a key (over a film it stays up).
        assertFalse(quiet(10 * 60 * minute, live = true))
        assertFalse(quiet(10 * 60 * minute, Film.PAUSED, live = true))
        // Once it's closed, the usual rules.
        assertTrue(quiet(Updater.IDLE_MS, live = false))
    }
}
