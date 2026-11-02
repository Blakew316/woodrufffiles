package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.player.RecentStore
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
import org.robolectric.annotation.Config

/** Home's Continue watching: holding OK on a tile opens the title menu, and a tile taken off hands the focus on. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HomeMenuTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun reset() { DashboardSamples.tvs = null; DashboardSamples.cameras = null }

    private fun home() {
        FamilySync.setEnabled(app, false)
        // Newest first: Midnight Harbor, then Paper Planes.
        RecentStore(app).apply {
            save("/storage/usb/Paper Planes (2022).mkv", "Paper Planes (2022).mkv", 3_000_000, 5_600_000)
            save("/storage/usb/Midnight Harbor (2024).mkv", "Midnight Harbor (2024).mkv", 2_710_000, 6_840_000)
        }
        DashboardSamples.tvs = emptyList()
        DashboardSamples.cameras = emptyList()
        val vm = MainViewModel(app)
        compose.setContent { TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } } }
        compose.runOnIdle { vm.show(Screen.HOME) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun tile(title: String) = compose.onNode(hasText(title) and hasClickAction())

    @Test fun holdingOkOnATileOffersItsMenuAndRemovingItMovesTheFocusOn() {
        home()
        tile("Midnight Harbor (2024)").requestFocus()
        compose.waitForIdle()
        tile("Midnight Harbor (2024)").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Remove from Continue watching")).fetchSemanticsNodes().isNotEmpty() }
        // The menu's title is the title, not the file's name.
        assertTrue(compose.onAllNodes(hasText("Midnight Harbor (2024).mkv")).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasText("Remove from Continue watching") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Midnight Harbor (2024)") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
        // Its neighbour on the right has the focus.
        compose.waitUntil(5_000) { runCatching { tile("Paper Planes (2022)").assertIsFocused() }.isSuccess }
    }
}
