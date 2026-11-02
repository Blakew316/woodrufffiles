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
import com.woodruff.tvexplorer.ui.SearchKey
import com.woodruff.tvexplorer.util.EarlyCrashes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * The remote's Search button in the main window, which both the recovery screen and Woodruff's Search
 * want: while the recovery screen is up it stays the recovery screen's (nothing of the app runs under
 * it, Search included), and once Woodruff opens normally again each press asks for Search once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RecoverySearchKeyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searches = 0

    @Before fun twoClosesInARow() {
        reset()
        // No network in tests: the check finds nothing (set first: Woodruff's own start may look too).
        Updater.listReleases = { emptyList() }
        repeat(2) {
            EarlyCrashes.opened(app)
            EarlyCrashes.crashed(app, null)
            EarlyCrashes.resetForTest()
        }
        EarlyCrashes.lastExit = { _, _, _ -> null }
        RecoveryView.lookForFix = { _, _ -> }
        RecoveryView.load = { it.run() }
        // Somewhere for Android to say whether Woodruff may use the TV's storage (it may not, here).
        org.robolectric.shadows.ShadowEnvironment.addExternalDir("storage")
        scope.launch(start = CoroutineStart.UNDISPATCHED) { SearchKey.presses.collect { searches++ } }
    }

    @After fun clean() {
        scope.cancel()
        reset()
    }

    private fun reset() {
        EarlyCrashes.resetForTest()
        File(app.filesDir, "early_crashes.txt").delete()
        File(app.filesDir, "early_crash_report.txt").delete()
        RecoveryView.resetForTest()
        Updater.resetForTest()
        AppVisibility.foreground = false
    }

    private fun content(a: MainActivity): View = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    /** One press of Search, as the main window hands it over; whether all of it was taken. */
    private fun search(a: MainActivity): Boolean {
        val down = a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH))
        val up = a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SEARCH))
        shadowOf(Looper.getMainLooper()).idle()
        return down && up
    }

    private fun press(a: MainActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Test fun searchStaysWithTheRecoveryScreenAndOpensSearchOnceWoodruffIsBack() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(content(controller.get()) is RecoveryView)
        // The recovery screen takes it (there's nothing to search there), and Woodruff's Search never
        // hears of it.
        assertTrue(search(controller.get()))
        assertEquals(0, searches)

        // Try opening again: Woodruff, normally, where the same button asks for Search.
        press(controller.get(), KeyEvent.KEYCODE_DPAD_RIGHT)
        press(controller.get(), KeyEvent.KEYCODE_DPAD_CENTER)
        shadowOf(Looper.getMainLooper()).idle()
        val again = controller.get()
        assertFalse(content(again) is RecoveryView)
        assertTrue(search(again))
        assertEquals(1, searches)
        controller.pause().stop().destroy()
    }
}
