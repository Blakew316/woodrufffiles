package com.woodruff.tvexplorer.player

import androidx.media3.common.C
import com.woodruff.tvexplorer.nowplaying.PlayerCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the phone's ↺10, 30↻ and Next do to Woodruff's own player, and what the phone is told about what plays. */
class PhoneControlsTest {
    private val hour = 3_600_000L

    private fun step(cmd: PlayerCommand, at: Long, of: Long = hour, seekable: Boolean = true, hasNext: Boolean = false, upNext: Boolean = false) =
        phoneStep(cmd, at, of, seekable, hasNext, upNext)

    @Test fun skipsLandTenBackAndThirtyAheadOfWhereTheFilmIs() {
        assertEquals(PhoneStep.Seek(1_190_000), step(PlayerCommand.BACK_10, 1_200_000))
        assertEquals(PhoneStep.Seek(1_230_000), step(PlayerCommand.FORWARD_30, 1_200_000))
    }

    @Test fun skipsNeverLandBeforeTheStartOrPastTheEnd() {
        assertEquals(PhoneStep.Seek(0), step(PlayerCommand.BACK_10, 4_000))
        assertEquals(PhoneStep.Seek(hour), step(PlayerCommand.FORWARD_30, hour - 12_000))
        // Its length not known yet: no end to stop at.
        assertEquals(PhoneStep.Seek(95_000), step(PlayerCommand.FORWARD_30, 65_000, of = C.TIME_UNSET))
    }

    @Test fun noJumpWhereThePlayerCantJump() {
        // Still opening, or a file without an index (a jump there would start it from the beginning).
        assertEquals(PhoneStep.Stay, step(PlayerCommand.BACK_10, 1_200_000, seekable = false))
        assertEquals(PhoneStep.Stay, step(PlayerCommand.FORWARD_30, 1_200_000, seekable = false))
        // Next doesn't jump within the film: it still goes on.
        assertEquals(PhoneStep.Next, step(PlayerCommand.NEXT, 1_200_000, seekable = false, hasNext = true))
    }

    @Test fun nextGoesOnOnlyWithSomethingQueued() {
        assertEquals(PhoneStep.Next, step(PlayerCommand.NEXT, 1_200_000, hasNext = true))
        // Asked just as the queue ran out: nothing happens (the film isn't marked watched with nowhere to go).
        assertEquals(PhoneStep.Stay, step(PlayerCommand.NEXT, 1_200_000, hasNext = false))
    }

    @Test fun fromUpNextBackGoesIntoTheLastSecondsAndPlaysThemAheadHasNowhereToGo() {
        val end = 1_320_000L
        assertEquals(PhoneStep.Seek(end - 10_000, playOn = true), step(PlayerCommand.BACK_10, end, of = end, hasNext = true, upNext = true))
        assertEquals(PhoneStep.Stay, step(PlayerCommand.FORWARD_30, end, of = end, hasNext = true, upNext = true))
        assertEquals(PhoneStep.Next, step(PlayerCommand.NEXT, end, of = end, hasNext = true, upNext = true))
        // Everywhere else a jump leaves play and pause as they were.
        assertEquals(PhoneStep.Seek(end - 20_000, playOn = false), step(PlayerCommand.BACK_10, end - 10_000, of = end, hasNext = true))
    }

    @Test fun thePhoneIsToldADriveFilmsKeyAndNothingElse() {
        assertEquals("drive:1AbC_dEf-9", phoneKey("drive:1AbC_dEf-9"))
        // A file on the TV's own storage, a web address sent from the phone (it can carry a token), nothing at all.
        assertNull(phoneKey("/storage/1234-ABCD/Movies/Midnight Harbor (2024).mkv"))
        assertNull(phoneKey("https://example.com/films/orbit.mp4?token=abc123"))
        assertNull(phoneKey("http://example.com/clip.mp4"))
        assertNull(phoneKey(null))
    }
}
