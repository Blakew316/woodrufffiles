package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleHostsTest {
    @Test fun googleHostsOverHttps() {
        assertTrue(isGoogleUrl("https://lh3.googleusercontent.com/abc=s720"))
        assertTrue(isGoogleUrl("https://www.googleapis.com/drive/v3/files/x?alt=media"))
        assertTrue(isGoogleUrl("https://drive.google.com/thumbnail?id=1"))
    }

    @Test fun neverLookAlikesOrPlainHttp() {
        assertFalse(isGoogleUrl("http://lh3.googleusercontent.com/abc"))
        assertFalse(isGoogleUrl("https://evilgoogle.com/x"))
        assertFalse(isGoogleUrl("https://google.com.example.net/x"))
        assertFalse(isGoogleUrl("https://image.tmdb.org/t/p/w342/x.jpg"))
        assertFalse(isGoogleUrl("not a url"))
    }
}
