package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The Command Center as the TV shows it (under the hub's pills, four cameras beside the notifications):
 * when the cameras go offline, the calm line under the title appears without moving the cameras, so the
 * bottom row keeps its corners and names inside the screen's margin.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CommandCenterLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val names = listOf("Front Door", "Backyard", "Garage", "Driveway")

    @After fun forget() {
        DashboardSamples.nest = null
        DashboardSamples.cameras = null
    }

    /** Four cameras, connected; no live video in a test (none of them plays here). */
    private fun cameras(offline: Boolean) = NestState(
        step = NestStep.CONNECTED,
        cameras = names.map { Camera(it, it, null, webRtc = false, doorbell = false) },
        offline = offline,
    )

    @Test fun goingOfflineMovesNoCameraAndKeepsTheBottomRowOnScreen() {
        val vm = MainViewModel(app)
        DashboardSamples.nest = cameras(offline = false)
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        show(vm)
        assertTrue("no offline line while online", compose.onAllNodesWithText(OFFLINE_LINE, substring = true).fetchSemanticsNodes().isEmpty())
        val online = names.associateWith(::bounds)

        DashboardSamples.nest = cameras(offline = true)
        show(vm)
        val line = compose.onAllNodesWithText(OFFLINE_LINE, substring = true).fetchSemanticsNodes().single().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val dp = compose.density.density
        for (name in names) {
            val now = bounds(name)
            assertEquals("$name's name didn't move", online.getValue(name), now)
            assertTrue("$name's name is ${(now.bottom - screen.bottom) / dp} dp past the screen's bottom margin", now.bottom <= screen.bottom - 8 * dp)
            assertTrue("the offline line sits above $name", line.bottom <= now.top)
        }
    }

    /** Where camera [name]'s label is. */
    private fun bounds(name: String): Rect = compose.onAllNodesWithText(name).fetchSemanticsNodes().single().boundsInRoot

    /** The Command Center opened afresh (from Home), with the sample cameras as they are now. */
    private fun show(vm: MainViewModel) {
        compose.runOnIdle { vm.show(Screen.HOME) }
        settle()
        compose.runOnIdle { vm.show(Screen.CAMERAS) }
        settle()
    }

    /** Frames and the screens' glides, well inside the offline line's 4 s grace (the samples say offline at once). */
    private fun settle() = repeat(8) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
    }

    private companion object {
        const val OFFLINE_LINE = "the cameras reconnect by themselves"
    }
}
