package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's Overview, worked out off the main thread: its rows and what Continue watching's
 * tiles stand for, the featured titles, the place the focus goes back to, and the progress bars.
 */
class OverviewPageTest {
    private fun film(i: Int) = DriveFile(
        "m$i", "Film $i.mp4", "video/mp4", 0, 0, null, null,
        MediaInfo("Film $i", 2000 + i, listOf(if (i % 2 == 0) "Drama" else "Comedy"), 5.0 + i % 10 * 0.4, backdrop = "https://img/$i.jpg"),
        created = i.toLong(),
    )

    private val films = (0 until 30).map(::film)
    private fun playing(key: String, title: String, upNext: Boolean = false) = RecentItem(key, title, 600_000, 1_200_000, 0, upNext)

    @Test fun continueWatchingComesFirstWithWhatEachTileStandsFor() {
        val recent = listOf(playing("drive:m29", "Film 29.mp4"), playing("drive:m3", "Film 3.mp4", upNext = true))
        val page = overviewPage(films, recent, emptyMap(), { Library.showMatchKey(it) }, day = 2026_300)
        assertEquals("Continue watching" to listOf(films[29], films[3]), page.rows.first())
        // The other rows, as the library makes them for what Continue watching holds.
        assertEquals(Library.overview(films, listOf(films[29], films[3])), page.rows)
        assertEquals("drive:m3", page.playing["m3"]?.key)
        assertTrue(page.playing["m3"]!!.upNext)
        assertEquals(setOf("m29", "m3"), page.playing.keys)
    }

    @Test fun theFeaturedTitlesAreTheDays() {
        val page = overviewPage(films, emptyList(), emptyMap(), { it }, day = 2026_300)
        assertEquals(Library.featured(films, 2026_300), page.featured)
        assertEquals(5, page.featured.size)
        // Nothing being watched: no Continue watching row.
        assertEquals("Recently added", page.rows.first().first)
    }

    @Test fun continueWatchingHoldsTwentyAtMost() {
        val recent = films.map { playing("drive:${it.id}", it.name) }
        val page = overviewPage(films, recent, emptyMap(), { it }, day = 1)
        assertEquals(20, page.rows.first().second.size)
        assertEquals(20, page.playing.size)
    }

    @Test fun focusGoesBackToTheRowAndTitleLeftFrom() {
        val rows = listOf("Recently added" to films.take(6), "Drama" to films.drop(6).take(6))
        assertEquals(1 to 2, restoreAt(rows, "Drama" to "m8"))
        assertEquals(0 to 0, restoreAt(rows, "Recently added" to "m0"))
    }

    @Test fun noPlaceToGoBackToWhenTheRowOrTitleIsGone() {
        val rows = listOf("Recently added" to films.take(6))
        assertNull(restoreAt(rows, null))
        assertNull(restoreAt(rows, "Top rated" to "m1"))
        assertNull(restoreAt(rows, "Recently added" to "m20"))
    }

    @Test fun progressBarsForWhatsPartWayThrough() {
        val recent = listOf(
            RecentItem("drive:a", "A", 300_000, 1_200_000, 0), // a quarter of the way
            RecentItem("drive:b", "B", 1_190_000, 1_200_000, 0), // 99%: finished, no bar
            RecentItem("drive:c", "C", 0, 0, 0, upNext = true), // up next: not started
        )
        assertEquals(mapOf("drive:a" to 0.25f, "drive:c" to 0f), progressByKey(recent))
    }
}
