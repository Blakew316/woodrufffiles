package com.woodruff.tvexplorer.screensaver

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.player.DoorbellActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Woodruff's own slideshow ends when it's left on purpose (the remote's Home, another app), so opening
 * Woodruff again from the TV's apps shows Woodruff where it was, not its screensaver. A doorbell ring
 * opening the camera over it isn't leaving it: Back from the camera returns to the slideshow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlideshowLeaveTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ring = Doorbell.Ring(1, "enterprises/p/devices/front-door", System.currentTimeMillis(), Doorbell.Host.SLIDESHOW)
    private val opened = mutableListOf<ActivityController<*>>()

    /** Every screen goes, and what their windows queued on the main thread as they ended runs now. */
    @After fun closeEverything() {
        opened.asReversed().forEach { runCatching { it.close() } }
        opened.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun slideshow() = Robolectric.buildActivity(SlideshowActivity::class.java, SlideshowActivity.intent(app)).setup().also { opened += it }

    private fun cameraOver() = Robolectric.buildActivity(DoorbellActivity::class.java, SlideshowActivity.doorbellIntent(app, ring)).setup().also { opened += it }

    @Test fun leavingForTheTvsHomeEndsTheSlideshow() {
        val s = slideshow()
        s.userLeaving()
        assertTrue(s.get().isFinishing)
    }

    @Test fun aRingOpensTheCameraOverItWithoutLeavingIt() {
        val intent = SlideshowActivity.doorbellIntent(app, ring)
        assertEquals(DoorbellActivity::class.java.name, intent.component?.className)
        assertTrue("opened by the ring, not by someone leaving", intent.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
    }

    @Test fun leavingFromTheCameraOverItEndsTheSlideshowToo() {
        val s = slideshow()
        val camera = cameraOver()
        camera.userLeaving()
        assertTrue(s.get().isFinishing)
    }

    @Test fun backFromTheCameraReturnsToTheSlideshow() {
        val s = slideshow()
        val camera = cameraOver()
        camera.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(s.get().isFinishing)
    }
}
