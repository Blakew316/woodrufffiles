package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.player.RecentStore
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
 * Streaming's Continue watching while the Overview stays up: it follows what's saved (a film started elsewhere comes
 * in, one finished goes), and a tile that goes hands the focus to its neighbour first.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ContinueRowLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle(steps: Int = 20) = repeat(steps) {
        Thread.sleep(15)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
    }

    private val films = (0 until 40).map { i ->
        val info = MediaInfo("Film $i", 2000 + i % 20, listOf(listOf("Drama", "Comedy", "Family")[i % 3]), rating = 6.0 + (i % 30) / 10.0)
        DriveFile("f$i", "Film $i.mkv", "video/x-matroska", 0, i * 1000L, null, info = info)
    }

    /** ✓s or progress changed (what Streaming counts: the player's saves among them). */
    private val marks = mutableIntStateOf(0)

    private fun rows() {
        val app = compose.activity.application
        FamilySync.setEnabled(app, false)
        RecentStore(app).apply {
            // Newest first: Film 5, then Film 3.
            save("drive:f3", "Film 3.mkv", 600_000, 6_000_000)
            save("drive:f5", "Film 5.mkv", 900_000, 6_000_000)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBringIntoViewSpec provides SmoothBringIntoView) {
                LibraryRows(
                    films, null, placeKey = "continue-live", isShows = false, canTakeFocus = { true },
                    onChoose = {}, onPlay = {}, onFocusArt = {}, marks = marks,
                )
            }
        }
        settle()
    }

    private fun tiles(title: String) = compose.onAllNodes(hasText(title) and hasClickAction()).fetchSemanticsNodes()

    /** Frames until [done] (a read off the main thread comes back in one of them), three seconds at most. */
    private fun settleUntil(done: () -> Boolean) {
        repeat(60) { if (done()) return; settle(1) }
    }

    @Test fun aFilmFinishedWhileTheRowsAreUpGoesAndTheFocusMovesToItsNeighbour() {
        rows()
        // Only in Continue watching (a title's in one row only as the rows are worked out).
        assertEquals(1, tiles("Film 5 (2005)").size)
        compose.onAllNodes(hasText("Film 5 (2005)") and hasClickAction())[0].requestFocus()
        settle(4)
        // Watched to the end (the player saves it as it closes), then counted as a change.
        compose.runOnIdle { RecentStore(compose.activity).remove("drive:f5") }
        marks.intValue++
        settleUntil { tiles("Film 5 (2005)").isEmpty() }
        assertTrue(tiles("Film 5 (2005)").isEmpty())
        // Its neighbour has the focus: it was never left on nothing.
        val focused = { compose.onAllNodes(hasText("Film 3 (2003)") and hasClickAction() and isFocused()).fetchSemanticsNodes().size }
        settleUntil { focused() == 1 }
        assertEquals(1, focused())
    }

    @Test fun aFilmStartedElsewhereComesIn() {
        rows()
        val before = tiles("Film 7 (2007)").size
        compose.runOnIdle { RecentStore(compose.activity).save("drive:f7", "Film 7.mkv", 600_000, 6_000_000) }
        marks.intValue++
        settleUntil { tiles("Film 7 (2007)").size == before + 1 }
        // In Continue watching now, at its start (and still in the row it was in as the rows opened).
        assertEquals(before + 1, tiles("Film 7 (2007)").size)
    }
}
