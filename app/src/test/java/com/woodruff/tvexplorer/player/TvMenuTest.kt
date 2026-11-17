package com.woodruff.tvexplorer.player

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The player's menus with the remote: OK held down chooses once, never again in the menu that choice opens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvMenuTest {
    @Test fun okHeldDownChoosesOnceAndNothingInTheMenuItOpens() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity).also(activity::setContentView)
        val menu = TvMenu(activity, root)
        var sizesOpened = 0
        var sizeChosen = 0
        menu.show("Subtitles", listOf(
            TvMenu.Row("Size: Standard") {
                sizesOpened++
                menu.show("Subtitle size", listOf(TvMenu.Row("Standard", checked = true) {}, TvMenu.Row("Large") { sizeChosen++ }))
            },
        ))
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, 0))
        assertEquals(1, sizesOpened)
        assertTrue(menu.shown)
        // OK still held: its repeats choose nothing in the size menu (it used to flash and close).
        menu.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        for (repeat in 1..5) assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, repeat))
        assertEquals(0, sizeChosen)
        assertTrue(menu.shown)
        // Held Up / Down still move through the rows; a new press of OK chooses.
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_UP, 3))
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, 4))
        assertTrue(menu.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, 0))
        assertEquals(1, sizeChosen)
        assertEquals(1, sizesOpened)
    }
}
