package com.woodruff.tvexplorer

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.update.RecoveryView
import com.woodruff.tvexplorer.update.Restore
import com.woodruff.tvexplorer.update.Updater
import com.woodruff.tvexplorer.util.EarlyCrashes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * How the main window's first lines agree with each other: the self-repair decision comes first
 * ([EarlyCrashes], the recovery screen), and a second main window the TV's app row started on top
 * of Woodruff's own ([MainIntents.isRepeatLaunch]) closes straight after `super.onCreate`, before
 * the recovery screen could be shown. Such a window isn't Woodruff starting: it never counts as a
 * start (a close soon after it isn't a close while starting), and never stacks a second recovery
 * screen. And a screen put back after an update opens the main window as every other path does
 * ([MainIntents.open]), so the TV's app row finds that task afterwards.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class MainWindowOpeningTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** What the TV's app row starts (Android TV / Google TV home). */
    private fun appRow() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setComponent(ComponentName(app, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    @Before fun freshTv() {
        // A close counted here makes Woodruff look for a fix: never over the network in a test.
        clean()
        Updater.listReleases = { emptyList() }
        EarlyCrashes.lastExit = { _, _, _ -> null }
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

    private fun content(a: MainActivity): ViewGroup = a.findViewById(android.R.id.content)

    @Test fun aSecondWindowFromTheAppRowIsntWoodruffStartingSoItCountsForNothing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, appRow())
        shadowOf(controller.get()).setIsTaskRoot(false)
        controller.create()
        assertTrue(controller.get().isFinishing)
        // Woodruff had been up all along: a close now isn't one while starting, as the repeat window
        // started no 45 s of its own.
        EarlyCrashes.crashed(app, null)
        assertEquals(0, EarlyCrashes.count(app))
        controller.destroy()
    }

    @Test fun theWindowThatReallyOpensWoodruffStillCounts() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, appRow()).create()
        assertFalse(controller.get().isFinishing)
        EarlyCrashes.crashed(app, null)
        assertEquals(1, EarlyCrashes.count(app))
        controller.destroy()
        // The window's recomposer (useAppMotion) ends on its way out with a step queued on Compose's
        // main-thread dispatcher: run it now. Left queued, the test's end would drop it, and that
        // dispatcher, shared by every later test in this JVM, would never run anything again.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun overTheRecoveryScreenASecondWindowClosesWithoutStackingAnother() {
        repeat(2) {
            EarlyCrashes.opened(app)
            EarlyCrashes.crashed(app, null)
            EarlyCrashes.resetForTest()
        }
        EarlyCrashes.lastExit = { _, _, _ -> null }
        assertTrue(EarlyCrashes.needsRecovery(app))
        val controller = Robolectric.buildActivity(MainActivity::class.java, appRow())
        shadowOf(controller.get()).setIsTaskRoot(false)
        controller.create()
        val a = controller.get()
        assertTrue(a.isFinishing)
        // The recovery screen already up underneath stays the only one; nothing was set up here.
        assertEquals(0, content(a).childCount)
        assertEquals(2, EarlyCrashes.count(app))
        controller.destroy()
    }

    @Test fun aScreenPutBackAfterAnUpdateOpensTheMainWindowLikeEveryOtherPath() {
        val main = Restore.mainIntent(app, Restore.App(Screen.SETTINGS))
        // The task it starts answers to the app row afterwards...
        assertTrue(main.filterEquals(appRow()))
        // ...an existing main window takes the route instead of a second one stacking on top...
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, main.flags and flags)
        assertEquals(Restore.App(Screen.SETTINGS), Restore.decode(main.getStringExtra(Restore.EXTRA_ROUTE)))
        // ...and it's there on purpose, so never closed as a repeat launch.
        assertFalse(MainIntents.isRepeatLaunch(isTaskRoot = false, intent = main))

        // Under a film: the main window still starts the task, the player goes on top in the same task.
        val (bottom, player) = Restore.intents(app, Restore.Film(listOf("drive:a"), listOf("A"), 0, 61_000, under = Restore.App(Screen.HOME)))
        assertTrue(bottom.filterEquals(appRow()))
        assertEquals(0, player.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
