package com.woodruff.tvexplorer.update

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.MainActivity
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.util.EarlyCrashes
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
import java.io.File
import java.time.Duration

/**
 * The main screen after this version closed while starting twice in a row: the recovery screen
 * instead of the app, with nothing else of Woodruff's started from it, and Try opening again
 * bringing the app back with one close left before the recovery screen returns.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RecoveryEntryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val looks = mutableListOf<Boolean>()

    @Before fun twoClosesInARow() {
        clean()
        // No network in tests: the check finds nothing (set first: Woodruff's own start may look too).
        Updater.listReleases = { emptyList() }
        repeat(2) {
            EarlyCrashes.opened(app)
            EarlyCrashes.crashed(app, null)
            EarlyCrashes.resetForTest()
        }
        EarlyCrashes.lastExit = { _, _, _ -> null }
        RecoveryView.lookForFix = { _, manual -> looks += manual }
        RecoveryView.load = { it.run() }
        // Somewhere for Android to say whether Woodruff may use the TV's storage (it may not, here).
        org.robolectric.shadows.ShadowEnvironment.addExternalDir("storage")
    }

    @After fun clean() {
        EarlyCrashes.resetForTest()
        File(app.filesDir, "early_crashes.txt").delete()
        File(app.filesDir, "early_crash_report.txt").delete()
        RecoveryView.resetForTest()
        Updater.resetForTest()
        AppVisibility.foreground = false
    }

    private fun content(a: MainActivity): View = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    @Test fun theMainScreenIsTheRecoveryScreenAndStartsNothingElse() {
        assertTrue(EarlyCrashes.needsRecovery(app))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val a = controller.get()
        assertTrue(content(a) is RecoveryView)
        // No phone connection, no screens' state, no slideshow: only the look for a fix.
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
        // (AndroidX keeps one of its own there for saved state.)
        val models = a.viewModelStore.keys().toString()
        assertFalse(models, models.contains("woodruff"))
        assertEquals(listOf(false), looks)
        // An update from here opens Woodruff at Home, as the new version.
        assertEquals(Restore.App(Screen.HOME), a.restoreRoute())
        // Its staying up doesn't count as Woodruff starting fine: the count stands.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarlyCrashes.EARLY_MS + 5_000))
        assertEquals(2, EarlyCrashes.count(app))
        // The remote's Search key has nothing to open here.
        assertTrue(a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH)))
        controller.pause().stop().destroy()
    }

    @Test fun noticesAndTheDoorbellStillReachTheTvWhileItsUp() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        // Woodruff's own banner isn't there, so Woodruff doesn't count as in front for the rest of the
        // app; the updater still knows its main screen is (Android's install prompt opens over it).
        assertFalse(AppVisibility.foreground)
        assertTrue(RecoveryView.inFront)
        // A message from a phone: shown as it would be over any other app (here, without the pop-up
        // permission, as Android's notification), not handed to a banner nobody sees.
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        try {
            Notices.post(app, NoticeSource.PHONE, "Dinner's ready", "Come down when you can")
            assertEquals(1, shadowOf(nm).allNotifications.size)
        } finally {
            Notices.clear(app)
            Notices.dismissLatest()
        }
        controller.pause().stop()
        assertFalse(RecoveryView.inFront)
        controller.destroy()
    }

    @Test fun tryOpeningAgainOpensTheAppWithOneCloseLeft() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val recovery = content(controller.get()) as RecoveryView
        assertTrue(recovery.first.isFocused)
        controller.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        controller.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        controller.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        controller.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, EarlyCrashes.count(app))
        // The main screen started over, as the app this time: the same window made again, no second one.
        val again = controller.get()
        assertFalse(content(again) is RecoveryView)
        assertNull(shadowOf(app).nextStartedActivity)
        // And this start is watched: one more close while starting brings the recovery screen back.
        EarlyCrashes.crashed(app, null)
        assertTrue(EarlyCrashes.needsRecovery(app))
        controller.pause().stop().destroy()
    }
}
