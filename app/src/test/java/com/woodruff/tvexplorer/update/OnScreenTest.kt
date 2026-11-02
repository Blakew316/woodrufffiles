package com.woodruff.tvexplorer.update

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.update.OnScreen.Film
import com.woodruff.tvexplorer.update.Restore.App
import com.woodruff.tvexplorer.util.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Every Woodruff screen followed with no code of its own ([OnScreen], installed as the app starts):
 * its keys count as use, coming on screen counts as use, and the updater learns what's up and what
 * to put back. Screens here stand in for the main window, the player, the slideshow and the doorbell.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnScreenTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
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
    private fun broadcast(action: String) { app.sendBroadcast(Intent(action)); shadowOf(Looper.getMainLooper()).idle() }

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

    @Test fun aPointerMovingIsUseButNotAKeyPress() {
        val player = open<FakePlayer>().get()
        now += 30 * minute
        AppVisibility.lastInput = 1
        val hover = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_HOVER_MOVE, 100f, 100f, 0)
        player.window.callback.dispatchGenericMotionEvent(hover)
        hover.recycle()
        assertEquals(0, Presence.sinceUseMs())
        assertEquals(1, AppVisibility.lastInput)
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

    @Test fun theUpdaterLearnsWhatsUpAndWhatToPutBack() {
        val main = open<FakeMain>()
        assertEquals(OnScreen.Moment(App(Screen.SETTINGS), Film.NONE, screenOn = true), OnScreen.moment(app))

        // A film over it: the film, with the main window under it for Back.
        val player = open<FakePlayer>()
        main.pause().stop()
        player.get().playing = true
        val playing = OnScreen.moment(app)
        assertEquals(Film.PLAYING, playing.film)
        assertEquals(App(Screen.SETTINGS), (playing.route as Restore.Film).under)
        player.get().playing = false
        assertEquals(Film.PAUSED, OnScreen.moment(app).film)

        // Under the TV's screensaver (Android 11+ stops the player): still on screen, and paused,
        // whatever it was doing.
        player.get().playing = true
        broadcast(Intent.ACTION_DREAMING_STARTED)
        player.pause().stop()
        val covered = OnScreen.moment(app)
        assertEquals(Film.PAUSED, covered.film)
        assertEquals("drive:a", (covered.route as Restore.Film).keys.single())
    }

    @Test fun theDoorbellsCameraOverTheSlideshowPutsBackTheSlideshow() {
        val main = open<FakeMain>()
        val slideshow = open<FakeSlideshow>()
        main.pause().stop()
        open<FakeDoorbell>()
        slideshow.pause().stop()
        assertEquals(Restore.Slideshow(memories = false, under = App(Screen.SETTINGS)), OnScreen.moment(app).route)
    }

    @Test fun aWoodruffLeftForAnotherAppDoesntOpenAgain() {
        val main = open<FakeMain>()
        main.pause().stop()
        assertEquals(OnScreen.Moment(null, Film.NONE, screenOn = true), OnScreen.moment(app))
    }

    @Test fun theScreenOffIsSaid() {
        open<FakeMain>()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        assertFalse(OnScreen.moment(app).screenOn)
    }

    @Test fun aScreenThatCantSayWhatItShowsBringsBackTheMainWindow() {
        val main = open<FakeMain>()
        main.get().route = null
        assertEquals(App(Screen.HOME), OnScreen.moment(app).route)
        // ...and nothing at all when only the doorbell's camera is up (from the TV's screensaver).
        main.pause().stop().destroy()
        open<FakeDoorbell>()
        assertNull(OnScreen.moment(app).route)
    }

    @Test fun comingBackFromAFilmIsStillUsingWoodruffNotOpeningIt() {
        val main = open<FakeMain>()
        // Opened: nothing of Woodruff's was up before it (an update may be offered).
        assertFalse(OnScreen.backFromInside(main.get()))
        val player = open<FakePlayer>()
        main.pause().stop()
        // Back at the end of the film: the main window starts again while the player is still up.
        main.restart()
        assertTrue(OnScreen.backFromInside(main.get()))
        player.pause().stop().destroy()
        main.resume()
    }

    @Test fun someonePressingInstallComesBackToTheirScreen() {
        open<FakeMain>()
        assertEquals(App(Screen.SETTINGS), OnScreen.routeNow(app))
    }
}

/** Stands in for the main window. */
class FakeMain : Activity(), Restore.Restorable {
    var route: Restore.Route? = App(Screen.SETTINGS)
    override fun restoreRoute(): Restore.Route? = route
}

/** Stands in for the player: says it plays a film. */
class FakePlayer : Activity(), Restore.Restorable {
    var playing = false
    val keys = mutableListOf<Int>()
    override fun restoreRoute(): Restore.Route? = Restore.film(listOf("drive:a"), listOf("A"), 0, 61_000, playing)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { keys += keyCode; return true }
}

/** Stands in for the slideshow. */
class FakeSlideshow : Activity(), Restore.Restorable {
    override fun restoreRoute(): Restore.Route = Restore.Slideshow(memories = false)
}

/** Stands in for the doorbell's camera, which can't say what it shows. */
class FakeDoorbell : Activity()
