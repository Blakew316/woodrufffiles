package com.woodruff.tvexplorer.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which playback errors are waited out on the same film, and how a film that can't play is explained. */
class PlaybackErrorsTest {
    private val failed = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    private val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    @Test fun noConnectionIsWaitedOutWhereverTheFilmIs() {
        for (drive in listOf(true, false)) {
            assertEquals(Stall.NETWORK, PlaybackErrors.stallOf(null, failed, drive))
            assertEquals(Stall.NETWORK, PlaybackErrors.stallOf(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, drive))
            assertEquals(Stall.NETWORK, PlaybackErrors.stallOf(null, PlaybackException.ERROR_CODE_TIMEOUT, drive))
        }
    }

    @Test fun aBusyOrFailingServerIsWaitedOut() {
        for (http in listOf(429, 500, 502, 503, 599)) {
            assertEquals("$http", Stall.BUSY, PlaybackErrors.stallOf(http, badStatus, drive = true))
            assertEquals("$http", Stall.BUSY, PlaybackErrors.stallOf(http, badStatus, drive = false))
        }
    }

    @Test fun onlyGoogleDrivesRefusalsAreWaitedOut() {
        assertEquals(Stall.REFUSED, PlaybackErrors.stallOf(401, badStatus, drive = true))
        assertEquals(Stall.REFUSED, PlaybackErrors.stallOf(403, badStatus, drive = true))
        // A link sent from the phone that refuses won't change its mind: say so and move on (it used
        // to try again every 10 s for ever, saying Google Drive had turned it down).
        assertNull(PlaybackErrors.stallOf(401, badStatus, drive = false))
        assertNull(PlaybackErrors.stallOf(403, badStatus, drive = false))
    }

    @Test fun googlesOwnReasonSaysWhichRefusalItIs() {
        // Its daily limit: said as that, and asked again every few minutes.
        assertEquals(Stall.LIMIT, PlaybackErrors.stallOf(403, badStatus, drive = true, reason = "downloadQuotaExceeded"))
        // Limiting how fast requests come: it passes by itself, as Google being busy does.
        assertEquals(Stall.BUSY, PlaybackErrors.stallOf(403, badStatus, drive = true, reason = "userRateLimitExceeded"))
        assertEquals(Stall.BUSY, PlaybackErrors.stallOf(403, badStatus, drive = true, reason = "rateLimitExceeded"))
        // Any other reason, or none: turned down, waited out.
        assertEquals(Stall.REFUSED, PlaybackErrors.stallOf(403, badStatus, drive = true, reason = "forbidden"))
        assertEquals(Stall.REFUSED, PlaybackErrors.stallOf(401, badStatus, drive = true, reason = "authError"))
        // A link from the phone stays as it was.
        assertNull(PlaybackErrors.stallOf(403, badStatus, drive = false, reason = "downloadQuotaExceeded"))
    }

    @Test fun aRefusalOfTheTokenIsToldFromGooglesLimits() {
        assertTrue(PlaybackErrors.tokenRefused(401, null))
        assertTrue(PlaybackErrors.tokenRefused(403, null))
        assertTrue(PlaybackErrors.tokenRefused(403, "forbidden"))
        assertFalse(PlaybackErrors.tokenRefused(403, "downloadQuotaExceeded"))
        assertFalse(PlaybackErrors.tokenRefused(403, "userRateLimitExceeded"))
        assertFalse(PlaybackErrors.tokenRefused(500, null))
        assertFalse(PlaybackErrors.tokenRefused(null, null))
    }

