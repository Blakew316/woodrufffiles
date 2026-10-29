package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** A title page's "More like this" (sorted off the main thread) and where its film resumes (from one read of what's been played). */
class TitleSuggestionsTest {
    private fun film(id: String, genres: List<String>, rating: Double? = null) =
        DriveFile(id, "$id.mkv", "video/x-matroska", 0, 0, null, null, MediaInfo(id, null, genres, rating))

    private val self = film("self", listOf("Action", "Sci-Fi"))
    private val both = film("both", listOf("Sci-Fi", "Action"), 6.0)
    private val actionBest = film("actionBest", listOf("Action"), 8.5)
    private val actionOk = film("actionOk", listOf("Action"), 7.0)
    private val drama = film("drama", listOf("Drama"), 9.0)
    private val unrated = film("unrated", listOf("Sci-Fi"))

    @Test fun mostGenresSharedFirstThenBestRated() =
        assertEquals(
            listOf(both, actionBest, actionOk, unrated),
            moreLikeThis(self, self.info!!.genres, listOf(drama, unrated, actionOk, self, both, actionBest), emptySet()),
        )

    @Test fun notItselfNorWhatsInItsCollectionsRow() =
        assertEquals(listOf(actionBest), moreLikeThis(self, self.info!!.genres, listOf(self, both, actionBest), setOf("both")))

    @Test fun noGenresNothingLikeIt() = assertEquals(emptyList<DriveFile>(), moreLikeThis(self, emptyList(), listOf(both, actionBest), emptySet()))

    @Test fun fourteenAtMost() {
        val many = (0 until 20).map { film("f$it", listOf("Action"), it.toDouble()) }
        val like = moreLikeThis(self, self.info!!.genres, many, emptySet())
        assertEquals(14, like.size)
        assertEquals("f19", like.first().id)
    }

    @Test fun resumesWhereItStoppedUnlessFinished() {
        val recent = listOf(
            RecentItem("drive:a", "A", 1_800_000, 6_000_000, 0),
            RecentItem("drive:b", "B", 5_800_000, 6_000_000, 0), // past 95%: finished, starts over
        )
        assertEquals(1_800_000L, resumeAt(recent, "drive:a"))
        assertEquals(0L, resumeAt(recent, "drive:b"))
        assertEquals(0L, resumeAt(recent, "drive:never"))
    }
}
