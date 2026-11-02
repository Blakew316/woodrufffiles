package com.woodruff.tvexplorer.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The phone app's page: a Home Screen launch ("/?k=…", the address the page keeps) gets the page in one round trip
 * with the cookie its requests use, and revalidates with a tiny 304 unless the app changed (finding 45).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhonePageTest {
    private val token = "abcdefghjkmn"
    private val server = TransferServer(0, token, "<html>__TVX_KEY__ __TVX_HOME_NAME__</html>", { emptyList() }, {}, homeName = { "Living Room" })

    private fun get(params: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()) =
        server.serve(phoneRequest("/", params = params, headers = headers))

    @Test fun aHomeScreenLaunchGetsThePageAtOnceWithItsCookie() {
        val r = get(mapOf("k" to token))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertNull("no redirect first", r.getHeader("Location"))
        assertTrue(r.bodyText().contains(token))
        assertEquals("tvx=$token; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax", r.getHeader("Set-Cookie"))
        assertEquals("no-cache", r.getHeader("Cache-Control"))
        val tag = r.getHeader("ETag")
        assertTrue(tag.startsWith("\""))
        // The next launch: the same page, so a tiny 304 (with the cookie again).
        val again = get(mapOf("k" to token), mapOf("if-none-match" to tag))
        assertEquals(NanoHTTPD.Response.Status.NOT_MODIFIED, again.status)
        assertEquals(tag, again.getHeader("ETag"))
        assertEquals("tvx=$token; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax", again.getHeader("Set-Cookie"))
    }

    @Test fun theCookieAloneStillOpensItWithoutSettingItAgain() {
        val r = get(headers = mapOf("cookie" to "tvx=$token"))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertNull(r.getHeader("Set-Cookie"))
    }

    @Test fun withoutTheKeyItAsksForTheCode() {
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, get().status)
        val wrong = get(mapOf("k" to "not-the-key"))
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, wrong.status)
        assertNull(wrong.getHeader("Set-Cookie"))
    }
}
