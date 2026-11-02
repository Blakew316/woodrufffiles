package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.ui.SmoothBringIntoView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Streaming's Overview keeps its banner whole or out of sight: a step down from it into the first row
 * scrolls the rows up to just under the header, rather than leaving the banner's buttons as a strip
 * tucked under it; back up from the second row the first row rests there again; and up into the banner
 * shows all of it. As DriveScreen lays it out: the header above (64 dp) and the app's way of scrolling.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class OverviewBannerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    private fun settle(steps: Int = 30) = repeat(steps) {
        Thread.sleep(15)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
    }

    /** [n] films over 8 genres; the first 8 have wide art, so there's a banner. */
    private fun library(n: Int = 120): List<DriveFile> {
        val genres = listOf("Drama", "Comedy", "Thriller", "Family", "Sci-Fi", "Western", "Adventure", "Mystery")
        return (0 until n).map { i ->
            val info = MediaInfo(
                "Film $i", 1990 + i % 30, listOf(genres[i % genres.size]), rating = 5.0 + (i % 40) / 10.0,
                backdrop = if (i < 8) "file:///nowhere/backdrop$i.jpg" else null,
            )
            DriveFile("f$i", "Film $i.mkv", "video/x-matroska", 0, i.toLong() * 1000, null, info = info)
        }
    }

    /** The Overview of [files] under a 64 dp header, as DriveScreen lays it out, with the app's way of scrolling. */
    private fun overview(files: List<DriveFile>, place: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBringIntoViewSpec provides SmoothBringIntoView) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(64.dp))
                    Box(Modifier.fillMaxSize().testTag("rows")) {
                        LibraryRows(
                            files, null, placeKey = place, isShows = false, canTakeFocus = { true },
                            onChoose = {}, onPlay = {}, onFocusArt = {}, marks = remember { mutableIntStateOf(0) },
                        )
                    }
                }
            }
        }
        settle()
    }

    @Test fun aSmallLibrarysOneRowStillHidesTheBannerWhole() {
        // Five films: the banner and one row (Recently added), too short a list to scroll by itself.
        overview(library(5), "banner-one-row")
        compose.onNodeWithText("Play").assertIsFocused()
        val top = compose.onNodeWithTag("rows").fetchSemanticsNode().boundsInRoot.top
        press(Key.DirectionDown)
        settle()
        assertBannerGone(top)
        assertEquals(8f, (rowTitleTop() - top) / compose.density.density, 1.5f)
    }

    @Test fun theBannerIsWholeOrGone() {
        overview(library(), "banner-whole")
        compose.onNodeWithText("Play").assertIsFocused()
        val top = compose.onNodeWithTag("rows").fetchSemanticsNode().boundsInRoot.top
        val dp = compose.density.density

        // Down into the first row: the banner goes out of sight, the row comes to rest just under the header.
        press(Key.DirectionDown)
        settle()
        assertBannerGone(top)
        assertEquals("the first row's title rests 8 dp under the header", 8f, (rowTitleTop() - top) / dp, 1.5f)

        // Down to the second row and back up: the first row rests there again, no strip of banner above it.
        press(Key.DirectionDown)
        settle()
        press(Key.DirectionUp)
        settle()
        assertBannerGone(top)
        assertEquals(8f, (rowTitleTop() - top) / dp, 1.5f)

        // Up into the banner: all of it, the list back at its top (8 dp, the 300 dp banner, 18 dp, then the row).
        press(Key.DirectionUp)
        settle()
        compose.onNodeWithText("Play").assertIsFocused()
        assertEquals("the whole banner shows above the first row", 326f, (rowTitleTop() - top) / dp, 1.5f)
    }

    @Test fun theRuleKeepsTheListOffTheBannerForTheRowsOnly() {
        // A step down from the banner: the app's rule asks for 194 px, the first row is 318 px away.
        assertEquals(318f, bannerWholeOrGone(194f, fromBanner = false, toRows = 318f))
        // The rule asks for more than that (a row far below): as asked.
        assertEquals(400f, bannerWholeOrGone(400f, fromBanner = false, toRows = 318f))
        // Back up to the first row, its margin rule asking to go 25 px back into the banner: it stays put.
        assertEquals(0f, bannerWholeOrGone(-25f, fromBanner = false, toRows = 0f))
        assertEquals(-40f, bannerWholeOrGone(-130f, fromBanner = false, toRows = -40f))
        // The banner asking to show all of itself: as asked.
        assertEquals(-318f, bannerWholeOrGone(-318f, fromBanner = true, toRows = 0f))
    }

    /** The banner's Play button is gone, or above the rows' top edge (under the header, unseen). */
    private fun assertBannerGone(top: Float) {
        val play = compose.onAllNodesWithText("Play").fetchSemanticsNodes()
        assertTrue(
            "the banner's buttons show as a strip under the header (${play.map { it.boundsInRoot }})",
            play.all { it.boundsInRoot.bottom <= top + 0.5f },
        )
    }

    /** Where the first row's title ("Recently added") is. */
    private fun rowTitleTop(): Float = compose.onAllNodesWithText("Recently added").fetchSemanticsNodes().single().boundsInRoot.top
}
