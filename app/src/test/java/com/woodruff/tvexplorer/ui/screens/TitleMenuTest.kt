package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.cloud.NewShow
import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title menu's rules (title-access §3.3): its title, its line, and which rows it has where. */
class TitleMenuTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L

    private fun film(id: String, title: String, year: Int? = null) =
        DriveFile(id, "$title.mkv", "video/x-matroska", 0, 0, null, info = MediaInfo(title, year))

    private fun folder(id: String, name: String, info: MediaInfo? = null) = DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null, info = info)

    private fun video(id: String, name: String, size: Long = 0) = DriveFile(id, name, "video/x-matroska", size, 0, null)

    private fun played(key: String, title: String, pos: Long, dur: Long, upNext: Boolean = false) = RecentItem(key, title, pos, dur, 0, upNext)

    private val harbor = film("m1", "Midnight Harbor", 2024)
    private val show = folder("bs", "Backyard Science (2019)", MediaInfo("Backyard Science", 2019, tv = true))
    private val season1 = folder("s1", "Season 1")
    private val season2 = folder("s2", "Season 2")
    private val s1e1 = video("e11", "Backyard.Science.S01E01.mkv")
    private val s1e2 = video("e12", "Backyard.Science.S01E02.mkv")
    private val s2e4 = video("e24", "Backyard.Science.S02E04.mkv")
    private val s2e5 = video("e25", "Backyard.Science.S02E05.mkv")
    private val listings = mapOf("bs" to listOf(season1, season2), "s1" to listOf(s1e1, s1e2), "s2" to listOf(s2e4, s2e5))

    private fun facts(
        target: MenuFor,
        recent: List<RecentItem> = emptyList(),
        inside: List<DriveFile>? = target.file?.let { listings[it.id] },
        listing: (String) -> List<DriveFile>? = { listings[it] },
        watched: Set<String> = emptySet(),
        news: NewShow? = null,
        linked: Boolean = false,
        entry: DriveFile? = target.file,
        place: DrivePlace? = target.place,
    ) = TitleFacts(target, entry, place, recent, emptyMap(), inside, listing, { it in watched }, news, now, linked)

    private fun labels(m: TitleMenuModel) = m.rows.map { it.label() }

    @Test fun aFilmNotStarted() {
        val m = titleMenu(facts(MenuFor(DrivePlace.Movies, harbor, MenuPlace.LIBRARY)))
        assertEquals("Midnight Harbor (2024)", m.title)
        assertEquals("Movie", m.subtitle)
        assertEquals(listOf("Play", "Mark as watched", "Download to this TV"), labels(m))
    }

    @Test fun aFilmPartWayWithLinkedTvs() {
        // 1h 05m of 2h 24m: 45 %.
        val recent = listOf(played("drive:m1", "Midnight Harbor (2024).mkv", 3_900_000, 8_640_000))
        val m = titleMenu(facts(MenuFor(DrivePlace.Movies, harbor, MenuPlace.LIBRARY), recent = recent, linked = true))
        assertEquals("45% watched", m.subtitle)
        assertEquals(listOf("Resume from 1h 05m", "Start over", "Watch together on all TVs", "Mark as watched", "Download to this TV"), labels(m))
        val together = m.rows[2] as TitleAction.Together
        assertEquals("drive:m1", together.key)
        assertEquals(3_900_000L, together.positionMs)
        // A ✓ on it: the row takes it off.
        val seen = titleMenu(facts(MenuFor(DrivePlace.Movies, harbor, MenuPlace.LIBRARY), watched = setOf("drive:m1")))
        assertTrue("Mark as unwatched" in labels(seen))
    }

    @Test fun aFilmKeptInItsFolderOnceItsListingIsKnown() {
        val box = folder("f1", "Neon Alley (2024)")
        val inside = listOf(video("v1", "Neon.Alley.2024.mkv", size = 4_000), video("v2", "trailer.mkv", size = 100))
        val known = titleMenu(facts(MenuFor(DrivePlace.Movies, box, MenuPlace.LIBRARY), inside = inside))
        assertEquals(listOf("Play", "Mark as watched"), labels(known))
        assertEquals("drive:v1", (known.rows[1] as TitleAction.Mark).key)
        // Never listed: its page instead (it lists the folder), and no ✓ row (none is added later).
        val unknown = titleMenu(facts(MenuFor(DrivePlace.Movies, box, MenuPlace.LIBRARY), inside = null))
        assertEquals(listOf("Details"), labels(unknown))
        assertEquals("Movie", unknown.subtitle)
    }

    @Test fun aShowGoesOnWithItsEpisode() {
        val recent = listOf(played("drive:e24", "Backyard.Science.S02E04.mkv", 648_000, 1_440_000))
        val m = titleMenu(facts(MenuFor(DrivePlace.TvShows, show, MenuPlace.LIBRARY), recent = recent))
        assertEquals("Backyard Science (2019)", m.title)
        assertEquals("S2 E4 · 45% watched", m.subtitle)
        assertEquals(listOf("Resume S2 E4", "Play from the start"), labels(m))
        // Its season queued after it (listed in memory), and S1 E1 for "from the start".
        val goOn = (m.rows[0] as TitleAction.Play).play as MainPlay.GoOn
        assertEquals(listOf("e24", "e25"), goOn.season?.videos?.map { it.id })
        assertEquals("e11", ((m.rows[1] as TitleAction.Restart).play as MainPlay.First).episode.id)
    }

    @Test fun aShowWithAnEpisodeUpNext() {
        val recent = listOf(played("drive:e25", "Backyard.Science.S02E05.mkv", 0, 0, upNext = true))
        val m = titleMenu(facts(MenuFor(DrivePlace.TvShows, show, MenuPlace.SEARCH), recent = recent))
        assertEquals("Next · S2 E5", m.subtitle)
        assertEquals("Play S2 E5", labels(m).first())
    }

    @Test fun aShowWithNewEpisodesPlaysTheFirstOfThem() {
        val fresh = listOf(
            FreshEpisode("e32", "Backyard.Science.S03E02.mkv", "s3", "Season 3", now - 2 * day, 3, 2),
            FreshEpisode("e33", "Backyard.Science.S03E03.mkv", "s3", "Season 3", now - day, 3, 3),
        )
        val news = NewShow("bs", fresh, now - day, announced = true)
        val m = titleMenu(facts(MenuFor(DrivePlace.TvShows, show, MenuPlace.HOME_ADDED), news = news))
        assertEquals("2 new episodes", m.subtitle)
        assertEquals(listOf("Play S3 E2", "Play from the start"), labels(m))
        // Watched on another TV: not new any more, so the next new one.
        val after = titleMenu(facts(MenuFor(DrivePlace.TvShows, show, MenuPlace.HOME_ADDED), news = news, watched = setOf("drive:e32")))
        assertEquals("Play S3 E3", labels(after).first())
    }

    @Test fun aShowFromItsFirstEpisodeOrItsPage() {
        val first = titleMenu(facts(MenuFor(DrivePlace.TvShows, show, MenuPlace.LIBRARY)))
        assertEquals("TV show", first.subtitle)
        assertEquals(listOf("Play S1 E1"), labels(first))
        // Nothing of it listed in memory: its page, which lists it.
        val unknown = titleMenu(facts(MenuFor(DrivePlace.TvShows, show, MenuPlace.LIBRARY), inside = null))
        assertEquals(listOf("Episodes"), labels(unknown))
    }

    @Test fun homeResumeTilesOfferThePageAndComeOff() {
        val tile = played("drive:m1", "Midnight Harbor (2024).mkv", 3_900_000, 8_640_000)
        val m = titleMenu(facts(MenuFor(null, null, MenuPlace.HOME_RESUME, tile), recent = listOf(tile), linked = true, entry = harbor, place = DrivePlace.Movies))
        assertEquals("Midnight Harbor (2024)", m.title)
        assertEquals("45% watched", m.subtitle)
        assertEquals(
            listOf("Resume from 1h 05m", "Start over", "Details", "Watch together on all TVs", "Mark as watched", "Remove from Continue watching"),
            labels(m),
        )
        assertTrue(m.rows.size <= 6)
        // An episode's tile: its show's page ("Episodes"); the show's first episode known, from the start too.
        val ep = played("drive:e24", "Backyard.Science.S02E04.mkv", 648_000, 1_440_000)
        val e = titleMenu(facts(MenuFor(null, null, MenuPlace.HOME_RESUME, ep), recent = listOf(ep), entry = show, place = DrivePlace.TvShows, inside = listings["bs"]))
        assertEquals("S2 E4 · 45% watched", e.subtitle)
        assertEquals(listOf("Resume S2 E4", "Play from the start", "Episodes", "Mark as watched", "Remove from Continue watching"), labels(e))
        assertEquals(DrivePlace.TvShows, e.place)
        assertEquals("bs", e.entry?.id)
    }

    @Test fun aTileWhoseTitleIsntInTheLibraries() {
        val tile = played("/storage/usb/Holiday 2023.mp4", "Holiday 2023.mp4", 30_000, 600_000)
        val m = titleMenu(facts(MenuFor(null, null, MenuPlace.HOME_RESUME, tile), recent = listOf(tile), linked = true, entry = null, place = null, inside = null))
        assertEquals("Holiday 2023", m.title)
        // Not a Drive video: not on the other TVs; no page to open.
        assertEquals(listOf("Play", "Mark as watched", "Remove from Continue watching"), labels(m))
    }

    @Test fun streamingsContinueWatchingRow() {
        val tile = played("drive:m1", "Midnight Harbor (2024).mkv", 3_900_000, 8_640_000)
        val m = titleMenu(facts(MenuFor(DrivePlace.Movies, harbor, MenuPlace.CONTINUE_ROW, tile), recent = listOf(tile), linked = true))
        assertEquals(
            listOf("Resume from 1h 05m", "Start over", "Watch together on all TVs", "Mark as watched", "Download to this TV", "Remove from Continue watching"),
            labels(m),
        )
        assertEquals(6, m.rows.size)
    }

    @Test fun nothingIsDownloadedFromHome() {
        val m = titleMenu(facts(MenuFor(DrivePlace.Movies, harbor, MenuPlace.HOME_ADDED)))
        assertEquals(listOf("Play", "Mark as watched"), labels(m))
    }

    @Test fun withoutWhatItNeedsTheMenuOpensWithThePage() {
        val tile = played("drive:m1", "Midnight Harbor (2024).mkv", 3_900_000, 8_640_000)
        assertEquals(
            listOf("Resume from 1h 05m", "Details", "Remove from Continue watching"),
            labels(quickTitleMenu(MenuFor(DrivePlace.Movies, harbor, MenuPlace.HOME_RESUME, tile), null)),
        )
        val q = quickTitleMenu(MenuFor(DrivePlace.Movies, harbor, MenuPlace.LIBRARY), null)
        assertEquals(listOf("Details", "Download to this TV"), labels(q))
        assertEquals("Movie", q.subtitle)
        assertEquals("TV show", quickTitleMenu(MenuFor(DrivePlace.TvShows, show, MenuPlace.SEARCH), null).subtitle)
    }

    @Test fun theMainButtonsWords() {
        val f = film("x", "X")
        assertEquals("Play", mainPlayLabel(MainPlay.Film(f, 59_000)))
        assertEquals("Resume from 1m", mainPlayLabel(MainPlay.Film(f, 61_000)))
        assertEquals("Play next episode", mainPlayLabel(MainPlay.GoOn(played("drive:x", "Pilot.mkv", 0, 0, upNext = true))))
        assertEquals("Resume", mainPlayLabel(MainPlay.GoOn(played("drive:x", "Pilot.mkv", 60_000, 600_000))))
        assertEquals("Play new episode", mainPlayLabel(MainPlay.New(FreshEpisode("x", "Pilot.mkv", null, null, now))))
        assertEquals("Play first episode", mainPlayLabel(MainPlay.First(video("x", "Pilot.mkv"), emptyList())))
    }

    @Test fun aShowsMainPlayFollowsTheTitlePagesOrder() {
        val goOn = MainPlay.GoOn(played("drive:e24", "S02E04.mkv", 600_000, 1_200_000))
        val new = MainPlay.New(FreshEpisode("e32", "S03E02.mkv", null, null, now, 3, 2))
        assertEquals(goOn, showMainPlay(goOn, new, s1e1, listOf(s1e1)))
        assertEquals(new, showMainPlay(null, new, s1e1, listOf(s1e1)))
        // A new episode that's the first anyway: the show starts at S1 E1, as any does.
        val firstIsNew = MainPlay.New(FreshEpisode("e11", "S01E01.mkv", null, null, now, 1, 1))
        assertEquals(MainPlay.First(s1e1, listOf(s1e1)), showMainPlay(null, firstIsNew, s1e1, listOf(s1e1)))
        assertNull(showMainPlay(null, null, null, emptyList()))
    }

    @Test fun anEpisodeMenusLine() {
        assertEquals("Watched", episodeMenuSubtitle(true, null))
        assertEquals("45% watched", episodeMenuSubtitle(false, played("drive:e", "S01E01.mkv", 540_000, 1_200_000)))
        assertNull(episodeMenuSubtitle(false, played("drive:e", "S01E01.mkv", 5_000, 1_200_000)))
        assertNull(episodeMenuSubtitle(false, null))
    }
}
