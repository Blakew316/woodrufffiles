package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.cloud.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What's offered after an episode: the next one queued, or the next season's first. */
class UpNextTest {
    private fun folder(id: String, name: String) = DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null)
    private fun video(id: String, name: String) = DriveFile(id, name, "video/x-matroska", 0, 0, null)

    @Test fun nextIsTheOneAfterTheFinishedEpisodesOwnPlace() {
        val queue = listOf("drive:e1", "drive:e2", "drive:e3")
        assertEquals(1, UpNext.indexAfter(queue, "drive:e1"))
        assertEquals(2, UpNext.indexAfter(queue, "drive:e2"))
        assertNull(UpNext.indexAfter(queue, "drive:e3")) // the season's last
        assertNull(UpNext.indexAfter(queue, "drive:elsewhere"))
    }

    private val show = listOf(
        folder("specials", "Specials"), folder("s2", "Season 2"), folder("s1", "Season 1"),
        folder("s10", "Season 10"), folder("bts", "Behind the scenes"), video("trailer", "Trailer.mp4"),
    )

    @Test fun theSeasonIsTheEpisodesFolderElseItsName() {
        assertEquals(2, UpNext.seasonOf(show, "s2", "Show S01E09.mkv")) // the folder it's in wins
        assertEquals(3, UpNext.seasonOf(show, "somewhere else", "Show S03E10.mkv"))
        assertNull(UpNext.seasonOf(show, "bts", "Pilot.mkv"))
    }

    @Test fun theNextSeasonIsTheLowestNumberAbove() {
        assertEquals("s2", UpNext.nextSeason(show, 1)?.id)
        assertEquals("s10", UpNext.nextSeason(show, 2)?.id) // numbers, not names: "Season 10" after "Season 2"
        assertNull(UpNext.nextSeason(show, 10))
        assertEquals("s1", UpNext.nextSeason(show, 0)?.id) // after the specials, season 1
    }

    @Test fun firstEpisodeByNumberNotByName() {
        val season = listOf(
            folder("extras", "Featurettes"), video("e10", "Show S02E10.mkv"),
            video("e2", "Show S02E02.mkv"), video("e1", "Show S02E01 - Pilot.mkv"),
        )
        assertEquals("e1", UpNext.firstEpisode(season, 2)?.id)
        // "Episode 3" style names: the season comes from the folder.
        assertEquals("w1", UpNext.firstEpisode(listOf(video("w3", "Episode 3.mp4"), video("w1", "Episode 1.mp4")), 4)?.id)
    }

    @Test fun noFirstEpisodeWhenNothingLooksLikeOne() {
        assertNull(UpNext.firstEpisode(listOf(video("b", "Behind the scenes.mp4"), folder("f", "S01E01")), 2))
        assertNull(UpNext.firstEpisode(emptyList(), 2))
    }

    @Test fun upNextsPictureIsTheListingsAsTheShowsPageShowsIt() {
        // TMDB's still first, else Drive's own frame of the video, at the size posters ask for.
        val still = "https://image.tmdb.org/t/p/w780/still.jpg"
        assertEquals(still, UpNext.art(video("e2", "Show S01E02.mkv").copy(posterUrl = still, thumbnailLink = "https://lh3.googleusercontent.com/drive-storage/abc=s220")))
        assertEquals("https://lh3.googleusercontent.com/drive-storage/abc=s720", UpNext.art(video("e2", "Show S01E02.mkv").copy(thumbnailLink = "https://lh3.googleusercontent.com/drive-storage/abc=s220")))
        assertNull(UpNext.art(video("e2", "Show S01E02.mkv")))
    }

    @Test fun aDriveEpisodesPictureIsNeverReadOutOfTheEpisode() {
        // Its frame from the film itself was its header, its index and a frame from Drive, for one picture.
        val known = mapOf("drive:e2" to "https://image.tmdb.org/t/p/w780/still.jpg")
        assertEquals(UpNextArt.Picture("https://image.tmdb.org/t/p/w780/still.jpg"), UpNext.artFor("drive:e2", known::get))
        assertEquals(UpNextArt.None, UpNext.artFor("drive:e3", known::get))
        // A file on this TV costs nothing to read: a frame from it, as before.
        assertEquals(UpNextArt.Frame, UpNext.artFor("/storage/USB/Shows/Show S01E02.mkv", known::get))
        assertEquals(UpNextArt.None, UpNext.artFor("https://example.com/clip.mp4", known::get))
    }

    @Test fun upNextAsksStillWatchingAfterAnHourAndAHalfWithoutAKey() {
        // As the LG asks: 90 minutes without a key on the remote or the phone, and 5 minutes to answer.
        assertFalse(UpNext.stillWatching(0))
        assertFalse(UpNext.stillWatching(90 * 60_000L - 1))
        assertTrue(UpNext.stillWatching(90 * 60_000L))
        assertTrue(UpNext.stillWatching(10 * 3_600_000L))
        assertEquals(5 * 60_000L, UpNext.ASK_FOR_MS)
    }

    @Test fun playNextInTheMenuCountsAsWatchedOnlyInTheCredits() {
        val hour = 3_600_000L
        // In its last 40 s or 5 %: as the TV's own Next Episode, watched.
        assertTrue(UpNext.nextCountsAsWatched(hour - 40_000, hour))
        assertTrue(UpNext.nextCountsAsWatched(hour - 1_000, hour))
        assertTrue(UpNext.nextCountsAsWatched((hour * 0.95).toLong(), hour))
        // Before that: the remote's Next, which keeps the place (at minute 5 it was marked watched on every TV).
        assertFalse(UpNext.nextCountsAsWatched(5 * 60_000, hour))
        assertFalse(UpNext.nextCountsAsWatched((hour * 0.94).toLong(), hour))
        // A short one: its last 40 s are more than its last 5 %.
        assertTrue(UpNext.nextCountsAsWatched(80_000, 120_000))
        assertFalse(UpNext.nextCountsAsWatched(79_000, 120_000))
        // Its length not known yet: never watched.
        assertFalse(UpNext.nextCountsAsWatched(10_000, androidx.media3.common.C.TIME_UNSET))
        assertFalse(UpNext.nextCountsAsWatched(0, 0))
    }
}
