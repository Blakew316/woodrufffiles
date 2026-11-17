package com.woodruff.tvexplorer.screensaver

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Woodruff's own slideshow keeps the screen on for an hour after Woodruff was last used, so it doesn't go dark under
 * someone watching, then lets the TV's own screensaver and sleep timers run. After the player's sleep timer it never
 * keeps the screen on: the TV sleeps by its own timers.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlideshowScreenOnTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val opened = mutableListOf<ActivityController<*>>()

    @After fun closeEverything() {
        opened.asReversed().forEach { runCatching { it.close() } }
        opened.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun slideshow(asleep: Boolean = false) =
        Robolectric.buildActivity(SlideshowActivity::class.java, SlideshowActivity.intent(app, asleep = asleep)).setup().also { opened += it }

    private fun keepsScreenOn(a: Activity) = a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test fun anHourWithTheScreenOnThenTheTvsOwnTimers() {
        val s = slideshow()
        assertTrue(keepsScreenOn(s.get()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(59))
        assertTrue("still on for someone watching", keepsScreenOn(s.get()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertFalse("the TV may sleep", keepsScreenOn(s.get()))
        assertNotNull(s.get().restoreRoute())
    }

    @Test fun afterTheSleepTimerTheScreenIsNeverKeptOn() {
        val s = slideshow(asleep = true)
        assertFalse(keepsScreenOn(s.get()))
        // An update meanwhile puts back the screen under it, not a slideshow that would keep the screen on.
        assertNull(s.get().restoreRoute())
    }

    @Test fun theHourCountsFromTheLastUse() {
        assertEquals(SLIDESHOW_SCREEN_ON_MS, slideshowScreenOnFor(0, asleep = false))
        assertEquals(15 * 60_000L, slideshowScreenOnFor(45 * 60_000L, asleep = false))
        assertEquals(0L, slideshowScreenOnFor(3 * SLIDESHOW_SCREEN_ON_MS, asleep = false))
        assertEquals(0L, slideshowScreenOnFor(0, asleep = true))
    }
}
