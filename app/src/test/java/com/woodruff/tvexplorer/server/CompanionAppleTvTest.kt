package com.woodruff.tvexplorer.server

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nowplaying.AppleTvScreen
import com.woodruff.tvexplorer.tvs.TvLinks
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Woodruff on the Apple TV telling the TV it's plugged into that it's on the Apple TV's screen (POST /api/appletv):
 * what it sends, how long each word lasts, and that it takes the pairing key, as the family's phones and Macs do,
 * never a linked TV's key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompanionAppleTvTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)
    private val token = "abcdefghjkmn"

    @Before fun nothingSaidYet() = AppleTvScreen.said(false)
    @After fun forget() = AppleTvScreen.said(false)

    private fun say(body: JSONObject) = routes.answerFor(phoneRequest("/api/appletv", Method.POST, body = body))

    @Test fun onScreenForAsLongAsItSaysThenOff() {
        val t = SystemClock.elapsedRealtime()
        val r = say(JSONObject().put("on", true).put("for", 25))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertEquals("{\"ok\":true}", r.bodyText())
        assertTrue(AppleTvScreen.onScreen(t + 24_000))
        assertFalse(AppleTvScreen.onScreen(t + 26_000))
        // It left the screen.
        assertEquals(NanoHTTPD.Response.Status.OK, say(JSONObject().put("on", false)).status)
        assertFalse(AppleTvScreen.onScreen(t))
    }

    @Test fun aWordLastsFiveToSixtySecondsAndOneThatDoesntSay25() {
        val t = SystemClock.elapsedRealtime()
        say(JSONObject().put("on", true))
        assertTrue(AppleTvScreen.onScreen(t + 24_000))
        assertFalse(AppleTvScreen.onScreen(t + 26_000))
        say(JSONObject().put("on", true).put("for", 3_600))
        assertTrue(AppleTvScreen.onScreen(t + 59_000))
        assertFalse(AppleTvScreen.onScreen(t + 61_000))
        say(JSONObject().put("on", true).put("for", 0))
        assertTrue(AppleTvScreen.onScreen(t + 4_000))
        assertFalse(AppleTvScreen.onScreen(t + 6_000))
    }

    @Test fun anythingButOnIsOff() {
        say(JSONObject().put("on", true).put("for", 25))
        say(JSONObject())
        assertFalse(AppleTvScreen.onScreen())
    }

    @Test fun itTakesThePairingKeyNeverALinkedTvsKey() {
        assertFalse("/api/appletv" in TvLinks.LINK_ROUTES)
        val server = TransferServer(0, token, "<html></html>", { emptyList() }, {}, extra = routes::handle, public = { routes.public(it) })
        val body = JSONObject().put("on", true).put("for", 25)
        // No key, or someone else's: turned away, and nothing is taken from it.
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(phoneRequest("/api/appletv", Method.POST, body = body)).status)
        assertEquals(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            server.serve(phoneRequest("/api/appletv", Method.POST, params = mapOf("k" to "someone-elses-key"), body = body)).status,
        )
        assertFalse(AppleTvScreen.onScreen())
        // The pairing key, in the address or as the cookie.
        assertEquals(NanoHTTPD.Response.Status.OK, server.serve(phoneRequest("/api/appletv", Method.POST, params = mapOf("k" to token), body = body)).status)
        assertTrue(AppleTvScreen.onScreen())
        AppleTvScreen.said(false)
        assertEquals(
            NanoHTTPD.Response.Status.OK,
            server.serve(phoneRequest("/api/appletv", Method.POST, headers = mapOf("cookie" to "tvx=$token"), body = body)).status,
        )
        assertTrue(AppleTvScreen.onScreen())
    }
}
