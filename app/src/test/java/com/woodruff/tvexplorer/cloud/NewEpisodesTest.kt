package com.woodruff.tvexplorer.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What counts as a new episode, what a show's poster says about them, and how a new video finds its show. */
class NewEpisodesTest {
    private val day = 24 * 60 * 60_000L
    private val now = 1_800_000_000_000L

    private fun ep(id: String, season: Int?, number: Int?, daysAgo: Int = 1, seasonId: String? = "s$season") =
        FreshEpisode(id, "Show S${season}E$number.mkv", seasonId, seasonId?.let { "Season $season" }, now - daysAgo * day, season, number)

    @Test fun newIsLatelyAddedNotWatchedAndNotStarted() {
        assertTrue(NewEpisodes.isNew(now - day, now, watched = false, progress = null))
        assertTrue(NewEpisodes.isNew(now - 29 * day, now, watched = false, progress = 0.01f))
        // Watched on any TV, or started (2 % in), it isn't.
        assertFalse(NewEpisodes.isNew(now - day, now, watched = true, progress = null))
        assertFalse(NewEpisodes.isNew(now - day, now, watched = false, progress = 0.02f))
        // Older than 30 days, or a time that can't be right (none, or ahead of the clock).
        assertFalse(NewEpisodes.isNew(now - 30 * day, now, watched = false, progress = null))
        assertFalse(NewEpisodes.isNew(0, now, watched = false, progress = null))
        assertFalse(NewEpisodes.isNew(now + day, now, watched = false, progress = null))
    }

    @Test fun captions() {
        assertEquals("New · S3 E2", NewEpisodes.caption(listOf(ep("a", 3, 2))))
        assertEquals("New episode", NewEpisodes.caption(listOf(ep("a", null, null))))
        assertEquals("New special", NewEpisodes.caption(listOf(ep("a", 0, 4))))
        // A whole season from its first episode.
        assertEquals("New season", NewEpisodes.caption(listOf(ep("a", 4, 1), ep("b", 4, 2), ep("c", 4, 3))))
        // Several, not from a season's start, or across seasons.
        assertEquals("2 new episodes", NewEpisodes.caption(listOf(ep("a", 3, 2), ep("b", 3, 3))))
        assertEquals("3 new episodes", NewEpisodes.caption(listOf(ep("a", 3, 1), ep("b", 3, 2), ep("c", 4, 1))))
        assertEquals("2 new episodes", NewEpisodes.caption(listOf(ep("a", 3, 1), ep("b", null, null))))
        assertEquals("99 new episodes", NewEpisodes.caption((2..100).map { ep("e$it", 1, it) }))
        assertEquals("99+ new episodes", NewEpisodes.caption((2..101).map { ep("e$it", 1, it) }))
    }

    @Test fun watchingOrderIsSeasonThenEpisodeSpecialsAndUnnumberedLast() {
        val eps = listOf(ep("x", null, null), ep("sp", 0, 1), ep("b", 4, 1), ep("a", 3, 9), ep("c", 4, 2))
        assertEquals(listOf("a", "b", "c", "sp", "x"), eps.sortedWith(NewEpisodes.watchingOrder).map { it.id })
    }

    @Test fun showsWithNewEpisodesAndWhetherTheirPostersSaySo() {
        val fresh = mapOf(
            "old" to listOf(ep("o2", 3, 3, daysAgo = 2), ep("o1", 3, 2, daysAgo = 5), ep("watched", 3, 1, daysAgo = 6)),
            "brandNew" to listOf(ep("n1", 1, 1, daysAgo = 3)),
            "seen" to listOf(ep("s1", 2, 1, daysAgo = 1)),
            "gone" to listOf(ep("g1", 1, 1)), // a show TV Shows doesn't list any more
        )
        val added = mapOf("old" to now - 400 * day, "brandNew" to now - 4 * day, "seen" to now - 90 * day)
        val seen: (String) -> Boolean = { it == "drive:watched" || it == "drive:s1" }
        val shows = NewEpisodes.shows(fresh, added, now, watched = seen, progress = { null })
        assertEquals(setOf("old", "brandNew"), shows.keys)
        val old = shows.getValue("old")
        // In watching order, the one watched left out; placed by its newest new episode.
        assertEquals(listOf("o1", "o2"), old.episodes.map { it.id })
        assertEquals("o1", old.first.id)
        assertEquals(now - 2 * day, old.at)
        assertTrue(old.announced)
        assertEquals("2 new episodes", old.caption)
        // Added whole lately: a new poster, no words about new episodes.
        assertFalse(shows.getValue("brandNew").announced)
        // Started on another TV: not new any more.
        assertNull(NewEpisodes.shows(fresh, added, now, seen, { if (it == "drive:o1" || it == "drive:o2") 0.3f else null })["old"])
    }

