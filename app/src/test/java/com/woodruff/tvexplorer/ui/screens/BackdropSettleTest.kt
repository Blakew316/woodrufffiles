package com.woodruff.tvexplorer.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.ui.motion.BrowseSettle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Streaming's backdrop changes only once browsing settles (browsing-motion §3.1, §9): pressing Right
 * every 500 ms across 20 posters never starts its full-screen fade; stopping for 750 ms starts exactly
 * one; moving off a poster and back within the wait changes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class BackdropSettleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val lastInput = AppVisibility.lastInput

    @After fun clocks() {
        BrowseSettle.wall = { System.currentTimeMillis() }
        BrowseSettle.uptime = { android.os.SystemClock.uptimeMillis() }
        AppVisibility.lastInput = lastInput
    }

    @Test
    fun theBackdropChangesOnlyOnceBrowsingSettles() {
        compose.mainClock.autoAdvance = false
        // One clock for the keys, the waits and the test.
        BrowseSettle.wall = { compose.mainClock.currentTime }
        BrowseSettle.uptime = { compose.mainClock.currentTime }
        AppVisibility.lastInput = compose.mainClock.currentTime
        val drawn = LinkedHashSet<String>()
        val art = mutableStateOf<String?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalPosterPainter provides { url -> drawn += url; ColorPainter(Color.DarkGray) }) {
                PosterBackdrop(art, null)
            }
        }
        compose.mainClock.advanceTimeBy(100)
        // Right every 500 ms across 20 posters: never a backdrop change.
        for (i in 0 until 20) {
            AppVisibility.lastInput = compose.mainClock.currentTime
            art.value = "art$i"
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
        assertTrue("changed while browsing: $drawn", drawn.isEmpty())
        // Stopping to look for 750 ms: exactly one change, to the poster looked at.
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertEquals(listOf("art19"), drawn.toList())
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(listOf("art19"), drawn.toList())

        // Over to a poster and back within the wait: nothing changes.
        AppVisibility.lastInput = compose.mainClock.currentTime
        art.value = "art20"
        compose.mainClock.advanceTimeBy(300)
        AppVisibility.lastInput = compose.mainClock.currentTime
        art.value = "art19"
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(listOf("art19"), drawn.toList())
    }
}
