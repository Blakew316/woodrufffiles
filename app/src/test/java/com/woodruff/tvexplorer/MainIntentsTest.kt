package com.woodruff.tvexplorer

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coming back to Woodruff returns to where it was (browsing-motion §3.4, finding 4): MainActivity is
 * singleTop; every intent Woodruff opens it with keeps the TV app row's identity, so Android brings
 * the task back as it was instead of starting a second window; a second window started anyway closes
 * at once; and before Android 12, Back at the last level puts Woodruff in the background.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainIntentsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val main = ComponentName(app, MainActivity::class.java)

    /** What the TV's app row starts (Android TV / Google TV home). */
    private fun appRow() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(main)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    @Test
    fun mainWindowIsSingleTopSoTheAppRowNoLongerClosesTheFilm() {
        val info = app.packageManager.getActivityInfo(main, 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test
    fun woodruffsOwnIntentsKeepTheAppRowsIdentity() {
        // Android compares a task's intent with the app row's this way (Task.isSameIntentFilter):
        // action, categories, data, package and component, exactly; not extras or flags.
        assertTrue(MainIntents.open(app, "notifications").filterEquals(appRow()))
        assertTrue(MainIntents.open(app).filterEquals(appRow()))
        assertTrue(MainIntents.resume(app).filterEquals(appRow()))
        // ...and the TV's launcher resolves exactly that identity to the main window.
        val resolved = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(app.packageName), 0)
        assertTrue(resolved.any { it.activityInfo.name == MainActivity::class.java.name })
    }

    @Test
    fun aLauncherThatNamesThePackageIsCoveredByTheRepeatLaunchGuard() {
        // A launcher may build its app-row intent with PackageManager.getLeanbackLaunchIntentForPackage,
        // which names the package as well as the component. Android compares the package strictly
        // (Intent.filterEquals), so a task Woodruff's own intent started or re-rooted doesn't answer
        // to it: Android then starts a second main window over the film, and that window closes at
        // once, leaving the task as it was.
        val launcher = app.packageManager.getLeanbackLaunchIntentForPackage(app.packageName)!!
        assertEquals(main, launcher.component)
        assertTrue(MainIntents.isRepeatLaunch(isTaskRoot = false, intent = launcher))
        assertFalse(MainIntents.isRepeatLaunch(isTaskRoot = true, intent = launcher))
    }

    @Test
    fun openingAtAScreenClosesWhatsOverTheMainWindowNeverASecondOne() {
        val i = MainIntents.open(app, "home")
        assertEquals("home", i.getStringExtra(MainActivity.EXTRA_OPEN))
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, i.flags and flags)
    }

    @Test
    fun resumingIsLikeTheAppRow() {
        val i = MainIntents.resume(app)
        assertNull(i.getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, i.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals("no CLEAR_TOP: a paused film stays", 0, i.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    @Test
    fun aSecondWindowFromTheAppRowIsARepeatLaunch() {
        assertTrue(MainIntents.isRepeatLaunch(isTaskRoot = false, intent = appRow()))
        assertTrue(MainIntents.isRepeatLaunch(false, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertTrue(MainIntents.isRepeatLaunch(false, MainIntents.resume(app)))
        // The first window, or one opened for a screen (a notification over a film the phone started), stays.
        assertFalse(MainIntents.isRepeatLaunch(isTaskRoot = true, intent = appRow()))
        assertFalse(MainIntents.isRepeatLaunch(false, MainIntents.open(app, "notifications")))
        assertFalse(MainIntents.isRepeatLaunch(false, Intent(app, MainActivity::class.java)))
        assertFalse(MainIntents.isRepeatLaunch(false, null))
    }

    @Test
    fun onlyAndroid9To11NeedBackToKeepTheTask() {
        for (sdk in 24..30) assertTrue("sdk $sdk", MainIntents.backKeepsTask(sdk))
        for (sdk in 31..36) assertFalse("sdk $sdk", MainIntents.backKeepsTask(sdk))
    }

    @Test
    fun backAtTheLastLevelPutsWoodruffInTheBackgroundKeepingEverything() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity).setIsTaskRoot(true)
        activity.onBackPressedDispatcher.addCallback(MainIntents.keepTaskOnBack(activity))
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun backInAWindowOverAFilmClosesItAsBefore() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity).setIsTaskRoot(false)
        activity.onBackPressedDispatcher.addCallback(MainIntents.keepTaskOnBack(activity))
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(shadowOf(activity).isTaskMovedToBack)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun aRepeatLaunchClosesAtOnceAndCleanly() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, appRow())
        shadowOf(controller.get()).setIsTaskRoot(false)
        controller.create()
        assertTrue(controller.get().isFinishing)
        // Nothing was set up, so nothing is torn down that wasn't (the media receiver).
        controller.destroy()
    }
}
