package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.ui.screens.cutWords
import com.woodruff.tvexplorer.ui.screens.limitWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Big libraries: a library folder lists up to 20,000 entries (it used to stop at 2,000, silently
 * leaving out the titles last in the alphabet), and a listing cut by its limit says so.
 */
class LibraryLimitTest {
    private fun file(n: Int) = DriveFile("id$n", "Film $n.mkv", "video/x-matroska", 0, 0, null)

    /** Pages of [sizes] entries, as Drive sends them (each but the last says there's another). */
    private fun pages(vararg sizes: Int): (String?) -> DrivePage {
        var made = 0
        return { token ->
            val i = token?.toInt() ?: 0
            val page = (0 until sizes[i]).map { file(made++) }
            DrivePage(page, if (i + 1 < sizes.size) "${i + 1}" else null)
        }
    }

    @Test fun aListingUnderTheLimitIsAllOfIt() {
        val listing = pageAll(20_000, pages(1000, 1000, 412))
        assertEquals(2412, listing.files.size)
        assertEquals(0, listing.cutAt)
        assertEquals("id2411", listing.files.last().id)
    }

    @Test fun thePagesStopAtTheLimitAndSaySo() {
        val asked = ArrayList<String?>()
        val source = pages(*IntArray(25) { 1000 })
        val listing = pageAll(20_000) { token -> asked += token; source(token) }
        assertEquals(20_000, listing.files.size)
        assertEquals(20_000, listing.cutAt)
        // Twenty pages asked for, not the twenty-five there are.
        assertEquals(20, asked.size)
        // In Drive's order: the first 20,000.
        assertEquals("id19999", listing.files.last().id)
    }

    @Test fun shortPagesNeverMakeItMoreThanTheLimit() {
        // Drive may send fewer than asked for: the last page read can run past the limit.
        val listing = pageAll(2_000, pages(900, 900, 900, 900))
        assertEquals(2_000, listing.files.size)
        assertEquals(2_000, listing.cutAt)
    }

    @Test fun exactlyTheLimitWithNothingMoreIsntCut() {
        assertEquals(0, pageAll(2_000, pages(1000, 1000)).cutAt)
        // Drive saying there's another page means there's more.
        assertEquals(2_000, pageAll(2_000, pages(1000, 1000, 5)).cutAt)
    }

    @Test fun anEmptyFolderIsAnEmptyListing() {
        val listing = pageAll(20_000, pages(0))
        assertEquals(emptyList<DriveFile>(), listing.files)
        assertEquals(0, listing.cutAt)
    }

    @Test fun librariesListTenTimesWhatTheyUsedTo() {
        assertEquals(20_000, DriveApi.LIBRARY_LIMIT)
        assertEquals(2_000, DriveApi.LIST_LIMIT)
    }

    @Test fun theLineUnderACutLibrarySaysWhatAndWhy() {
        assertEquals(
            "Movies has over 20,000 files, so only the first 20,000 show. Keeping each film in its own folder lets every one show.",
            limitWords("Movies", 20_000, Locale.US),
        )
        assertEquals(
            "TV Shows has over 20,000 files, so only the first 20,000 show. Keeping each show in its own folder lets every one show.",
            limitWords("TV Shows", 20_000, Locale.US),
        )
        // Numbers as the TV writes them.
        assertEquals(true, limitWords("Movies", 20_000, Locale.GERMANY).contains("20.000"))
    }

    @Test fun theBreadcrumbSaysWhichShow() {
        assertNull(cutWords(DrivePlace.Videos(), 0, Locale.US))
        assertEquals("newest 2,000 shown", cutWords(DrivePlace.Videos(), 2_000, Locale.US))
        // Shared with me and folders are in name order.
        assertEquals("first 2,000 shown", cutWords(DrivePlace.SharedWithMe, 2_000, Locale.US))
        assertEquals("first 2,000 shown", cutWords(DrivePlace.Folder("abc", "Old photos"), 2_000, Locale.US))
    }
}
