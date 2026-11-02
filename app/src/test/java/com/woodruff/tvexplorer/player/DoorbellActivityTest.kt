package com.woodruff.tvexplorer.player

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nest.Doorbell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The doorbell view closes by itself 2 minutes after the last key press over the slideshow, the
 * screensaver or a Woodruff screen, never over a film; and it isn't left behind when it's left.
 * (No cameras are connected here, so it shows its words, not a live picture.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DoorbellActivityTest {
    private val ring = Doorbell.Ring(1, "enterprises/p/devices/front-door", System.currentTimeMillis(), Doorbell.Host.SLIDESHOW)

    private fun open(origin: DoorbellActivity.Origin?): org.robolectric.android.controller.ActivityController<DoorbellActivity> {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent: Intent = if (origin == null) DoorbellActivity.intent(app, ring.camera) else DoorbellActivity.intent(app, ring, origin, "Home")
        return Robolectric.buildActivity(DoorbellActivity::class.java, intent).setup()
    }

    private fun wait(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    @Test fun overTheSlideshowItClosesByItselfAfterTwoMinutes() {
        val c = open(DoorbellActivity.Origin.SLIDESHOW)
        wait(Duration.ofSeconds(119))
        assertFalse(c.get().isFinishing)
        wait(Duration.ofSeconds(2))
        assertTrue(c.get().isFinishing)
    }

    @Test fun anyKeyStartsTheTwoMinutesAgain() {
        val c = open(DoorbellActivity.Origin.SCREENSAVER)
        wait(Duration.ofSeconds(90))
        c.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        c.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
        wait(Duration.ofSeconds(90))
        assertFalse(c.get().isFinishing)
        wait(Duration.ofSeconds(31))
        assertTrue(c.get().isFinishing)
    }

    @Test fun overAWoodruffScreenItClosesByItselfToo() {
        val c = open(DoorbellActivity.Origin.SCREEN)
        wait(Duration.ofSeconds(121))
        assertTrue(c.get().isFinishing)
    }

    @Test fun overAFilmItStaysUntilBack() {
        val c = open(null)
        wait(Duration.ofMinutes(10))
        assertFalse(c.get().isFinishing)
        // Left for a moment (the TV's screensaver over it): the film's camera is still there after.
        c.pause().stop()
        assertFalse(c.get().isFinishing)
    }

    @Test fun leftForSomethingElseItIsntLeftBehind() {
        val c = open(DoorbellActivity.Origin.SLIDESHOW)
        c.pause().stop()
        wait(Duration.ofSeconds(4))
        assertTrue(c.get().isFinishing)
    }

    @Test fun startedBehindTheScreensaverItWaitsForItToGo() {
        // Android may start it behind the TV's screensaver and stop it until the screensaver has gone.
        val c = open(DoorbellActivity.Origin.SCREENSAVER)
        c.pause().stop()
        wait(Duration.ofMillis(500))
        c.start().resume()
        wait(Duration.ofSeconds(10))
        assertFalse(c.get().isFinishing)
    }
}
