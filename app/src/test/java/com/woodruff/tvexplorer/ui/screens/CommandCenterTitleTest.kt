package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The Command Center's headline is written as the side menu, Home and Settings write its name: "Command Center". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CommandCenterTitleTest {
    @get:Rule val compose = createComposeRule()

    @After fun forget() { DashboardSamples.nest = null }

    @Test fun theHeadlineSaysCommandCenter() {
        DashboardSamples.nest = NestState(step = NestStep.CONNECTED, cameras = listOf(Camera("Front Door", "Front Door", null, webRtc = false, doorbell = true)))
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        compose.runOnIdle { vm.show(Screen.CAMERAS) }
        repeat(8) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
        assertTrue("the headline is there", compose.onAllNodesWithText("Command Center").fetchSemanticsNodes().isNotEmpty())
        assertTrue("nothing says “Command center”", compose.onAllNodesWithText("Command center").fetchSemanticsNodes().isEmpty())
    }
}
