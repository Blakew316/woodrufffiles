package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.EpisodeMeta
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * A title page's rows (the season tabs, the episodes, a film's collection and More like this) run to the
 * edge of the screen, as Netflix's and the Apple TV app's do, rather than stopping square 48 dp before
 * it with the third card's synopsis cut mid-word. The words and buttons above them keep their margin.
 */
@RunWith(AndroidJUnit4::class)
// Real text measurement: the season tabs' widths come from their words.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TitlePageRowsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSeasonTabsAndTheEpisodesRunToTheScreensEdge() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        FamilySync.setEnabled(context, false)
        val show = DriveFile(
            "rs", "Backyard Science (2019)", DriveFile.FOLDER_MIME, 0, 0, null,
            info = MediaInfo("Backyard Science", 2019, listOf("Family"), 8.1, "Two curious kids and their grandmother.", tmdbId = 4242, tv = true),
        )
        fun season(id: String, n: Int) = DriveFile(id, "Season $n", DriveFile.FOLDER_MIME, 0, 0, null)
        fun episode(id: String, n: Int, name: String) =
            DriveFile(id, "Backyard.Science.S01E0$n.mkv", "video/x-matroska", 0, 0, null, episode = EpisodeMeta(1, n, name, "What happens in it.", 24))
        // Twelve seasons: more tabs than fit across, so their row is as wide as it may be.
        FolderCache.seed("rs", (1..12).map { season("rs-s$it", it) })
        FolderCache.seed("rs-s1", listOf(episode("r1", 1, "Rain Gauges"), episode("r2", 2, "Kitchen Volcano"), episode("r3", 3, "Night Sky"), episode("r4", 4, "Tide Pools")))
        compose.setContent {
            TvExplorerTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    TitlePage(
                        show, null, null, emptyList(), isShow = true, artHeaders = { emptyMap() },
                        onPlay = {}, onPlayEpisode = { _, _, _ -> }, onPlayKey = { _, _ -> }, openLabel = "All folders",
                        onOpen = {}, onChoose = {},
                    )
                }
            }
        }
        settle()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes()
        assertEquals("the season tabs and the episodes", 2, rows.size)
        val dp = compose.density.density
        for (row in rows) {
            assertTrue(
                "a row ends ${(screen - row.boundsInRoot.right) / dp} dp before the screen's edge",
                row.boundsInRoot.right >= screen - 1f,
            )
            // Still in line with the page's words on the left (72 dp in).
            assertEquals(72f, row.boundsInRoot.left / dp, 1f)
        }
    }

    private fun settle() = repeat(20) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }
}