    @Test fun recentlyAddedCountsAShowFromItsNewestEpisode() {
        val show = DriveFile("old", "Old Show", DriveFile.FOLDER_MIME, 0, 0, null, created = now - 400 * day)
        val film = DriveFile("film", "Film.mkv", "video/x-matroska", 0, 0, null, created = now - 3 * day)
        val shows = mapOf("old" to NewShow("old", listOf(ep("o1", 3, 2)), now - day, announced = true))
        assertEquals(now - day, NewEpisodes.addedAt(show, shows))
        assertEquals(now - 3 * day, NewEpisodes.addedAt(film, shows))
        assertEquals(listOf("old", "film"), listOf(film, show).sortedByDescending { NewEpisodes.addedAt(it, shows) }.map { it.id })
    }

    /** A Drive of folders, for the walk: folder id to its name and parent; every lookup counted. */
    private class FakeDrive(val folders: Map<String, FolderFacts>) {
        val asked = ArrayList<String>()
        suspend fun facts(id: String): FolderFacts {
            asked += id
            return folders[id] ?: throw IOException("no such folder: $id")
        }
    }

    private val drive = FakeDrive(
        mapOf(
            "season2" to FolderFacts("Season 2", "show"),
            "disc1" to FolderFacts("Disc 1", "season3"),
            "season3" to FolderFacts("Season 3", "show"),
            "phone" to FolderFacts("Phone uploads", "root"),
            "root" to FolderFacts("My Drive", null),
            "a" to FolderFacts("A", "b"), "b" to FolderFacts("B", "c"), "c" to FolderFacts("C", "d"), "d" to FolderFacts("D", "show"),
        ),
    )

    private fun video(id: String, name: String, parent: String?, daysAgo: Int = 1) = RecentVideo(id, name, parent, now - daysAgo * day)

    @Test fun eachNewVideoFindsItsShowAndSeason() = runBlocking {
        val known = HashMap<String, Placement?>()
        val found = NewEpisodes.placeVideos(
            listOf(
                video("e1", "Episode 4.mkv", "season2"), // its number from its name, its season from its folder's
                video("e2", "Show S02E05.mkv", "season2"),
                video("e3", "Show S03E01.mkv", "disc1"), // a folder deeper: still season 3
                video("e4", "Show S01E09.mkv", "show"), // in the show's own folder
                video("clip", "clip.mp4", "phone"), // under no show
                video("film", "Film.mkv", "movies"), // in a library's own folder: stops there
                video("shared", "x.mp4", null), // shared with the family, in no folder of theirs
            ),
            shows = setOf("show"), stops = setOf("movies", "tvroot"), known = known, facts = drive::facts,
        )
        val eps = found.getValue("show")
        assertEquals(listOf("e1", "e2", "e3", "e4"), eps.map { it.id })
        assertEquals(FreshEpisode("e1", "Episode 4.mkv", "season2", "Season 2", now - day, 2, 4), eps[0])
        assertEquals("season3" to "Season 3", eps[2].seasonId to eps[2].seasonName)
        assertEquals(3 to 1, eps[2].season to eps[2].number)
        assertEquals(null to null, eps[3].seasonId to eps[3].seasonName)
        assertEquals(setOf("show"), found.keys)
        // Season 2's episodes shared one lookup; the libraries' own folder was never looked up.
        assertEquals(1, drive.asked.count { it == "season2" })
        assertFalse("movies" in drive.asked)
        // What was learned is kept: the next refresh walks nothing it's seen.
        assertEquals(Placement("show", "season2", "Season 2"), known["season2"])
        assertEquals(Placement("show", "season3", "Season 3"), known["disc1"])
        assertNull(known["phone"])
        assertTrue(known.containsKey("phone"))
        drive.asked.clear()
        NewEpisodes.placeVideos(listOf(video("e5", "Show S02E06.mkv", "season2"), video("clip2", "y.mp4", "phone")), setOf("show"), emptySet(), known, drive::facts)
        assertTrue(drive.asked.isEmpty())
    }

    @Test fun aWalkCutShortIsntRememberedAsLeadingNowhere() = runBlocking {
        val known = HashMap<String, Placement?>()
        // a → b → c → d → show: deeper than a walk goes.
        val found = NewEpisodes.placeVideos(listOf(video("deep", "Show S01E01.mkv", "a")), setOf("show"), emptySet(), known, drive::facts)
        assertTrue(found.isEmpty())
        assertFalse(known.containsKey("a"))
        // From closer by, the same folders lead to the show.
        val closer = NewEpisodes.placeVideos(listOf(video("near", "Show S01E02.mkv", "c")), setOf("show"), emptySet(), known, drive::facts)
        assertEquals(listOf("near"), closer.getValue("show").map { it.id })
        assertEquals(Placement("show", "d", "D"), known["c"])
    }

    @Test fun offlineTheWalkStopsAndKeepsWhatItLearned() {
        val known = HashMap<String, Placement?>()
        val flaky = FakeDrive(mapOf("season2" to FolderFacts("Season 2", "show")))
        var threw = false
        try {
            runBlocking {
                NewEpisodes.placeVideos(
                    listOf(video("e1", "Show S02E01.mkv", "season2"), video("e2", "x.mkv", "unknown")),
                    setOf("show"), emptySet(), known, flaky::facts,
                )
            }
        } catch (e: IOException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(Placement("show", "season2", "Season 2"), known["season2"])
        assertFalse(known.containsKey("unknown"))
    }
}
