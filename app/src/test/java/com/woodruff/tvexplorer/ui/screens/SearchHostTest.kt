package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.VoiceSearch
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Search as the app opens it: the side menu's first item, the remote's button (from MainActivity), its own page
 * window over the screen, and Back closing it. On a TV without Google Drive set up, the page says so.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchHostTest {
    @get:Rule val compose = createComposeRule()

    @After fun closeSearch() = SearchPages.close()

    private fun app() {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent { TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test fun theSideMenuStartsWithSearch() {
        app()
        compose.onNode(hasText("Search") and hasClickAction()).assertExists()
        compose.onNode(hasText("Search") and hasClickAction()).fetchSemanticsNode().let { search ->
            val home = compose.onNode(hasText("Home") and hasClickAction()).fetchSemanticsNode()
            assertTrue("Search sits above Home", search.boundsInRoot.top < home.boundsInRoot.top)
        }
    }

    @Test fun theRemotesButtonOpensSearchAndBackClosesIt() {
        app()
        VoiceSearch.requests.tryEmit(Unit)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue(SearchPages.isOpen)
        assertEquals(SearchFrom.REMOTE, SearchPages.request.value?.from)
        // No Google Drive on this TV: Search says so, its one button focused.
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText("Search needs Google Drive")).assertExists()
        compose.waitUntil(5_000) { runCatching { compose.onNode(hasText("Set up Google Drive") and hasClickAction()).assertIsFocused() }.isSuccess }
        Espresso.pressBack()
        compose.waitForIdle()
        assertFalse(SearchPages.isOpen)
    }

    @Test fun pressedAgainWhileOpenItListensAgainRatherThanReopening() {
        app()
        SearchPages.open(SearchFrom.MENU)
        compose.waitForIdle()
        val first = SearchPages.request.value
        SearchPages.searchKey()
        compose.waitForIdle()
        assertEquals(first, SearchPages.request.value)
    }
}
