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

    @Test fun runningOutOfMemoryIsFoundDeepInTheCauses() {
        assertTrue(PlaybackErrors.outOfMemory(RuntimeException("decoder", IllegalStateException(OutOfMemoryError()))))
        assertTrue(PlaybackErrors.outOfMemory(OutOfMemoryError()))
        assertFalse(PlaybackErrors.outOfMemory(RuntimeException("decoder", IllegalStateException("bad frame"))))
    }
}
