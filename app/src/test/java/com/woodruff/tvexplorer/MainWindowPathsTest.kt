package com.woodruff.tvexplorer

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.server.ServerState
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.update.Restore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One way into Woodruff's main window, whatever opens it: a notice's tap, the phone connection's
 * notification, the reopening after an update, the phone's Home key. Each builds its intent with
 * [MainIntents], so the task it starts or brings back keeps the TV app row's identity: opening Woodruff
 * from the app row afterwards finds that task as it was, and a second main window never stacks on the
 * first. Nothing else in the app names the main window, so nothing can open it with a bare intent.
 * (The recovery screen's "Try opening again" makes its window again in place: no intent at all.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainWindowPathsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** What the TV's app row starts (Android TV / Google TV home). */
    private fun appRow() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setComponent(ComponentName(app, MainActivity::class.java))

    private val atScreen = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    @Test fun nothingButMainIntentsNamesTheMainWindow() {
        val mainIntents = SourceScan.files.single { it.first == "MainIntents.kt" }.second
        assertTrue("the one place that builds it", mainIntents.contains("MainActivity::class.java"))
        // By its class, by its name, or as the app's own launcher entry: anywhere else, a main window
        // opened without the app row's identity (a second Woodruff over the first, a task the app row
        // can't find again).
        val elsewhere = SourceScan.matches(Regex("""\bMainActivity::class\b""")).filter { !it.startsWith("MainIntents.kt:") } +
            SourceScan.matches(Regex(""""[\w.]*\.MainActivity"""")) +
            SourceScan.matches(Regex("""get(Leanback)?LaunchIntentForPackage\(\s*(BuildConfig\.APPLICATION_ID|"com\.woodruff|(context|c|ctx|app|application|applicationContext|activity|this)\.packageName\b)"""))
        assertEquals("open the main window through MainIntents.open / resume instead", emptyList<String>(), elsewhere)
    }

    @Test fun aNoticesTapOpensTheMainWindowAtNotifications() {
        val nm = app.getSystemService(NotificationManager::class.java)
        AppVisibility.foreground = false
        try {
            // Away from Woodruff, without the pop-up permission: Android's own notification.
            Notices.post(app, NoticeSource.PHONE, "Dinner's ready", "Come down when you can")
            val tap = shadowOf(shadowOf(nm).allNotifications.single().contentIntent).savedIntent
            assertTrue(tap.filterEquals(appRow()))
            assertEquals("notifications", tap.getStringExtra(MainActivity.EXTRA_OPEN))
            // The open main window takes it (whatever was over it closes), never a second one.
            assertEquals(atScreen, tap.flags and atScreen)
            assertFalse(MainIntents.isRepeatLaunch(isTaskRoot = false, intent = tap))
        } finally {
            Notices.clear(app)
            Notices.dismissLatest()
            nm.cancelAll()
        }
    }

    @Test fun thePhoneConnectionsNotificationBringsWoodruffBackAsItWas() {
        var shown: Notification? = null
        val allowed = TransferService.foreground
        // Its notification as the service enters the foreground; refused, so no server starts here.
        TransferService.foreground = { _, _, n, _ -> shown = n; throw IllegalStateException("not in this test") }
        val service = Robolectric.buildService(TransferService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, TransferService::class.java), 0, 1)
            val tap = shadowOf(assertNotNullAndGet(shown).contentIntent).savedIntent
            assertTrue(tap.filterEquals(appRow()))
            // As the app row opens it: the task to the front as it was, a paused film still over its screen.
            assertNull(tap.getStringExtra(MainActivity.EXTRA_OPEN))
            assertEquals(0, tap.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } finally {
            service.destroy()
            TransferService.foreground = allowed
            TransferService.showForGallery(ServerState())
        }
    }

    @Test fun anUpdateReopensTheScreenItInterruptedInTheMainWindowTheAppRowFinds() {
        Restore.reopen(app, Restore.App(Screen.SETTINGS)) { false }
        shadowOf(Looper.getMainLooper()).idle()
        val main = assertNotNullAndGet(shadowOf(app).nextStartedActivity)
        assertTrue(main.filterEquals(appRow()))
        assertEquals(Restore.OPEN, main.getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(Restore.App(Screen.SETTINGS), Restore.decode(main.getStringExtra(Restore.EXTRA_ROUTE)))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or atScreen, main.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or atScreen))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }
}
