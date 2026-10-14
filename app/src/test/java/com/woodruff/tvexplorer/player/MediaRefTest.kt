package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRefTest {
    @Test fun keysRoundTrip() {
        val drive = MediaRef.parse("drive:abc123")
        assertTrue(drive is MediaRef.Drive)
        assertEquals("abc123", (drive as MediaRef.Drive).id)
        assertEquals("drive:abc123", drive.key)

        val local = MediaRef.parse("/storage/emulated/0/Movies/a.mkv")
        assertTrue(local is MediaRef.Local)
        assertTrue(MediaRef.parse("https://example.com/a.mp4") is MediaRef.Remote)
        assertEquals("/storage/emulated/0/Movies/a.mkv", local.key)
    }
}
