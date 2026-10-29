package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Streaming from Drive: who gets the token, when it's renewed, and how much video is held in memory. */
class DriveStreamTest {
    private val drive = "/drive/v3/files/abc"

    @Test fun onlyDriveFilesOverHttpsGetTheToken() {
        assertTrue(carriesDriveToken("https", "www.googleapis.com", drive))
        assertFalse(carriesDriveToken("http", "www.googleapis.com", drive))
        assertFalse(carriesDriveToken("https", "googleapis.com", drive))
        assertFalse(carriesDriveToken("https", "www.googleapis.com.example.net", drive))
        assertFalse(carriesDriveToken("https", "lh3.googleusercontent.com", drive))
        assertFalse(carriesDriveToken("https", "image.tmdb.org", drive))
        // Other Google APIs on the same host, a link sent from the phone, say.
        assertFalse(carriesDriveToken("https", "www.googleapis.com", "/youtube/v3/videos"))
        assertFalse(carriesDriveToken("https", "www.googleapis.com", "/drivex/files"))
        assertFalse(carriesDriveToken("file", null, "/storage/emulated/0/drive/film.mkv"))
        assertFalse(carriesDriveToken(null, null, null))
    }

    @Test fun theTokenIsRenewedJustInsideItsLastMinute() {
        val now = 1_700_000_000_000L
        val expires = now + 3_600_000
        val renewAt = now + tokenRenewDelayMs(expires, now)
        // GoogleAuth renews a token only in its last 60 s, so earlier would just return the old one.
        assertTrue(expires - renewAt in 1L until 60_000L)
        assertTrue(renewAt > now + 3_500_000)
    }

    @Test fun aTokenThatIsDueIsRenewedAtOnce() {
        val now = 1_700_000_000_000L
        assertEquals(0L, tokenRenewDelayMs(now + 30_000, now))
        assertEquals(0L, tokenRenewDelayMs(now - 5_000, now))
        assertEquals(0L, tokenRenewDelayMs(0, now)) // none kept yet
    }

    @Test fun bigHeapsKeepMedia3sOwnBuffer() {
        assertNull(bufferCapBytes(256))
        assertNull(bufferCapBytes(512))
    }

    @Test fun onSmallHeapsTheBufferAndOkHttpsWindowShareHalf() {
        for (heapMb in listOf(96, 128, 192)) {
            assertEquals(heapMb * 1024 * 1024 / 2, bufferCapBytes(heapMb)!! + HTTP2_WINDOW_BYTES)
        }
        assertEquals(80 * 1024 * 1024, bufferCapBytes(192))
    }

    @Test fun aTinyHeapStillGetsMedia3sSmallestBuffer() {
        assertEquals(200 * 64 * 1024, bufferCapBytes(32))
    }
}
