package com.woodruff.tvexplorer.ui.motion

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.icons.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * OK on a card: a press clicks when let go, holding it opens the card's menu, and letting go of that hold runs
 * nothing in the menu, which Android hands the rest of the hold (its menu is a window of its own).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OkLongPressTest {
    private fun settle() = repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    private fun ok(action: Int, repeat: Int, downAt: Long, flags: Int = 0) =
        androidx.compose.ui.input.key.KeyEvent(KeyEvent(downAt, downAt + repeat * 50L, action, KeyEvent.KEYCODE_DPAD_CENTER, repeat, 0, 0, 0, flags))

    @Test fun aPressClicksAHoldIsTheLongClickAndOnlyAPressBegunHereClicks() {
        var clicks = 0
        var longClicks = 0
        val card = OkPress()
        val press = { e: androidx.compose.ui.input.key.KeyEvent -> okKey(e, card, { clicks++ }, { longClicks++ }, null) }

        // A press: one click, on release.
        press(ok(KeyEvent.ACTION_DOWN, 0, 1_000))
        assertEquals(0, clicks)
        press(ok(KeyEvent.ACTION_UP, 0, 1_000))
        assertEquals(1, clicks)
        assertEquals(0, longClicks)

        // Held: the long click at its first repeat, and no click when let go.
        press(ok(KeyEvent.ACTION_DOWN, 0, 2_000))
        press(ok(KeyEvent.ACTION_DOWN, 1, 2_000))
        press(ok(KeyEvent.ACTION_DOWN, 2, 2_000))
        press(ok(KeyEvent.ACTION_UP, 0, 2_000))
        assertEquals(1, clicks)
        assertEquals(1, longClicks)

        // The row the menu opened with the focus gets the rest of that hold and its release: nothing.
        var rowClicks = 0
        var rowLongClicks = 0
        val row = OkPress()
        val onRow = { e: androidx.compose.ui.input.key.KeyEvent -> okKey(e, row, { rowClicks++ }, { rowLongClicks++ }, null) }
        onRow(ok(KeyEvent.ACTION_DOWN, 3, 3_000))
        onRow(ok(KeyEvent.ACTION_DOWN, 4, 3_000))
        onRow(ok(KeyEvent.ACTION_UP, 0, 3_000))
        assertEquals(0, rowClicks)
        assertEquals(0, rowLongClicks)
        // Its own press clicks it.
        onRow(ok(KeyEvent.ACTION_DOWN, 0, 4_000))
        onRow(ok(KeyEvent.ACTION_UP, 0, 4_000))
        assertEquals(1, rowClicks)

        // A release Android cancelled (the window lost the focus while OK was down) clicks nothing.
        press(ok(KeyEvent.ACTION_DOWN, 0, 5_000))
        press(ok(KeyEvent.ACTION_UP, 0, 5_000, KeyEvent.FLAG_CANCELED))
        assertEquals(1, clicks)
        // And the next press is a press again.
        press(ok(KeyEvent.ACTION_DOWN, 0, 6_000))
        press(ok(KeyEvent.ACTION_UP, 0, 6_000))
        assertEquals(2, clicks)
    }

    @Test fun holdingOkOnACardOpensItsMenuAndLettingGoRunsNothingThere() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var clicks = 0
        var menu by mutableStateOf(false)
        var played = 0
        activity.setContent {
            val focus = remember { FocusRequester() }
            TvCard(
                onClick = { clicks++ }, shape = RectangleShape, onLongClick = { menu = true },
                modifier = Modifier.size(120.dp).focusRequester(focus),
            ) {}
            LaunchedEffect(Unit) { focus.requestFocus() }
            if (menu) ActionMenu(
                "Midnight Harbor", null,
                listOf(MenuAction("Play", Sym.PlayArrow) { played++ }, MenuAction("Details", Sym.Info) {}),
                onDismiss = { menu = false },
            )
        }
        settle()

        // Held on the card: its first repeat opens the menu.
        val t = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        activity.dispatchKeyEvent(KeyEvent(t, t + 500, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        settle()
        assertTrue(menu)
        val window = DialogWindows.of(activity).single()

        // The menu's window has the focus now: Android hands it the rest of the hold and its release. Play
        // (its first row, focused) doesn't run, and the menu stays.
        window.callback.dispatchKeyEvent(KeyEvent(t, t + 550, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2))
        window.callback.dispatchKeyEvent(KeyEvent(t, t + 600, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 3))
        window.callback.dispatchKeyEvent(KeyEvent(t, t + 650, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        settle()
        assertEquals(0, played)
        assertTrue(menu)
        assertEquals(0, clicks)

        // A press in the menu plays, and closes it.
        val p = SystemClock.uptimeMillis()
        window.callback.dispatchKeyEvent(KeyEvent(p, p, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        window.callback.dispatchKeyEvent(KeyEvent(p, p + 80, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        settle()
        assertEquals(1, played)
        assertFalse(menu)

        // Back on the card, a press clicks it, once.
        val c = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(c, c, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        activity.dispatchKeyEvent(KeyEvent(c, c + 80, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        settle()
        assertEquals(1, clicks)
        assertFalse(menu)
    }
}
