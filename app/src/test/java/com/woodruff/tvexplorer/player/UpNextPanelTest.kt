package com.woodruff.tvexplorer.player

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Up next at an episode's end: its count to the next one, or, after a long while with nobody at the remote or the phone, a question with no count. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class UpNextPanelTest {
    @Test fun itCountsDownOrAsksStillWatchingWithNoCount() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity).also(activity::setContentView)
        val panel = UpNextPanel(activity, root)
        val next = EpisodeLabel.of("Backyard Science S02E05 The Volcano")

        panel.show(next, seconds = 10)
        assertEquals("UP NEXT", panel.heading)
        assertTrue(panel.counting)
        assertTrue(panel.shown)

        panel.hide(animate = false)
        panel.show(next, seconds = 10, asking = true)
        assertEquals("STILL WATCHING?", panel.heading)
        assertFalse("no count while it asks", panel.counting)
        assertFalse("Play Now chosen, as with the count", panel.doneChosen)

        // The next episode's Up next counts again.
        panel.hide(animate = false)
        panel.show(next, seconds = 10)
        assertEquals("UP NEXT", panel.heading)
        assertTrue(panel.counting)
    }
}
