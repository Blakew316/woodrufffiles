package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.ui.SmoothBringIntoView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The Overview's first focus: the banner's Play takes it once the rows are in (tried frame by frame
 * until it's there, not once and forgotten), unless someone pressed a key meanwhile: then the focus
 * stays where they put it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class LibraryRowsFirstFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle(steps: Int = 20) = repeat(steps) {
        Thread.sleep(15)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
    }

    /** Films with wide art for the banner, over a few genres for the rows. */
    private val films = (0 until 40).map { i ->
        val info = MediaInfo(
            "Film $i", 2000 + i % 20, listOf(listOf("Drama", "Comedy", "Family")[i % 3]), rating = 6.0 + (i % 30) / 10.0,
            backdrop = if (i < 6) "file:///nowhere/backdrop$i.jpg" else null,
        )
        DriveFile("f$i", "Film $i.mkv", "video/x-matroska", 0, i * 1000L, null, info = info)
    }

    /** The rows, with something else focusable above them (as Streaming's header is). */
    private fun rows() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBringIntoViewSpec provides SmoothBringIntoView) {
                Column {
                    androidx.compose.foundation.layout.Box(Modifier.size(40.dp).testTag("header").focusable())
                    LibraryRows(
                        films, null, placeKey = "first-focus", isShows = false, canTakeFocus = { true },
                        onChoose = {}, onPlay = {}, onFocusArt = {}, marks = remember { mutableIntStateOf(0) },
                    )
                }
            }
        }
    }

    @Test fun theBannersPlayTakesTheFocusOnceTheRowsAreIn() {
        rows()
        settle()
        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test fun aKeyPressedBeforeTheyreInLeavesTheFocusWhereItWent() {
        rows()
        // Someone presses a key while the rows are still being worked out: the focus goes where their press
        // put it (the header here), and the rows arriving after don't take it.
        compose.onNodeWithTag("header").requestFocus()
        AppVisibility.lastInput = System.currentTimeMillis() + 1
        settle()
        compose.onNodeWithTag("header").assertIsFocused()
        compose.onNodeWithText("Play").assertIsNotFocused()
    }
}
