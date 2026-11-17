package com.woodruff.tvexplorer.server

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.nowplaying.AppleTvScreen
import com.woodruff.tvexplorer.nowplaying.NowPlaying
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
 * Woodruff on an Apple TV telling the TV it's plugged into that it's on the Apple TV's screen (POST /api/appletv):
 * what it sends, how long each word lasts and whose it is; what the TV says of its own screen for the Apple TV
 * (GET /api/appletv); that it takes the pairing key, as the family's phones and Macs do, or a linked TV's key for
 * the word passed on by the hub; and the hub passing it on (/api/tvs/appletv).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompanionAppleTvTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)
    private val token = "abcdefghjkmn"
    private var own: () -> Boolean = { false }

    @Before fun nothingSaidYet() {
        AppleTvScreen.forget()
        NowPlaying.ownPlayer = null
        own = Notices.ownScreenUp
        Notices.ownScreenUp = { false }
    }

    @After fun forget() {
        AppleTvScreen.forget()
        Notices.ownScreenUp = own
    }

    private fun say(body: JSONObject) = routes.answerFor(phoneRequest("/api/appletv", Method.POST, body = body))

    @Test fun onScreenForAsLongAsItSaysThenOff() {
        val t = SystemClock.elapsedRealtime()
        val r = say(JSONObject().put("on", true).put("for", 25).put("from", "apple-tv-1").put("name", "Living Room Apple TV"))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertEquals("{\"ok\":true}", r.bodyText())
        assertTrue(AppleTvScreen.onScreen(t + 24_000))
        assertFalse(AppleTvScreen.onScreen(t + 26_000))
        // It left the screen.
        assertEquals(NanoHTTPD.Response.Status.OK, say(JSONObject().put("on", false).put("from", "apple-tv-1")).status)
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

    @Test fun oneAppleTvsOffNeverCancelsAnothersOn() {
        say(JSONObject().put("on", true).put("for", 25).put("from", "apple-tv-1"))
        say(JSONObject().put("on", true).put("for", 25).put("from", "apple-tv-2"))
        say(JSONObject().put("on", false).put("from", "apple-tv-1"))
        assertTrue(AppleTvScreen.onScreen())
        // One without a "from" (an Apple TV on an older Woodruff) is a sender of its own too.
        say(JSONObject().put("on", false))
        assertTrue(AppleTvScreen.onScreen())
        say(JSONObject().put("on", false).put("from", "apple-tv-2"))
        assertFalse(AppleTvScreen.onScreen())
    }

    @Test fun theTvSaysWhatsOnItsScreenForTheAppleTv() {
        // Robolectric's media sessions list nothing: it can't tell.
        val r = routes.answerFor(phoneRequest("/api/appletv"))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertEquals("{\"input\":null,\"appleTv\":null}", r.bodyText())
        // Woodruff's own screens are up: it's on no input.
        Notices.ownScreenUp = { true }
        assertEquals("{\"input\":false,\"appleTv\":false}", routes.answerFor(phoneRequest("/api/appletv")).bodyText())
    }

    @Test fun itTakesThePairingKeyOrALinkedTvsKey() {
        // The hub passes an Apple TV's word on with this TV's link key, and asks for its report with it.
        assertTrue("/api/appletv" in TvLinks.LINK_ROUTES)
        // The hub's own route for that takes the pairing key only.
        assertFalse("/api/tvs/appletv" in TvLinks.LINK_ROUTES)
        val server = TransferServer(0, token, "<html></html>", { emptyList() }, {}, extra = routes::handle, public = { routes.public(it) })
        val body = JSONObject().put("on", true).put("for", 25).put("from", "apple-tv-1")
        // No key, or someone else's: turned away, and nothing is taken from it.
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(phoneRequest("/api/appletv", Method.POST, body = body)).status)
        assertEquals(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            server.serve(phoneRequest("/api/appletv", Method.POST, params = mapOf("k" to "someone-elses-key"), body = body)).status,
        )
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(phoneRequest("/api/appletv")).status)
        assertFalse(AppleTvScreen.onScreen())
        // The pairing key, in the address or as the cookie.
        assertEquals(NanoHTTPD.Response.Status.OK, server.serve(phoneRequest("/api/appletv", Method.POST, params = mapOf("k" to token), body = body)).status)
        assertTrue(AppleTvScreen.onScreen())
        AppleTvScreen.forget()
        assertEquals(
            NanoHTTPD.Response.Status.OK,
            server.serve(phoneRequest("/api/appletv", Method.POST, headers = mapOf("cookie" to "tvx=$token"), body = body)).status,
        )
        assertTrue(AppleTvScreen.onScreen())
    }

    @Test fun theHubPassesTheWordOnToTheTvItsFor() {
        // "this": the hub itself.
        val word = JSONObject().put("id", "this").put("on", true).put("for", 25).put("from", "apple-tv-1")
        assertEquals(NanoHTTPD.Response.Status.OK, routes.answerFor(phoneRequest("/api/tvs/appletv", Method.POST, body = word)).status)
        assertTrue(AppleTvScreen.onScreen())
        assertEquals(
            "{\"input\":null,\"appleTv\":null}",
            routes.answerFor(phoneRequest("/api/tvs/appletv", params = mapOf("id" to "this"))).bodyText(),
        )
        // A TV that isn't linked to this one: said so, and nothing is taken here.
        AppleTvScreen.forget()
        val r = routes.answerFor(phoneRequest("/api/tvs/appletv", Method.POST, body = word.put("id", "no-such-tv")))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
        assertFalse(AppleTvScreen.onScreen())
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, routes.answerFor(phoneRequest("/api/tvs/appletv", params = mapOf("id" to "no-such-tv"))).status)
    }
}
