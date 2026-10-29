package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Progress shared between TVs: per key the newest wins, removals and unmarks included. */
class FamilySyncTest {
    private fun item(key: String, at: Long, pos: Long = 600_000, upNext: Boolean = false) =
        RecentItem(key, "Title $key", pos, 2_400_000, at, upNext)

    private fun from(vararg items: RecentItem) = SyncData(recent = items.toList())

    @Test fun newerProgressFromAnotherTvWins() {
        val (list, _) = FamilyMerge.recent(listOf(item("drive:a", 100, pos = 60_000)), emptyMap(), listOf(from(item("drive:a", 200, pos = 900_000))))
        assertEquals(listOf(item("drive:a", 200, pos = 900_000)), list)
    }

    @Test fun olderProgressFromAnotherTvLoses() {
        val (list, _) = FamilyMerge.recent(listOf(item("drive:a", 300, pos = 60_000)), emptyMap(), listOf(from(item("drive:a", 200, pos = 900_000))))
        assertEquals(listOf(item("drive:a", 300, pos = 60_000)), list)
    }

    @Test fun aRemovalOnAnotherTvTakesItAway() {
        val (list, gone) = FamilyMerge.recent(listOf(item("drive:a", 100)), emptyMap(), listOf(SyncData(gone = mapOf("drive:a" to 150L))))
        assertTrue(list.isEmpty())
        assertEquals(mapOf("drive:a" to 150L), gone)
    }

    @Test fun watchingAgainAfterARemovalBringsItBack() {
        val (list, gone) = FamilyMerge.recent(emptyList(), mapOf("drive:a" to 100L), listOf(from(item("drive:a", 200))))
        assertEquals(listOf("drive:a"), list.map { it.key })
        assertTrue(gone.isEmpty())
    }

    @Test fun aRemovalAsNewAsTheProgressWins() {
        val (list, _) = FamilyMerge.recent(listOf(item("drive:a", 100)), emptyMap(), listOf(SyncData(gone = mapOf("drive:a" to 100L))))
        assertTrue(list.isEmpty())
    }

    @Test fun keepsTheThirtyNewestNewestFirst() {
        val mine = (1..20).map { item("drive:m$it", it * 10L) }
        val theirs = (1..20).map { item("drive:t$it", it * 10L + 5) }
        val (list, _) = FamilyMerge.recent(mine, emptyMap(), listOf(SyncData(recent = theirs)))
        assertEquals(30, list.size)
        assertEquals("drive:t20", list.first().key)
        assertEquals(list.sortedByDescending { it.updatedAt }, list)
        // The ten oldest of the forty are left out.
        assertFalse(list.any { it.key == "drive:m5" || it.key == "drive:t5" })
        assertTrue(list.any { it.key == "drive:m6" } && list.any { it.key == "drive:t6" })
    }

    @Test fun upNextTravelsWithItsEntry() {
        val (list, _) = FamilyMerge.recent(emptyList(), emptyMap(), listOf(from(item("drive:e5", 300, pos = 0, upNext = true))))
        assertTrue(list.single().upNext)
    }

    @Test fun thisTvsOwnFilesStayInPlace() {
        val usb = item("/storage/usb/Movies/Film.mkv", 100)
        val (list, _) = FamilyMerge.recent(listOf(usb), emptyMap(), listOf(from(item("drive:b", 200))))
        assertEquals(listOf("drive:b", usb.key), list.map { it.key })
    }

    @Test fun checkmarksJoinUp() {
        // One from before times were kept (0) and a new one from another TV.
        val (on, off) = FamilyMerge.watched(mapOf("drive:a" to 0L), emptyMap(), listOf(SyncData(watched = mapOf("drive:b" to 100L))))
        assertEquals(setOf("drive:a", "drive:b"), on.keys)
        assertTrue(off.isEmpty())
    }

    @Test fun anUnmarkOnAnotherTvTakesTheCheckOff() {
        val (on, off) = FamilyMerge.watched(mapOf("drive:a" to 0L), emptyMap(), listOf(SyncData(unwatched = mapOf("drive:a" to 50L))))
        assertTrue(on.isEmpty())
        assertEquals(mapOf("drive:a" to 50L), off)
    }

    @Test fun markingAgainAfterAnUnmarkWins() {
        val (on, off) = FamilyMerge.watched(emptyMap(), mapOf("drive:a" to 50L), listOf(SyncData(watched = mapOf("drive:a" to 80L))))
        assertEquals(mapOf("drive:a" to 80L), on)
        assertTrue(off.isEmpty())
    }

    @Test fun theNewestShowChoiceWinsAndTakingOneOffCounts() {
        val mine = mapOf("i:the office" to Stamped("83000", 100), "a:the office" to Stamped("en", 100))
        val theirs = SyncData(shows = mapOf("i:the office" to Stamped(null, 200), "a:the office" to Stamped("ja", 50), "t:the office" to Stamped("off", 10)))
        val merged = FamilyMerge.shows(mine, listOf(theirs))
        assertNull(merged["i:the office"]?.value)
        assertEquals(Stamped("en", 100), merged["a:the office"])
        assertEquals(Stamped("off", 10), merged["t:the office"])
    }

    @Test fun choicesFromBeforeTimesKeepThisTvs() {
        val merged = FamilyMerge.shows(mapOf("a:x" to Stamped("en", 0)), listOf(SyncData(shows = mapOf("a:x" to Stamped("fr", 0)))))
        assertEquals("en", merged["a:x"]?.value)
    }

    @Test fun twoTvsEndUpTheSame() {
        val a = SyncData(
            recent = listOf(item("drive:y", 300), item("drive:x", 100)), gone = mapOf("drive:z" to 250L),
            watched = mapOf("drive:w" to 10L), shows = mapOf("i:s" to Stamped("5000", 20)),
        )
        val b = SyncData(
            recent = listOf(item("drive:x", 400, pos = 1_200_000), item("drive:z", 200)),
            unwatched = mapOf("drive:w" to 30L), shows = mapOf("i:s" to Stamped("7000", 40)),
        )
        val onA = FamilyMerge.merge(a, listOf(b))
        assertEquals(onA, FamilyMerge.merge(b, listOf(a)))
        assertEquals(listOf("drive:x", "drive:y"), onA.recent.map { it.key })
        assertEquals(1_200_000L, onA.recent.first().positionMs)
        assertEquals(mapOf("drive:z" to 250L), onA.gone)
        assertTrue(onA.watched.isEmpty())
        assertEquals("7000", onA.shows["i:s"]?.value)
        // Merging the same file again changes nothing.
        assertEquals(onA, FamilyMerge.merge(onA, listOf(b)))
    }

    @Test fun newestKeepsTheMostRecent() {
        val times = (1..10).associate { "drive:$it" to it.toLong() }
        assertEquals(setOf("drive:8", "drive:9", "drive:10"), FamilyMerge.newest(times, 3).keys)
        assertEquals(times, FamilyMerge.newest(times, 10))
    }

    @Test fun removalsAreCapped() {
        val gone = (1..250).associate { "drive:$it" to it.toLong() }
        val (_, kept) = FamilyMerge.recent(emptyList(), gone, emptyList())
        assertEquals(200, kept.size)
        assertTrue("drive:250" in kept && "drive:50" !in kept)
    }
}
