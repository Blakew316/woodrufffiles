package com.woodruff.tvexplorer.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.ui.screens.DashboardSamples
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.update.Restore
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Woodruff opened at a screen (decided before its first frame, as MainActivity.handleOpen does for a
 * screen put back after an update, a notification's tap or the phone's Home key): that screen has the
 * focus once it's up. The side menu's start-up fallback (for a screen with nothing to focus) doesn't
 * open over it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class OpenedAtAScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The side menu's items: the focus on one of them means the menu opened. */
    private val menu = setOf("Search", "Home", "Streaming", "Family Memories", "Command Center", "Settings")

    @After fun reset() { DashboardSamples.tvs = null; DashboardSamples.cameras = null }

    private fun openAt(open: (MainViewModel) -> Unit) {
        FamilySync.setEnabled(app, false)
        DashboardSamples.tvs = emptyList()
        DashboardSamples.cameras = emptyList()
        val vm = MainViewModel(app)
        open(vm)
        compose.setContent { TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } } }
        // Past the start-up fallbacks (the content at 300 ms, the side menu at 400 ms).
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    /** What has the focus, by its words. */
    private fun focused(): String {
        val node = compose.onAllNodes(isFocused()).fetchSemanticsNodes().singleOrNull()
        assertNotNull("one thing has the focus", node)
        return node!!.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }

    @Test fun aScreenPutBackAfterAnUpdateKeepsTheFocus() {
        openAt { vm -> Restore.apply(Restore.App(Screen.SETTINGS), vm) { error("only Streaming asks for it") } }
        val on = focused()
        assertTrue("in Settings, not the side menu: $on", on !in menu)
    }

    @Test fun aNotificationsTapLandsOnItsPill() {
        openAt { vm -> vm.show(Screen.NOTIFICATIONS) }
        compose.onNode(hasText("Notifications") and hasClickAction()).assertIsFocused()
    }

    @Test fun thePhonesHomeKeyLandsOnHome() {
        openAt { vm -> vm.show(Screen.HOME) }
        val on = focused()
        assertTrue("on Home, not the side menu: $on", on !in menu)
    }

    @Test fun theCommandCenterPutBackWithItsCamerasKeepsTheFocus() {
        openAt { vm -> Restore.apply(Restore.App(Screen.CAMERAS, camera = "Front door", cameraAt = 0), vm) { error("only Streaming asks for it") } }
        val on = focused()
        assertTrue("in the Command Center, not the side menu: $on", on !in menu)
    }
}
