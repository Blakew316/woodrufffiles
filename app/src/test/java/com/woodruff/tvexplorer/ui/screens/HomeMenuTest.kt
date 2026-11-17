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

/**
 * Home's Continue watching: holding OK on a tile opens the title menu, a tile taken off hands the focus on, and the
 * row follows what's saved while Home is up (the focus staying put, or handed on before a tile goes).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HomeMenuTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun reset() { DashboardSamples.tvs = null; DashboardSamples.cameras = null }

    private fun home(seed: RecentStore.() -> Unit = {
        // Newest first: Midnight Harbor, then Paper Planes.
        save("/storage/usb/Paper Planes (2022).mkv", "Paper Planes (2022).mkv", 3_000_000, 5_600_000)
        save("/storage/usb/Midnight Harbor (2024).mkv", "Midnight Harbor (2024).mkv", 2_710_000, 6_840_000)
    }) {
        FamilySync.setEnabled(app, false)
        RecentStore(app).seed()
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

    @Test fun whatsSavedWhileHomeIsUpShowsAtOnce() {
        home()
        // The player saves where it stopped as it closes, a moment after Home is back on screen.
        compose.runOnIdle { RecentStore(app).save("/storage/usb/Orbit (2021).mkv", "Orbit (2021).mkv", 1_000_000, 6_000_000) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Orbit (2021)") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aTileThatLeavesContinueWatchingHandsTheFocusOnFirst() {
        home()
        tile("Midnight Harbor (2024)").requestFocus()
        compose.waitForIdle()
        // Watched to the end, or taken off on another TV or the phone: gone from Continue watching.
        compose.runOnIdle { RecentStore(app).remove("/storage/usb/Midnight Harbor (2024).mkv") }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Midnight Harbor (2024)") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
        // The focus went to its neighbour before it went, not to nothing.
        compose.waitUntil(5_000) { runCatching { tile("Paper Planes (2022)").assertIsFocused() }.isSuccess }
    }

    @Test fun aShowsTileKeepsTheFocusAsTheShowMovesOn() {
        home {
            save("/storage/usb/Paper Planes (2022).mkv", "Paper Planes (2022).mkv", 3_000_000, 5_600_000)
            save("/storage/usb/Backyard Science - S01E03.mkv", "Backyard Science - S01E03.mkv", 600_000, 1_500_000)
        }
        tile("Backyard Science - S01E03").requestFocus()
        compose.waitForIdle()
        // S1 E3 watched to the end and the next one up next, as the player leaves them.
        compose.runOnIdle {
            RecentStore(app).apply {
                save("/storage/usb/Backyard Science - S01E03.mkv", "Backyard Science - S01E03.mkv", 1_500_000, 1_500_000)
                saveUpNext("/storage/usb/Backyard Science - S01E04.mkv", "Backyard Science - S01E04.mkv")
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Next · S1 E4")).fetchSemanticsNodes().isNotEmpty() }
        // The same tile, the show's, now for S1 E4: still the one with the focus.
        compose.waitUntil(5_000) { runCatching { tile("Backyard Science - S01E04").assertIsFocused() }.isSuccess }
        assertTrue(compose.onAllNodes(hasText("Backyard Science - S01E03") and hasClickAction()).fetchSemanticsNodes().isEmpty())
    }
}