    @Test fun theFilmsOwnTroubleIsNotWaitedOut() {
        assertNull(PlaybackErrors.stallOf(404, badStatus, drive = true))
        assertNull(PlaybackErrors.stallOf(400, badStatus, drive = true))
        assertNull(PlaybackErrors.stallOf(410, badStatus, drive = false))
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )) assertNull("$code", PlaybackErrors.stallOf(null, code, drive = true))
    }

    @Test fun whyAFilmCantPlayInPlainWords() {
        fun why(code: Int, http: Int? = null, drive: Boolean = true, oom: Boolean = false) = PlaybackErrors.why(code, http, drive, oom)
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )) assertEquals("this TV can’t play its video format", why(code))
        for (code in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )) assertEquals("the file seems to be damaged", why(code))
        assertEquals("it’s no longer in Google Drive", why(badStatus, http = 404))
        assertEquals("Google Drive won’t send this file", why(badStatus, http = 403))
        assertEquals("this TV ran out of memory", why(PlaybackException.ERROR_CODE_DECODING_FAILED, oom = true))
        assertEquals("this TV ran out of memory", why(PlaybackException.ERROR_CODE_UNSPECIFIED, oom = true))
        // Anything else, and a web link's missing file (it was never in Google Drive).
        assertEquals("this TV couldn’t open it", why(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals("this TV couldn’t open it", why(badStatus, http = 404, drive = false))
        assertEquals("this TV couldn’t open it", why(badStatus, http = 400))
        assertEquals("this TV couldn’t open it", why(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, drive = false))
        assertEquals("this TV couldn’t open it", why(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }

    @Test fun theMessageNamesTheFilmAndNeverACode() {
        assertEquals(
            "Can’t play Midnight Harbor: this TV can’t play its video format.",
            PlaybackErrors.cantPlay("Midnight Harbor", PlaybackErrors.why(PlaybackException.ERROR_CODE_DECODING_FAILED, null, true, false)),
        )
        assertEquals("Can’t play this file: the file seems to be damaged.", PlaybackErrors.cantPlay(null, "the file seems to be damaged"))
        assertEquals("Can’t play this file: the file seems to be damaged.", PlaybackErrors.cantPlay(" ", "the file seems to be damaged"))
        for (code in (1000..1004) + (2000..2008) + (3001..3004) + (4001..4006) + (5001..5004) + (6000..6008)) {
            for (http in listOf(null, 400, 401, 403, 404, 500)) {
                val text = PlaybackErrors.cantPlay("Film", PlaybackErrors.why(code, http, drive = true, outOfMemory = false))
                assertFalse(text, text.contains("ERROR") || text.contains("_") || text.contains("error") || text.contains("'"))
                http?.let { assertFalse(text, text.contains(it.toString())) }
            }
        }
    }

    @Test fun aFileDriveTurnsDownAgainAfterItsTryIsTurnedDownForGood() {
        // Drive flagged the video, its owner turned downloads off, these keys may not open it: after the
        // one try with a new token, it's said and the player moves on (it was tried every 10 s for hours).
        for (reason in listOf("cannotDownloadAbusiveFile", "cannotDownloadFile", "appNotAuthorizedToFile", "forbidden", null)) {
            assertTrue("$reason", PlaybackErrors.refusedForGood(403, drive = true, reason = reason, hadTry = true))
            // Its first refusal has its try first: Google sometimes stops taking a token early, with a 403.
            assertFalse("$reason", PlaybackErrors.refusedForGood(403, drive = true, reason = reason, hadTry = false))
        }
        // Google's limits pass by themselves: waited out, never "won't send".
        for (reason in listOf("downloadQuotaExceeded", "userRateLimitExceeded", "rateLimitExceeded")) {
            assertFalse(reason, PlaybackErrors.refusedForGood(403, drive = true, reason = reason, hadTry = true))
        }
        // A token turned down (401) is the sign-in's business; a link from the phone was never waited out.
        assertFalse(PlaybackErrors.refusedForGood(401, drive = true, reason = null, hadTry = true))
        assertFalse(PlaybackErrors.refusedForGood(403, drive = false, reason = "forbidden", hadTry = true))
        assertEquals(
            "Can’t play Midnight Harbor: Google Drive won’t send this file.",
            PlaybackErrors.cantPlay("Midnight Harbor", PlaybackErrors.why(badStatus, 403, drive = true, outOfMemory = false)),
        )
    }

    @Test fun googleBusyOrLimitingWaitsLongerEachTimeAndTheDailyLimitFiveMinutes() {
        // No connection: every 10 s (the network coming back is heard at once anyway).
        for (tries in 0..6) assertEquals(10_000L, PlaybackErrors.retryWaitMs(Stall.NETWORK, tries))
        // Google's daily limit: every 5 minutes.
        for (tries in 0..6) assertEquals(300_000L, PlaybackErrors.retryWaitMs(Stall.LIMIT, tries))
        // Busy, failing, limiting requests, or still turning the token down: 10 s, 30 s, 1 min, 2, then 5 for good.
        for (stall in listOf(Stall.BUSY, Stall.REFUSED)) {
            assertEquals(
                listOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L),
                (0..6).map { PlaybackErrors.retryWaitMs(stall, it) },
            )
        }
        assertTrue(Stall.SIGNED_OUT.stopped)
        assertTrue(Stall.KEYS_REFUSED.stopped)
        for (s in listOf(Stall.NETWORK, Stall.BUSY, Stall.REFUSED, Stall.LIMIT)) assertFalse(s.stopped)
    }

    @Test fun refusalsASecondTryCantChangeGoStraightToThePlayer() {
        // Media3 asked again three times, a second apart, before the player heard: four requests a refusal.
        for (http in listOf(401, 403, 404, 410, 416, 429)) assertTrue("$http", PlaybackErrors.noRetryFor(http))
        // A server busy or failing for a moment, or no answer at all: Media3 still tries again by itself.
        for (http in listOf(500, 502, 503, 408, null)) assertFalse("$http", PlaybackErrors.noRetryFor(http))
    }

    @Test fun runningOutOfMemoryIsFoundDeepInTheCauses() {
        assertTrue(PlaybackErrors.outOfMemory(RuntimeException("decoder", IllegalStateException(OutOfMemoryError()))))
        assertTrue(PlaybackErrors.outOfMemory(OutOfMemoryError()))
        assertFalse(PlaybackErrors.outOfMemory(RuntimeException("decoder", IllegalStateException("bad frame"))))
    }

    @Test fun theFilmsOwnTroubleIsTriedAgainWhereItWasBeforeItCantPlay() {
        val sound = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        val decoding = PlaybackException.ERROR_CODE_DECODING_FAILED
        val unsupported = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        // The soundbar waking or reconnecting: every wait, played or not.
        assertEquals(PlaybackErrors.IN_PLACE_WAITS_MS.size, PlaybackErrors.triesInPlace(sound, played = false))
        assertEquals(PlaybackErrors.IN_PLACE_WAITS_MS.size, PlaybackErrors.triesInPlace(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, played = true))
        // Anything in a film that has played already: tried again, not skipped.
        for (code in listOf(decoding, unsupported, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)) {
            assertTrue("$code", PlaybackErrors.triesInPlace(code, played = true) >= 2)
        }
        // From the start: a decoder that wouldn't start gets one try; a format this TV can't play, none.
        assertEquals(1, PlaybackErrors.triesInPlace(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, played = false))
        assertEquals(0, PlaybackErrors.triesInPlace(unsupported, played = false))
        assertEquals(0, PlaybackErrors.triesInPlace(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, played = false))
        // All of it under half a minute.
        assertTrue(PlaybackErrors.IN_PLACE_WAITS_MS.sum() < 30_000)
    }
}
