package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Search's results go back to their first when the focused one goes (the phone typed on) without the
 * grid being measured from the thread the search ran on. That used to happen whenever the page went on
 * from its search on that thread, as it does under this first version of the test rule (on a TV it goes
 * on on the main thread): measured there while the screen was being measured too, it threw
 * "performMeasureAndLayout called during measure layout", now and then, in SearchScreenTest. Here the
 * two are made to meet every time: the search waits until the screen is mid-measure, and the screen
 * holds its measure while the search goes on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchResultsThreadTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun film(id: String, title: String, year: Int, rating: Double) =
        DriveFile(id, "$title ($year).mkv", "video/x-matroska", 0, 0, null, info = MediaInfo(title, year, rating = rating))

    private fun show(id: String, title: String, year: Int, rating: Double) =
        DriveFile(id, title, DriveFile.FOLDER_MIME, 0, 0, null, info = MediaInfo(title, year, rating = rating, tv = true))

    private val films = listOf(film("m1", "The Last Light", 2023, 7.1), film("m2", "Midnight Harbor", 2024, 7.8))
    private val shows = listOf(show("t1", "The Lighthouse", 2019, 7.9))

    /** The next search off the main thread waits here (until [go]), having said so ([reached]). */
    private val trap = AtomicBoolean(false)
    private val reached = CountDownLatch(1)
    private val go = CountDownLatch(1)
    /** The screen's next measure waits a while, having let the search go on. */
    private val hold = AtomicBoolean(false)

    @Test fun theFirstResultTakesTheFocusWithoutTheGridMeasuredFromTheSearch() {
        val remeasure = mutableIntStateOf(0)
        compose.setContent {
            Layout(content = {
                TvExplorerTheme {
                    SearchScreen(
                        request = SearchRequest(SearchFrom.MENU, null, listen = false, id = 1), block = SearchBlock.NONE,
                        libraries = {
                            if (Looper.myLooper() != Looper.getMainLooper() && trap.compareAndSet(true, false)) {
                                reached.countDown()
                                go.await(5, TimeUnit.SECONDS)
                            }
                            mapOf(DrivePlace.Movies to films, DrivePlace.TvShows to shows)
                        },
                        listings = 1, recent = emptyList(), news = emptyMap(), offline = null, down = false, token = null, voice = true,
                        covered = false, marks = remember { mutableIntStateOf(0) },
                        onListen = {}, heard = MutableSharedFlow(), listenAgain = emptyFlow(),
                        onOpen = {}, onMenu = {}, onPlay = {}, onFix = {},
                    )
                }
            }) { measurables, constraints ->
                remeasure.intValue // read: changing it measures this again
                if (hold.compareAndSet(true, false)) {
                    // Mid-measure: the search goes on meanwhile, on its own thread.
                    go.countDown()
                    Thread.sleep(500)
                }
                val placed = measurables.map { it.measure(constraints) }
                layout(constraints.maxWidth, constraints.maxHeight) { placed.forEach { it.place(0, 0) } }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { RemoteInput.type(null, "the", submit = false) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("The Lighthouse (2019)")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("The Lighthouse (2019)") and hasClickAction()).requestFocus()
        compose.waitForIdle()
        // The phone types on: The Lighthouse no longer matches, The Last Light does. Its search waits...
        trap.set(true)
        compose.runOnIdle { RemoteInput.type(null, "the last", submit = false) }
        compose.waitUntil(5_000) { reached.count == 0L }
        // ...until the screen is mid-measure, and goes on from there.
        hold.set(true)
        compose.runOnIdle { remeasure.intValue++ }
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("The Lighthouse (2019)")).fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasText("The Last Light (2023)") and hasClickAction()).assertIsFocused() }.isSuccess
        }
    }
}
