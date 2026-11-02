package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import android.view.KeyEvent
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
import com.woodruff.tvexplorer.ui.SearchKey
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Search as the app opens it: the side menu's first item, the remote's button (as the main window and its pop-ups
 * hand it over, SearchKey), its own page window over the screen, and Back closing it. On a TV without Google Drive
 * set up, the page says so.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchHostTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun closeSearch() {
        scope.cancel()
        SearchPages.close()
    }

    /** One press of the remote's Search button, as a window of the main window hands it over. */
    private fun pressSearch() = compose.runOnIdle {
        SearchKey.take(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH))
        SearchKey.take(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SEARCH))
    }

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
        pressSearch()
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
        var listenAgain = 0
        scope.launch(start = CoroutineStart.UNDISPATCHED) { SearchPages.listenAgain.collect { listenAgain++ } }
        SearchPages.open(SearchFrom.MENU)
        compose.waitForIdle()
        val first = SearchPages.request.value
        pressSearch()
        compose.waitForIdle()
        assertEquals(first, SearchPages.request.value)
        assertEquals(1, listenAgain)
    }
}
