package com.woodruff.tvexplorer.util

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.memory.MemoryCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Woodruff off screen for ten minutes gives its memory back to the TV's other apps (sooner if Android
 * says memory is short), and keeps it while on screen or back in time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeanMemoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var images: ImageLoader
    private val minute = 60_000L

    @Before fun anImageLoaderWithAPoster() {
        images = ImageLoader.Builder(app).memoryCache { MemoryCache.Builder(app).maxSizeBytes(4 shl 20).build() }.build()
        LeanMemory.images = images
        poster()
    }

    @After fun forget() { LeanMemory.images = null }

    private fun poster() = images.memoryCache!!.set(MemoryCache.Key("poster"), MemoryCache.Value(Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)))

    private fun cached() = images.memoryCache!!.size > 0

    private fun open(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The memory is given back in the background: waits for that (at most a few seconds). */
    private fun givenBack(): Boolean {
        val until = System.currentTimeMillis() + 5_000
        while (cached() && System.currentTimeMillis() < until) Thread.sleep(10)
        return !cached()
    }

    @Test fun tenMinutesOffScreenGivesItBack() {
        val screen = open()
        assertTrue(LeanMemory.onScreen)
        screen.pause().stop()
        assertFalse(LeanMemory.onScreen)
        pass(9 * minute)
        Thread.sleep(50)
        assertTrue(cached())
        pass(2 * minute)
        assertTrue(givenBack())
    }

    @Test fun comingBackInTimeKeepsIt() {
        val screen = open()
        screen.pause().stop()
        pass(9 * minute)
        // Back before the ten minutes are up (a quick look at another app), then a film over it.
        screen.start().resume()
        pass(30 * minute)
        Thread.sleep(50)
        assertTrue(cached())
    }

    @Test fun onScreenAndroidsWarningsAreLeftToCoil() {
        open()
        LeanMemory.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        Thread.sleep(50)
        assertTrue(cached())
    }

    @Test fun offScreenMemoryShortGivesItBackAtOnce() {
        open().pause().stop()
        LeanMemory.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue(givenBack())
    }

    @Test fun whichOfAndroidsWarningsMeanNow() {
        @Suppress("DEPRECATION")
        val now = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE, ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        now.forEach { assertTrue("level $it", LeanMemory.trimsAtOnce(it)) }
        // The screens just went: the ten minutes see to that, so a quick trip to another app costs nothing.
        assertFalse(LeanMemory.trimsAtOnce(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(LeanMemory.trimsAtOnce(0))
        assertEquals(10 * minute, LeanMemory.AFTER_MS)
    }
}
