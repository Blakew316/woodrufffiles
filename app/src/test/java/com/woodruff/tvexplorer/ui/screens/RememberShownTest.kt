package com.woodruff.tvexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A library grid's list is worked out off the main thread (L1-5): what shows while it's worked out,
 * that a new filter keeps the last list up until the new one is in, that another library's list never
 * shows, and that coming back to a list already worked out shows it at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RememberShownTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun forget() { lastSorted = null }

    private fun films(prefix: String, n: Int) = (0 until n).map { i ->
        DriveFile("$prefix$i", "${('A' + (i * 7) % 26)}$prefix Film $i.mkv", "video/x-matroska", 0, i * 1000L, null, info = Library.parsed("${('A' + (i * 7) % 26)}$prefix Film $i.mkv", folder = false))
    }

    /** Lets the background sort finish and the result reach the screen. */
    private fun settle() = repeat(10) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        compose.waitForIdle()
    }

    @Test
    fun theGridsListIsSortedOffTheMainThreadKeepingTheLastOneUpMeanwhile() {
        val movies = films("m", 40)
        val shows = films("s", 30)
        var files by mutableStateOf(movies)
        var place by mutableStateOf("Movies")
        var filter by mutableStateOf(LibraryFilter(sort = LibrarySort.TITLE))
        var shown: List<DriveFile>? = emptyList()
        // Holds a sort back on its background thread until the test lets it finish.
        val gate = java.util.concurrent.Semaphore(0)
        val gated = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.setContent {
            val f = filter
            shown = rememberShown(files, place, sorting = true, inputs = f) { list -> if (gated.get()) gate.acquire(); Library.apply(list, f) }
        }
        settle()
        val aToZ = Library.apply(movies, LibraryFilter(sort = LibrarySort.TITLE))
        assertEquals(aToZ, shown)

        // A new sort: the A–Z list stays up while the newest-first one is worked out...
        gated.set(true)
        compose.runOnIdle { filter = LibraryFilter(sort = LibrarySort.RECENT) }
        compose.waitForIdle()
        assertEquals(aToZ, shown)
        // ...then it's there.
        gate.release()
        settle()
        val newest = Library.apply(movies, LibraryFilter(sort = LibrarySort.RECENT))
        assertEquals(newest, shown)
        assertNotEquals(aToZ, shown)

        // Another library: never Movies' titles under TV Shows; nothing until its own are in.
        compose.runOnIdle { files = shows; place = "TV Shows" }
        compose.waitForIdle()
        assertNull(shown)
        gate.release()
        settle()
        assertEquals(Library.apply(shows, LibraryFilter(sort = LibrarySort.RECENT)), shown)
    }

    @Test
    fun aListAlreadyWorkedOutShowsAtOnce() {
        val movies = films("m", 25)
        val filter = LibraryFilter(sort = LibrarySort.TITLE)
        val sorted = Library.apply(movies, filter)
        lastSorted = Sorted("Movies", movies, filter, sorted)
        var shown: List<DriveFile>? = null
        compose.setContent {
            shown = rememberShown(movies, "Movies", sorting = true, inputs = filter) { error("worked out again") }
        }
        compose.waitForIdle()
        assertSame(sorted, shown)
    }

    @Test
    fun theOverviewAndFoldersShowTheListingAsItIs() {
        val movies = films("m", 10)
        var shown: List<DriveFile>? = null
        compose.setContent { shown = rememberShown(movies, "Movies", sorting = false, inputs = Unit) { error("not sorted") } }
        compose.waitForIdle()
        assertSame(movies, shown)
    }

    @Test
    fun unwatchedIsWorkedOutAfreshEachTime() {
        val movies = films("m", 12)
        val filter = LibraryFilter(unwatched = true)
        lastSorted = Sorted("Movies", movies, filter, emptyList())
        var shown: List<DriveFile>? = listOf()
        val gate = java.util.concurrent.Semaphore(0)
        compose.setContent { shown = rememberShown(movies, "Movies", sorting = true, inputs = filter, fresh = true) { gate.acquire(); it.take(3) } }
        compose.waitForIdle()
        // Not the kept copy...
        assertNull(shown)
        gate.release()
        settle()
        // ...but the list as it is now.
        assertEquals(movies.take(3), shown)
    }
}
