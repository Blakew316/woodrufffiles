package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.ui.LocalOpenSidebar
import com.woodruff.tvexplorer.ui.SmoothBringIntoView
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Browsing feel, on real screens' pieces (browsing-motion §3, §4): Back from deep in the Overview's
 * rows glides to the top with the banner's Play focused, and the next Back opens the side menu; Back
 * along a row (no banner) brings that row back to its start; Back deep in a grid glides to its top
 * with the first poster focused; a quick double Back goes straight to the side menu without the
 * focus coming back.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class BrowsingMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    /** Frames, focus, glides and the rows worked out off the main thread. */
    private fun settle(steps: Int = 20) = repeat(steps) {
        Thread.sleep(15)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
    }

    /** A library of [n] films over 8 genres, rated; the first 8 with wide art when [featured] (the banner). */
    private fun library(n: Int, featured: Boolean): List<DriveFile> {
        val genres = listOf("Drama", "Comedy", "Thriller", "Family", "Sci-Fi", "Western", "Adventure", "Mystery")
        return (0 until n).map { i ->
            val info = MediaInfo(
                "Film $i", 1990 + i % 30, listOf(genres[i % genres.size]), rating = 5.0 + (i % 40) / 10.0,
                backdrop = if (featured && i < 8) "file:///nowhere/backdrop$i.jpg" else null,
            )
            DriveFile("f$i", "Film $i.mkv", "video/x-matroska", 0, i.toLong() * 1000, null, info = info)
        }
    }

    @Test
    fun backDeepInTheRowsGlidesToTheBannersPlayThenOpensTheSideMenu() {
        var sideMenu = 0
        val files = library(120, featured = true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // As MainActivity and DriveScreen set it up: the app's scrolling, and at a library's top the
            // screen's own Back opening the side menu (under the rows').
            CompositionLocalProvider(LocalOpenSidebar provides { sideMenu++ }, LocalBringIntoViewSpec provides SmoothBringIntoView) {
                BackHandler { sideMenu++ }
                LibraryRows(
                    files, null, placeKey = "rows-banner", isShows = false, canTakeFocus = { true },
                    onChoose = {}, onPlay = {}, onFocusArt = {}, marks = remember { mutableIntStateOf(0) },
                )
            }
        }
        settle()
        compose.onNodeWithText("Play").assertIsFocused()
        // Down to the sixth row.
        repeat(7) { press(Key.DirectionDown); settle(8) }
        compose.onNodeWithText("Play").assertDoesNotExistOrIsUnfocused()

        back()
        settle(40)
        compose.onNodeWithText("Play").assertIsFocused()
        assertEquals("the first Back went to the top, not the side menu", 0, sideMenu)

        back()
        settle()
        assertEquals(1, sideMenu)
    }

    @Test
    fun backAlongARowBringsItBackToItsStartWhenThereIsNoBanner() {
        var sideMenu = 0
        val files = library(60, featured = false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalOpenSidebar provides { sideMenu++ }, LocalBringIntoViewSpec provides SmoothBringIntoView) {
                BackHandler { sideMenu++ }
                LibraryRows(
                    files, null, placeKey = "rows-plain", isShows = false, canTakeFocus = { true },
                    onChoose = {}, onPlay = {}, onFocusArt = {}, marks = remember { mutableIntStateOf(0) },
                )
            }
        }
        settle()
        // The first row's first poster has the focus (no banner); along the row to its sixth.
        val firstTitle = files.maxByOrNull { it.added }!!.info!!.let { "${it.title} (${it.year})" } // Recently added leads
        compose.onNodeWithText(firstTitle).assertIsFocused()
        repeat(5) { press(Key.DirectionRight); settle(6) }

        back()
        settle(30)
        compose.onNodeWithText(firstTitle).assertIsFocused()
        assertEquals(0, sideMenu)

        back()
        settle()
        assertEquals(1, sideMenu)
    }

    /** A grid of [count] focusable tiles under Back to the top, with a "menu" to move the focus out to. */
    private fun grid(count: Int, start: Int, onSideMenu: () -> Unit): Pair<() -> LazyGridState, FocusRequester> {
        lateinit var state: LazyGridState
        val menu = FocusRequester()
        compose.setContent {
            CompositionLocalProvider(LocalOpenSidebar provides onSideMenu) {
                BackHandler { onSideMenu() }
                state = rememberLazyGridState(initialFirstVisibleItemIndex = start)
                val held = remember { mutableStateOf(false) }
                val first = remember { FocusRequester() }
                GridBackToTop(state, first, held)
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.size(10.dp).testTag("menu").focusRequester(menu).focusable())
                    LazyVerticalGrid(GridCells.Fixed(4), state = state, modifier = Modifier.fillMaxSize().onFocusChanged { held.value = it.hasFocus }) {
                        items(count) { i ->
                            Box(Modifier.height(120.dp).testTag("tile$i").then(if (i == 0) Modifier.focusRequester(first) else Modifier).focusable())
                        }
                    }
                }
            }
        }
        return { state } to menu
    }

    @Test
    fun backDeepInAGridGlidesToItsTopWithTheFirstPosterFocused() {
        var sideMenu = 0
        compose.mainClock.autoAdvance = false
        val (state, _) = grid(400, start = 200) { sideMenu++ }
        settle(4)
        compose.onNodeWithTag("tile201").requestFocus()
        settle(4)

        back()
        settle(40)
        assertEquals(0, state().firstVisibleItemIndex)
        assertEquals(0, state().firstVisibleItemScrollOffset)
        compose.onNodeWithTag("tile0").assertIsFocused()
        assertEquals(0, sideMenu)

        back()
        settle()
        assertEquals(1, sideMenu)
    }

    @Test
    fun aQuickDoubleBackGoesStraightToTheSideMenuAndTheGlideFinishesUnderneath() {
        var sideMenu = 0
        lateinit var menu: FocusRequester
        compose.mainClock.autoAdvance = false
        val (state, m) = grid(400, start = 200) {
            sideMenu++
            // As the side menu does: it takes the focus.
            menu.requestFocus()
        }
        menu = m
        settle(4)
        compose.onNodeWithTag("tile201").requestFocus()
        settle(4)

        back()
        back() // within 400 ms: no time has passed
        assertEquals(1, sideMenu)
        settle(40)
        assertEquals("the glide finished underneath", 0, state().firstVisibleItemIndex)
        compose.onNodeWithTag("menu").assertIsFocused()
    }

    @Test
    fun aGridThatFitsLeavesBackToTheScreen() {
        var sideMenu = 0
        compose.mainClock.autoAdvance = false
        grid(8, start = 0) { sideMenu++ }
        settle(4)
        compose.onNodeWithTag("tile5").requestFocus()
        settle(4)
        back()
        settle()
        assertEquals(1, sideMenu)
    }

    @Test
    fun backDeepInAListGlidesToItsTop() {
        var sideMenu = 0
        lateinit var state: LazyListState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalOpenSidebar provides { sideMenu++ }) {
                BackHandler { sideMenu++ }
                state = rememberLazyListState(initialFirstVisibleItemIndex = 120)
                val held = remember { mutableStateOf(false) }
                val first = remember { FocusRequester() }
                ListBackToTop(state, first, held)
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().onFocusChanged { held.value = it.hasFocus }) {
                    items(300) { i ->
                        Box(Modifier.height(60.dp).testTag("row$i").then(if (i == 0) Modifier.focusRequester(first) else Modifier).focusable())
                    }
                }
            }
        }
        settle(4)
        compose.onNodeWithTag("row121").requestFocus()
        settle(4)
        back()
        settle(40)
        assertEquals(0, state.firstVisibleItemIndex)
        compose.onNodeWithTag("row0").assertIsFocused()
        assertEquals(0, sideMenu)
    }
}

/** The node is gone (scrolled away) or isn't focused. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrIsUnfocused() {
    val node = runCatching { fetchSemanticsNode() }.getOrNull() ?: return
    val focused = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Focused) { null }
    assertTrue("still focused", focused != true)
}
