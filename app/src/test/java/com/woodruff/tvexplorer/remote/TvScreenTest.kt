package com.woodruff.tvexplorer.remote

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.ui.motion.DialogWindows
import com.woodruff.tvexplorer.ui.motion.dpadGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.abs

/**
 * The phone's remote presses keys where the TV's remote would (in a pop-up when one is up), with real
 * times and a held arrow's repeats; its TV Screen shows the pop-ups over the dimmed screen, at the
 * width asked, and lets go of what it copies into once nobody's watching.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvScreenTest {
    /** Stands in for a Woodruff screen. */
    class Screen : ComponentActivity() {
        val keys = mutableListOf<KeyEvent>()
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            keys += KeyEvent(event)
            return super.dispatchKeyEvent(event)
        }
    }

    private val attached = mutableListOf<Screen>()

    @After fun detachAll() = attached.forEach(TvScreen::detach)

    private fun open(): Screen = Robolectric.buildActivity(Screen::class.java).setup().get()

    private fun attach(s: Screen) { TvScreen.attach(s); attached += s }

    private fun settle() = repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    @Test fun thePhonesKeysGoToThePopUpWithTheFocus() {
        val screen = open()
        val underneath = mutableListOf<Int>()
        val inPage = mutableListOf<Int>()
        var closed = false
        screen.setContent {
            Box(Modifier.fillMaxSize().onKeyEvent { if (it.type == KeyEventType.KeyDown) underneath += it.nativeKeyEvent.keyCode; false })
            Dialog(onDismissRequest = { closed = true }) {
                val focus = remember { FocusRequester() }
                Box(
                    Modifier.size(200.dp).dpadGate().focusRequester(focus).focusable()
                        .onKeyEvent { if (it.type == KeyEventType.KeyDown) inPage += it.nativeKeyEvent.keyCode; false },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        settle()
        attach(screen)

        assertTrue(TvScreen.press("down"))
        settle()
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), inPage)
        assertTrue(underneath.isEmpty())
        assertTrue(screen.keys.isEmpty())

        // Back closes the pop-up, as the TV's remote does (not the screen under it).
        assertTrue(TvScreen.press("back"))
        settle()
        assertTrue(closed)
        assertTrue(screen.keys.isEmpty())
    }

    @Test fun withNoPopUpTheScreenGetsThem() {
        val screen = open()
        attach(screen)
        assertTrue(TvScreen.press("menu"))
        settle()
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MENU), screen.keys.map { it.action to it.keyCode })
        // With a real time, as the TV's own keys have: Settings → Smoothness counts the moves after it.
        assertTrue(screen.keys.all { it.eventTime > 0 && it.downTime > 0 })
    }

    @Test fun aScreenOpenedOverAnotherTakesTheKeysAndGivesThemBackWhenItGoes() {
        val under = open()
        under.setContent { Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp).dpadGate()) } }
        settle()
        attach(under)
        // A screen opened over it (the doorbell's camera over the film, say): its keys, not the pop-up's under it.
        val over = open()
        attach(over)
        TvScreen.press("ok")
        settle()
        assertEquals(2, over.keys.size)
        // It closes without the one under it starting again (it never stopped): the keys go back there.
        TvScreen.detach(over)
        assertTrue(TvScreen.onScreen)
        val before = over.keys.size
        TvScreen.press("ok")
        settle()
        assertEquals(before, over.keys.size)
        TvScreen.detach(under)
        assertFalse(TvScreen.onScreen)
        assertFalse(TvScreen.press("ok"))
    }

    @Test fun aHeldArrowRepeatsAsTheRemotesDo() {
        val code = KeyEvent.KEYCODE_DPAD_RIGHT
        val (first, firstUp) = TvScreen.events(code, 0, 10_000)
        assertEquals(0, first.repeatCount)
        assertEquals(10_000L, first.downTime)
        assertEquals(KeyEvent.ACTION_UP, firstUp.action)
        // The repeats carry on the hold: the same down time, counting up, each at its own time.
        val (second, _) = TvScreen.events(code, 1, 10_140)
        val (third, _) = TvScreen.events(code, 2, 10_280)
        assertEquals(listOf(10_000L, 10_000L), listOf(second.downTime, third.downTime))
        assertEquals(listOf(1, 2), listOf(second.repeatCount, third.repeatCount))
        assertEquals(10_280L, third.eventTime)
        // A new press, another button, or a repeat long after: a hold of its own.
        assertEquals(10_400L, TvScreen.events(code, 0, 10_400).first.downTime)
        assertEquals(10_500L, TvScreen.events(KeyEvent.KEYCODE_DPAD_LEFT, 1, 10_500).first.downTime)
        assertEquals(20_000L, TvScreen.events(KeyEvent.KEYCODE_DPAD_LEFT, 5, 20_000).first.downTime)
    }

    @Test fun thePhoneOnlyPressesTheButtonsItHas() {
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, TvScreen.keyCode("ok"))
        assertEquals(KeyEvent.KEYCODE_MENU, TvScreen.keyCode("menu"))
        assertEquals(null, TvScreen.keyCode("power"))
        assertFalse(TvScreen.press("power"))
    }

    @Test fun thePictureShowsThePopUpOverTheDimmedScreen() {
        val screen = open()
        screen.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFFD03030)))
            Dialog(onDismissRequest = {}) { Box(Modifier.size(320.dp, 180.dp).dpadGate().background(Color(0xFF2050E0))) }
        }
        settle()
        attach(screen)
        val window = DialogWindows.of(screen).single()
        val dims = window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        val dim = if (dims) window.attributes.dimAmount else 0f

        val jpeg = offMain { TvScreen.frame() }
        assertNotNull(jpeg)
        val picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)
        assertEquals(960, picture.width)
        assertEquals(540, picture.height)
        // The pop-up drawn where its window sits on the screen (a TV's window manager centres it; the
        // JVM's leaves it at the corner), at the picture's scale (half of the 1920 px screen)...
        val decor = window.peekDecorView()
        val at = IntArray(2).also { decor.getLocationOnScreen(it) }
        assertNear(0xFF2050E0.toInt(), picture.getPixel((at[0] + decor.width / 2) / 2, (at[1] + decor.height / 2) / 2))
        // ...and the screen around it dimmed as the TV dims what's under a pop-up.
        fun dimmed(c: Int) = (c * (1 - dim)).toInt()
        assertTrue(dims)
        assertNear(AndroidColor.rgb(dimmed(0xD0), dimmed(0x30), dimmed(0x30)), picture.getPixel(picture.width - 40, picture.height - 40))
        assertTrue(TvScreen.holdsBitmaps)

        // The hub's thumbnail asks for a small one.
        val small = offMain { TvScreen.frame(240) }!!.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        assertEquals(240, small.width)
        assertEquals(135, small.height)

        // Off the screen: no picture.
        TvScreen.detach(screen)
        assertEquals(null, offMain { TvScreen.frame() })
    }

    @Test fun whatPicturesAreCopiedIntoIsLetGo() {
        val screen = open()
        screen.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF203040))) }
        settle()
        attach(screen)
        assertNotNull(offMain { TvScreen.frame() })
        assertTrue(TvScreen.holdsBitmaps)
        TvScreen.release()
        val until = System.currentTimeMillis() + 5_000
        while (TvScreen.holdsBitmaps && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(TvScreen.holdsBitmaps)
    }

    @Test fun anotherTvsPictureIsSampledDownToTheThumbnail() {
        assertEquals(4, TvScreen.sampleFor(960, 240))
        assertEquals(2, TvScreen.sampleFor(960, 300))
        assertEquals(1, TvScreen.sampleFor(240, 240))
        assertEquals(1, TvScreen.sampleFor(200, 240))
    }

    /** Runs [block] on another thread (a server thread), keeping the main thread turning meanwhile. */
    private fun <T> offMain(block: () -> T): T? {
        var result: T? = null
        val thread = Thread { result = block() }.apply { start() }
        val until = System.currentTimeMillis() + 10_000
        while (thread.isAlive && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        thread.join(1_000)
        return result
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 24) {
        val ok = listOf(16, 8, 0).all { shift -> abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= tolerance }
        assertTrue("expected #${Integer.toHexString(expected)}, was #${Integer.toHexString(actual)}", ok)
    }
}
