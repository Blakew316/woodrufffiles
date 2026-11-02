package com.woodruff.tvexplorer.util

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A pop-up's frames (a title page, a menu) are counted under its own name, and as navigating after a
 * key pressed in it, the phone's included now that its presses carry the time they came.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameStatsPopUpTest {
    @Test fun aPopUpsFramesAreItsOwnAndNavigatingAfterItsKeys() {
        val name = "Title page ${System.nanoTime()}"
        val at = 7_000_000L
        val key = KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0)
        FrameStats.keyFinished(key, FrameStats.keyStarted(key))
        val phases = LongArray(Phase.COUNT)
        FrameStats.count(name, 30_000_000, 16_666_667, phases, startMs = at + 200)
        FrameStats.count(name, 12_000_000, 16_666_667, phases, startMs = at + 5_000)
        val frames = FrameStats.counted().screens.single { it.name == name }
        assertEquals(1, frames.navigating.frames)
        assertEquals(1, frames.navigating.slow)
        assertEquals(1, frames.idle.frames)
    }
}
