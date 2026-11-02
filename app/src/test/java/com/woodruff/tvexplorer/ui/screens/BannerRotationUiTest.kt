package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.ui.LocalOpenSidebar
import com.woodruff.tvexplorer.ui.SmoothBringIntoView
import com.woodruff.tvexplorer.ui.motion.BrowseSettle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The featured banner as it really runs in the Overview (browsing-motion §3.2, §9): it moves on 12 s
 * after the last key, never while keys keep coming, and never while it's even partly scrolled away;
 * back fully in view, it stays a whole turn first. (BrowseSettleTest checks the same rules on their
 * own; this checks HeroCarousel's loop that follows them, its scroll watch included.)
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class BannerRotationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val lastInput = AppVisibility.lastInput

    @After fun clocks() {
        BrowseSettle.wall = { System.currentTimeMillis() }
        BrowseSettle.uptime = { android.os.SystemClock.uptimeMillis() }
        AppVisibility.lastInput = lastInput
    }

    /** The two featured titles (the only two with wide art), so the banner goes back and forth. */
    private val featured = listOf("Harbor Lights", "Paper Moon")

    private fun library(): List<DriveFile> {
        val genres = listOf("Drama", "Comedy", "Thriller", "Family", "Sci-Fi", "Western")
        val banner = featured.mapIndexed { i, title ->
            DriveFile("b$i", "$title.mkv", "video/x-matroska", 0, 1_000_000L + i, null,
                info = MediaInfo(title, 2001 + i, listOf("Drama"), rating = 8.0, backdrop = "file:///nowhere/banner$i.jpg"))
        }
        val rest = (0 until 80).map { i ->
            DriveFile("f$i", "Film $i.mkv", "video/x-matroska", 0, i * 1000L, null,
                info = MediaInfo("Film $i", 1990 + i % 30, listOf(genres[i % genres.size]), rating = 5.0 + (i % 40) / 10.0))
        }
        return banner + rest
    }

    /** Real time for the rows worked out off the main thread, a little of the test's clock for frames. */
    private fun settle(steps: Int = 20) = repeat(steps) {
        Thread.sleep(15)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    /**
     * Moves the test's clock on to [at] in short steps, so the banner's waits and fades run as on a
     * TV. (Each step is rounded up to a whole frame: aimed at the time left, it overshoots by less
     * than one frame in all.)
     */
    private fun passTo(at: Long) {
        while (compose.mainClock.currentTime < at) {
            compose.mainClock.advanceTimeBy(minOf(250L, at - compose.mainClock.currentTime))
            compose.waitForIdle()
        }
    }

    private val now get() = compose.mainClock.currentTime

    /** The featured title on the banner (one only, once a fade is done). */
    private fun showing(): String? = featured.singleOrNull { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }

    /** A key press as Woodruff's window counts it (AppVisibility.lastInput), and the key itself. */
    private fun press(key: Key) {
        AppVisibility.lastInput = compose.mainClock.currentTime
        compose.onRoot().performKeyInput { pressKey(key) }
    }

    @Test
    fun theBannerMovesOnOnlyWhenNobodyIsMovingAndItIsWhollyInView() {
        compose.mainClock.autoAdvance = false
        BrowseSettle.wall = { compose.mainClock.currentTime }
        BrowseSettle.uptime = { compose.mainClock.currentTime }
        AppVisibility.lastInput = compose.mainClock.currentTime
        compose.setContent {
            CompositionLocalProvider(LocalOpenSidebar provides {}, LocalBringIntoViewSpec provides SmoothBringIntoView) {
                BackHandler {}
                LibraryRows(
                    library(), null, placeKey = "banner-rotation", isShows = false, canTakeFocus = { true },
                    onChoose = {}, onPlay = {}, onMenu = {}, onFocusArt = {}, marks = remember { mutableIntStateOf(0) },
                )
            }
        }
        settle()
        compose.onNodeWithText("Play").assertIsFocused()
        // The key that opened the screen, just now.
        val opened = now
        AppVisibility.lastInput = opened
        val first = showing()
        assertNotNull("one featured title on the banner", first)
        val second = featured.single { it != first }

        // Nobody pressing anything: the same title for 12 s, then the next (its fade done).
        passTo(opened + 11_700)
        assertEquals("moved on before 12 s", first, showing())
        passTo(opened + 13_200)
        assertEquals(second, showing())

        // A key every 3 s for half a minute (someone choosing): it never moves on.
        var lastKey = now
        repeat(10) {
            lastKey = now
            AppVisibility.lastInput = lastKey
            passTo(lastKey + 3_000)
            assertEquals("moved on while keys were coming", second, showing())
        }
        // 12 s after the last of them, it does.
        passTo(lastKey + 11_700)
        assertEquals(second, showing())
        passTo(lastKey + 13_200)
        assertEquals(first, showing())

        // Down to the first row: the banner is partly scrolled away (still there above the row).
        press(Key.DirectionDown)
        settle(10)
        val atRow = showing()
        assertNotNull("the banner is still partly on screen", atRow)
        // A whole minute without a key: it doesn't move on while it isn't wholly on screen.
        passTo(now + 60_000)
        assertEquals("moved on while partly scrolled away", atRow, showing())

        // Back up to it: wholly in view again (once the column has glided back), it stays a whole
        // turn first, counted from the key and from coming back into view.
        press(Key.DirectionUp)
        val up = now
        settle(10)
        compose.onNodeWithText("Play").assertIsFocused()
        passTo(up + 11_700)
        assertEquals("moved on as it came back into view", atRow, showing())
        passTo(up + 14_000)
        assertNotEquals(atRow, showing())
        assertNotNull(showing())
    }
}
