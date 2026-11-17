package com.woodruff.tvexplorer.notify

import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.ui.SearchKey
import com.woodruff.tvexplorer.ui.motion.DialogWindows
import com.woodruff.tvexplorer.ui.motion.dpadGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/**
 * Woodruff's own notice card is drawn in its main window, under that window's pop-ups (a title page, Search, the
 * filters): while one is up, a notice shows over it as over another app, and not under it as well.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoticeOverPopUpsTest {
    /** Stands in for the main window. */
    class Main : ComponentActivity(), SearchKey.Host

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun settle() = repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    /** Whether a notice card over other apps (NoticeOverlay's window) shows [title]. */
    private fun cardOverApps(title: String): Boolean {
        val wm = Shadow.extract<ShadowWindowManagerImpl>(app.getSystemService(WindowManager::class.java))
        return wm.views.any { it.shows(title) }
    }

    private fun View.shows(text: String): Boolean = when (this) {
        is TextView -> this.text.toString() == text
        is ViewGroup -> (0 until childCount).any { getChildAt(it).shows(text) }
        else -> false
    }

    /** [main] is the main window in front, as MainActivity says in onStart. */
    private fun inFront(main: Main) {
        AppVisibility.foreground = true
        DialogWindows.mainWindow(main)
    }

    @After fun reset() {
        AppVisibility.foreground = false
        DialogWindows.mainWindow(null)
        ShadowSettings.setCanDrawOverlays(false)
        Notices.dismissLatest()
    }

    @Test fun aPopUpOverTheMainWindowHasTheCardOverIt() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        var popUp by mutableStateOf(false)
        main.setContent { if (popUp) Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp).dpadGate()) } }
        settle()
        Notices.load(app)
        Notices.dismissLatest()
        // No card left up over other apps by another test (a doorbell's keeps its place).
        NoticeOverlay.hide()
        settle()
        inFront(main)
        ShadowSettings.setCanDrawOverlays(true)

        // Nothing over the main window: the card is its own, there.
        assertFalse(DialogWindows.overMainWindow)
        val first = Notices.post(app, NoticeSource.PHONE, "Dinner's ready!", "")
        settle()
        assertSame(first, Notices.latest.value)
        assertFalse(cardOverApps("Dinner's ready!"))

        // A title page (Search, the filters…) over it: the card goes over that, and isn't under it too.
        popUp = true
        settle()
        assertTrue(DialogWindows.overMainWindow)
        Notices.post(app, NoticeSource.SYSTEM, "Parcel left at the gate", "Front Door")
        settle()
        assertTrue(cardOverApps("Parcel left at the gate"))
        assertNull(Notices.latest.value)

        popUp = false
        settle()
        assertFalse(DialogWindows.overMainWindow)
    }

    @Test fun withoutDrawingOverAppsItIsTheTvsNotificationAndStillTheMainWindowsCard() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        main.setContent { Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp).dpadGate()) } }
        settle()
        Notices.load(app)
        Notices.dismissLatest()
        inFront(main)
        assertTrue(DialogWindows.overMainWindow)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val before = nm.allNotifications.size

        val n = Notices.post(app, NoticeSource.PHONE, "Dinner's ready!", "")
        settle()
        // Android's own notification, and the card in the main window for when the pop-up closes.
        assertEquals(before + 1, nm.allNotifications.size)
        assertSame(n, Notices.latest.value)
    }

    @Test fun anotherScreensPopUpsLeaveTheMainWindowsCardAlone() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        main.setContent { }
        inFront(main)
        // The player's options, say: over the player, not over the main window.
        val other = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        other.setContent { Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp).dpadGate()) } }
        settle()
        assertFalse(DialogWindows.overMainWindow)
        // A main window that went from the front has none in front either.
        main.setContent { Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp).dpadGate()) } }
        settle()
        assertTrue(DialogWindows.overMainWindow)
        DialogWindows.mainWindowGone(main)
        assertFalse(DialogWindows.overMainWindow)
    }
}
