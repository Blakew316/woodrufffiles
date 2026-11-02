package com.woodruff.tvexplorer.nest

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Two doorbells ringing within the moment a doorbell view takes to come up open one view, not two:
 * while a screen's view is on its way, the next ring goes to it. What marks a view as on its way,
 * and what clears that (the view coming up, the screen giving the ring back, the 10 s running out).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DoorbellViewOpeningTest {
    private var t = 1_900_000_000_000L

    private fun ring(id: Long, host: Doorbell.Host) = Doorbell.Ring(id, "enterprises/p/devices/door-$id", t, host)

    @After fun clock() {
        Doorbell.clock = System::currentTimeMillis
    }

    @Test fun theSlideshowTakingARingPutsAViewOnItsWayUntilItsUp() {
        Doorbell.clock = { t }
        assertTrue(Doorbell.claim(ring(9_001, Doorbell.Host.SLIDESHOW), Doorbell.Host.SLIDESHOW))
        assertTrue(Doorbell.viewOpeningNow())
        // The view comes up and listens: it takes rings itself from then on.
        val view = Doorbell.watchFor(Doorbell.Host.VIEW) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Doorbell.viewOpeningNow())
        view.stop()
    }

    @Test fun theScreensaverTakingARingDoesTooAndOneThatNeverComesStopsCounting() {
        Doorbell.clock = { t }
        assertTrue(Doorbell.claim(ring(9_002, Doorbell.Host.SCREENSAVER), Doorbell.Host.SCREENSAVER))
        assertTrue(Doorbell.viewOpeningNow())
        t += VIEW_OPENING_MS
        assertFalse(Doorbell.viewOpeningNow())
    }

    @Test fun aScreenThatCouldntOpenItsViewGivesTheRingBack() {
        Doorbell.clock = { t }
        val r = ring(9_003, Doorbell.Host.SLIDESHOW)
        assertTrue(Doorbell.claim(r, Doorbell.Host.SLIDESHOW))
        Doorbell.unclaim(r)
        assertFalse(Doorbell.viewOpeningNow())
    }

    @Test fun woodruffsScreensSayThemselvesWhenTheyStartTheView() {
        // In place in the Command Center nothing is on its way; AppRoot marks it only when it starts the view.
        Doorbell.clock = { t }
        assertTrue(Doorbell.claim(ring(9_004, Doorbell.Host.APP), Doorbell.Host.APP))
        assertFalse(Doorbell.viewOpeningNow())
        Doorbell.viewOpening()
        assertTrue(Doorbell.viewOpeningNow())
        Doorbell.unclaim(ring(9_004, Doorbell.Host.APP))
        assertFalse(Doorbell.viewOpeningNow())
    }
}
