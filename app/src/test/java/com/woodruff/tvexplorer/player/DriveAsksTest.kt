package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the TV's diagnostics say about what each film asked of Google Drive (DriveTally, behind
 * DriveAsks): a line when a film closes, one every 10 minutes while it keeps asking, and one at each
 * refusal over Google's limits with the day's totals so far.
 */
class DriveAsksTest {
    private var clock = 1_763_340_000_000L // a moment on Nov 17, 2025 (UTC)
    private val lines = ArrayList<String>()
    private val tally = DriveTally(now = { clock }, say = { lines += it })
    private val film = "drive:abc"

    private fun play(pieces: Int, bytes: Long = 8 * MB) = repeat(pieces) {
        tally.requested(film, bytes)
        tally.received(film, bytes)
    }

    @Test fun aFilmSaysWhatItAskedWhenItCloses() {
        tally.playing(film, "Dune (2021)")
        play(3)
        tally.leave(film)
        tally.finish(film)
        assertEquals(listOf("Drive: Dune (2021): asked 24.0 MB in 3 requests, received 24.0 MB"), lines)
        // Said once: its counts are let go.
        tally.finish(film)
        assertEquals(1, lines.size)
    }

    @Test fun aFilmThatKeepsAskingSaysSoEveryTenMinutes() {
        tally.playing(film, "Dune (2021)")
        play(1)
        clock += 9 * 60_000L
        play(1)
        assertTrue(lines.isEmpty())
        clock += 60_000L
        play(1)
        assertEquals(listOf("Drive: Dune (2021): asked 24.0 MB in 3 requests so far, received 16.0 MB"), lines)
    }

    @Test fun aPlayerHoldingTheFilmAgainKeepsItsCountsGoing() {
        tally.playing(film, "Dune (2021)")
        play(2)
        tally.leave(film)
        // Opened again before its line was due (back from the doorbell, say): one film, one line later.
        tally.playing(film, "Dune (2021)")
        tally.finish(film)
        assertTrue(lines.isEmpty())
    }

    @Test fun aRefusalSaysTheDaysTotalsSoFar() {
        tally.playing(film, "Dune (2021)")
        play(2, 3 * GB / 2)
        tally.requested(film, 8 * MB)
        tally.refused(film, 403, "downloadQuotaExceeded")
        assertEquals(
            listOf("Drive: Dune (2021): Google refused it (403 downloadQuotaExceeded); asked 3.0 GB for it in 3 requests; asked of Google today (UTC): 3.0 GB in 3 requests, received 3.0 GB"),
            lines,
        )
        // The player tries again, and is refused again: said again only a minute later.
        tally.refused(film, 403, "downloadQuotaExceeded")
        assertEquals(1, lines.size)
        clock += 60_000L
        tally.refused(film, 403, "downloadQuotaExceeded")
        assertEquals(2, lines.size)
    }

    @Test fun theDaysTotalsStartAgainAtMidnightUtc() {
        play(2)
        assertEquals(2, tally.today().requests)
        clock = (DriveTally.dayOf(clock) + 1) * 86_400_000L
        assertEquals(0, tally.today().requests)
        tally.requested(null, 100)
        assertEquals(1, tally.today().requests)
        assertEquals(100L, tally.today().asked)
    }

    @Test fun aDaysTotalsKeptOnTheTvComeBackTheSameDayOnly() {
        val day = DriveTally.dayOf(clock)
        val back = DriveTally(now = { clock }, say = {})
        back.restore(day - 1, 5 * GB, 5 * GB, 99)
        assertEquals(0, back.today().requests)
        back.restore(day, 5 * GB, 4 * GB, 99)
        assertEquals(99, back.today().requests)
        assertEquals(5 * GB, back.today().asked)
    }

    @Test fun aFilmNoPlayerHoldsIsSaidOnceItStopsAsking() {
        // A film played on a phone through this TV: no player here closes it.
        tally.named(film, "drive:abc on a phone", replace = false)
        play(2)
        clock += 10 * 60_000L
        tally.requested("drive:other", 1 * MB)
        assertEquals(listOf("Drive: drive:abc on a phone: asked 16.0 MB in 2 requests, received 16.0 MB"), lines)
    }

    @Test fun theReportSaysTheDayAndWhatIsAskingNow() {
        tally.playing(film, "Dune (2021)")
        play(2)
        tally.requested(null, 50 * KB)
        val summary = tally.summary()
        assertEquals("Asked of Google today (UTC): 16.0 MB in 3 requests, received 16.0 MB.", summary[0])
        assertEquals("Now, Dune (2021): asked 16.0 MB in 2 requests, received 16.0 MB.", summary[1])
    }

    @Test fun onlyGooglesLimitsAreQuotaRefusals() {
        assertTrue(DriveAsks.isQuotaRefusal(403, "downloadQuotaExceeded"))
        assertTrue(DriveAsks.isQuotaRefusal(403, "userRateLimitExceeded"))
        assertTrue(DriveAsks.isQuotaRefusal(403, "dailyLimitExceeded"))
        assertTrue(DriveAsks.isQuotaRefusal(429, null))
        assertFalse(DriveAsks.isQuotaRefusal(403, "forbidden"))
        assertFalse(DriveAsks.isQuotaRefusal(403, null))
        assertFalse(DriveAsks.isQuotaRefusal(401, "authError"))
        assertFalse(DriveAsks.isQuotaRefusal(404, "notFound"))
    }

    @Test fun amountsReadTheSameInEveryLanguage() {
        assertEquals("0 B", DriveTally.amount(0))
        assertEquals("12.0 KB", DriveTally.amount(12 * KB))
        assertEquals("8.0 MB", DriveTally.amount(8 * MB))
        assertEquals("3.2 GB", DriveTally.amount(3_435_973_837))
        assertEquals("850 MB", DriveTally.amount(850 * MB))
    }

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024
        const val GB = 1024L * 1024 * 1024
    }
}
