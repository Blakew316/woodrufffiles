package com.woodruff.tvexplorer.server

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.MainActivity
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.player.DoorbellActivity
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.update.RecoveryView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * A video started from the phone while Woodruff isn't on screen and may not pop up over other apps waits,
 * and starts the moment Woodruff is opened ([PendingPlay]). Since the TV's app row brings Woodruff back as
 * it was (MainIntents), what comes back may be a paused film or the photo viewer rather than the main
 * window: the video starts over whichever it is, in its task, once. The doorbell's camera popping up by
 * itself, or the recovery screen, isn't Woodruff being opened. And with any of Woodruff's own screens up,
 * a start goes straight to the screen: nothing waits for the main window.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PendingPlayTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val routes = CompanionRoutes(app)

    @Before fun anotherAppInFront() {
        clean()
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse("a test before this one left one of Woodruff's screens up (started, never stopped)", TvScreen.onScreen)
    }

    @After fun clean() {
        PendingPlay.clear()
        AppVisibility.foreground = false
        RecoveryView.inFront = false
        Notices.clear(app)
        Notices.dismissLatest()
        app.getSystemService(NotificationManager::class.java).cancelAll()
        shadowOf(app).clearNextStartedActivities()
    }

    /** The phone starts Midnight Harbor here, 45 minutes in (/api/watch, as Play on the phone does). */
    private fun startFromThePhone(): JSONObject {
        val body = JSONObject().put("key", "drive:m1").put("title", "Midnight Harbor (2024).mkv").put("position", 2_710_000)
        val r = routes.answerFor(phoneRequest("/api/watch", NanoHTTPD.Method.POST, body = body))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        return JSONObject(r.bodyText())
    }

    private fun assertIsTheFilmFromThePhone(started: Intent?) {
        assertEquals(PlayerActivity::class.java.name, started?.component?.className)
        assertEquals(listOf("drive:m1"), started!!.getStringArrayListExtra("keys"))
        assertEquals(2_710_000, started.getLongExtra("position", -1))
    }

    @Test fun withOneOfWoodruffsScreensUpAStartGoesStraightToTheScreen() {
        // A film playing in Woodruff's player: the main window is behind it, but Woodruff is on screen, and an app
        // on screen may open its own screens. (Only the main window used to count: the phone's film waited for this
        // one to end, and the phone said "Ready on Living Room… open Woodruff Family there" over Woodruff itself.)
        val playing = Robolectric.buildActivity(PlayerActivity::class.java).get()
        TvScreen.attach(playing)
        try {
            assertTrue(PendingPlay.onScreenNow())
            assertTrue(startFromThePhone().getBoolean("started"))
            assertFalse(PendingPlay.holding)
            assertIsTheFilmFromThePhone(shadowOf(app).nextStartedActivity)
            // Over the recovery screen nothing of the app starts: it waits instead.
            RecoveryView.inFront = true
            assertFalse(PendingPlay.onScreenNow())
            assertFalse(startFromThePhone().getBoolean("started"))
            assertTrue(PendingPlay.holding)
        } finally {
            TvScreen.detach(playing)
        }
        // Allowed to pop up over other apps: on screen at once from anywhere, as before.
        RecoveryView.inFront = false
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(startFromThePhone().getBoolean("started"))
        assertFalse(PendingPlay.holding)
    }

    @Test fun aWaitingVideoStartsOverWhicheverScreenOpeningWoodruffBringsBack() {
        val r = startFromThePhone()
        assertFalse(r.getBoolean("started"))
        assertEquals("It’s waiting on the TV: open Woodruff Family there.", r.getString("message"))
        assertTrue(PendingPlay.holding)
        assertNull("nothing started meanwhile", shadowOf(app).nextStartedActivity)

        // The doorbell's camera popping up by itself on a ring isn't Woodruff being opened: it still waits.
        val bell = Robolectric.buildActivity(DoorbellActivity::class.java).get()
        PendingPlay.cameUp(bell)
        assertNull(shadowOf(bell).nextStartedActivity)
        assertTrue(PendingPlay.holding)

        // Opened from the TV's app row, Woodruff comes back as it was: here a paused film. The phone's film starts
        // over it, in its task (not a task of its own), so Back returns to the paused one.
        val paused = Robolectric.buildActivity(PlayerActivity::class.java).get()
        PendingPlay.cameUp(paused)
        val started = shadowOf(paused).nextStartedActivity
        assertIsTheFilmFromThePhone(started)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertFalse(PendingPlay.holding)

        // Once: the main window coming back afterwards starts nothing more.
        val main = Robolectric.buildActivity(MainActivity::class.java).get()
        PendingPlay.cameUp(main)
        assertNull(shadowOf(main).nextStartedActivity)
    }

    @Test fun theMainWindowOrThePhotosOpeningWoodruffStartIt_neverTheRecoveryScreenOrAWindowOnItsWayOut() {
        startFromThePhone()
        // The recovery screen up (Woodruff closed twice while starting): nothing of the app starts over it.
        RecoveryView.inFront = true
        val recovering = Robolectric.buildActivity(MainActivity::class.java).get()
        PendingPlay.cameUp(recovering)
        assertNull(shadowOf(recovering).nextStartedActivity)
        RecoveryView.inFront = false
        // The app row's second main window, closing at once: not what's on screen.
        val repeat = Robolectric.buildActivity(MainActivity::class.java).get().apply { finish() }
        PendingPlay.cameUp(repeat)
        assertNull(shadowOf(repeat).nextStartedActivity)
        assertTrue(PendingPlay.holding)
        // The photo viewer coming back: the film starts over it.
        val photos = Robolectric.buildActivity(ImageViewerActivity::class.java).get()
        PendingPlay.cameUp(photos)
        assertIsTheFilmFromThePhone(shadowOf(photos).nextStartedActivity)

        // A notice's tap brings the main window (MainIntents.open): a film waiting starts over it.
        startFromThePhone()
        val main = Robolectric.buildActivity(MainActivity::class.java).get()
        PendingPlay.cameUp(main)
        assertIsTheFilmFromThePhone(shadowOf(main).nextStartedActivity)
    }

    @Test fun aVideoLeftWaitingOverTenMinutesNeverStarts() {
        startFromThePhone()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        assertFalse(PendingPlay.holding)
        val main = Robolectric.buildActivity(MainActivity::class.java).get()
        PendingPlay.cameUp(main)
        assertNull(shadowOf(main).nextStartedActivity)
    }

    @Test fun aRealScreenComingBackStartsIt() {
        // As Android runs it: the film from the phone waits; Woodruff's player is opened (as the app row brings back a
        // paused film) and comes to the front; the waiting film starts over it from there.
        startFromThePhone()
        val film = File(app.cacheDir, "Ironwood (2025).mp4").apply { writeBytes(ByteArray(64)) }
        val c = Robolectric.buildActivity(PlayerActivity::class.java, PlayerActivity.intent(app, listOf(film.path), listOf("Ironwood (2025)"), film.path)).setup()
        try {
            assertIsTheFilmFromThePhone(shadowOf(c.get()).nextStartedActivity)
            assertFalse(PendingPlay.holding)
        } finally {
            c.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
