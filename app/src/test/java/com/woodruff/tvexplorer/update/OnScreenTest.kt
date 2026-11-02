package com.woodruff.tvexplorer.update

import android.app.Activity
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.util.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Every Woodruff screen followed with no code of its own ([OnScreen], installed as the app starts):
 * its keys count as use, and so does coming on screen. Screens here stand in for Woodruff's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnScreenTest {
    private var now = 90_000_000L
    private val minute = 60_000L

    @Before fun fakeClock() {
        Presence.clock = { now }
        Presence.resetForTest()
    }

    @After fun realClock() {
        Presence.resetForTest()
        Presence.clock = { android.os.SystemClock.elapsedRealtime() }
    }

    private inline fun <reified A : Activity> open(): ActivityController<A> = Robolectric.buildActivity(A::class.java).setup()

    @Test fun keysInAnyWoodruffWindowCountAsUseAndStillReachIt() {
        val player = open<FakePlayer>().get()
        now += 30 * minute
        AppVisibility.lastInput = 0
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        // The way Android hands a key to the window.
        player.window.callback.dispatchKeyEvent(down)
        assertEquals(0, Presence.sinceUseMs())
        assertTrue(AppVisibility.lastInput > 0)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), player.keys)
        // Wrapped once, however often it's asked.
        OnScreen.countKeys(player.window)
        assertTrue(player.window.callback is OnScreen.KeyCounter)
        player.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT), player.keys)
    }

    @Test fun comingOnScreenCountsAsUse() {
        now += 30 * minute
        open<FakeMain>()
        assertEquals(0, Presence.sinceUseMs())
    }

    @Test fun thePhonesRemoteKeysCountAsUse() {
        val main = open<FakeMain>().get()
        TvScreen.attach(main)
        now += 30 * minute
        assertTrue(TvScreen.press("ok"))
        assertEquals(0, Presence.sinceUseMs())
        TvScreen.detach(main)
    }
}

/** Stands in for the main window. */
class FakeMain : Activity()

/** Stands in for the player. */
class FakePlayer : Activity() {
    val keys = mutableListOf<Int>()
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { keys += keyCode; return true }
}
