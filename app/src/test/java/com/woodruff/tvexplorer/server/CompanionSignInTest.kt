package com.woodruff.tvexplorer.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.tvs.HealthGrant
import com.woodruff.tvexplorer.tvs.SignIns
import com.woodruff.tvexplorer.tvs.TvLinks
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Signing this TV in from its phone app, through the companion routes: the attempt and its status, Google's answer
 * finished in the background (the exchange is faked: tests never reach Google), the page that answer lands on, and
 * that only this TV's pairing key can start one (never a linked TV's key).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompanionSignInTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)
    private val base = "http://living-room.local:8080"
    private val exchange = PhoneSignIn.exchange

    @After fun putBack() {
        PhoneSignIn.exchange = exchange
        CompanionHub.googleAttempts.clear()
    }

    @Test fun signInsNeverTravelOverALink() {
        // Not link routes: a linked TV's key opens nothing here (TransferServer then asks for the pairing key).
        assertFalse("/api/signin" in TvLinks.LINK_ROUTES)
        assertFalse("/api/signin/status" in TvLinks.LINK_ROUTES)
        assertNull(routes.public(session("/api/signin", NanoHTTPD.Method.POST, mapOf("k" to "someone-elses-key"), JSONObject().put("grant", "drive"))))
        assertNull(routes.public(session("/api/signin/status", NanoHTTPD.Method.GET, mapOf("k" to "someone-elses-key", "state" to "x"))))
    }

    @Test fun anAttemptNeedsTheKeysFirstAndANameItKnows() {
        val unknown = handle(session("/api/signin", NanoHTTPD.Method.POST, body = JSONObject().put("grant", "youtube")))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, unknown.status)
        assertEquals("Unknown sign-in", text(unknown))
        // No Google keys on this TV yet: the phone's sheet says to set them up first.
        val keys = handle(session("/api/signin", NanoHTTPD.Method.POST, body = JSONObject().put("grant", "cameras")))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, keys.status)
        val o = JSONObject(text(keys))
        assertEquals("keys", o.getString("need"))
        assertEquals("Add the Google Client ID and secret first", o.getString("message"))
    }

    @Test fun signedInFromThePhoneWithNoScreenOpen() = runBlocking {
        val ready = PhoneSignIn.begin(ctx, GoogleAuth.Grant.DRIVE, base)
        // Google's answer goes to this TV's home-network address, inside the state the relay page reads.
        val state = String(android.util.Base64.decode(ready.state, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
        assertEquals(base, JSONObject(state).getString("r"))
        assertTrue(CompanionHub.googleAttempts.getValue(ready.state).fromPhone)
        assertEquals("pending", status(ready.state).getString("status"))

        val swapped = CompletableDeferred<String>()
        PhoneSignIn.exchange = { _, code, a -> assertTrue(a.fromPhone); swapped.complete(code) }
        // Settings and Streaming hear of it (they follow CompanionHub.changes).
        val heard = CompletableDeferred<Unit>()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) { CompanionHub.changes.first(); heard.complete(Unit) }
        val page = callback(mapOf("state" to ready.state, "code" to "4/one-time"))
        assertEquals("4/one-time", withTimeout(2_000) { swapped.await() })
        withTimeout(2_000) { heard.await() }
        listener.cancel()
        assertTrue(page, page.contains("${TvLinks.name(ctx)} is signed in ✓"))
        assertTrue(page, page.contains("Google Drive is back on ${TvLinks.name(ctx)}. Go back to Woodruff Family on your phone."))
        val done = status(ready.state)
        assertEquals("signedIn", done.getString("status"))
        assertFalse(done.getBoolean("weekly"))
        // One use: the same answer again finds an old link, worded for the phone.
        assertTrue(callback(mapOf("state" to ready.state, "code" to "4/one-time")).contains("This sign-in link is old"))
        assertFalse(CompanionHub.googleAttempts.containsKey(ready.state))
    }

    @Test fun cancelledAtGoogle() {
        val ready = PhoneSignIn.begin(ctx, GoogleAuth.Grant.PHOTOS, base)
        PhoneSignIn.exchange = { _, _, _ -> throw AssertionError("nothing to swap") }
        val page = callback(mapOf("state" to ready.state, "error" to "access_denied"))
        assertTrue(page, page.contains("Sign-in cancelled") && page.contains("Nothing changed. You can try again from your phone."))
        assertEquals("cancelled", status(ready.state).getString("status"))
    }

    @Test fun whenTheTvCouldntFinishThePhoneHearsWhy() {
        val ready = PhoneSignIn.begin(ctx, GoogleAuth.Grant.PHOTOS, base)
        PhoneSignIn.exchange = { _, _, _ -> throw GoogleAuth.TokenError(GoogleAuth.TokenError.Kind.REVOKED, "Google sign-in failed: Bad Request") }
        val page = callback(mapOf("state" to ready.state, "code" to "4/stale"))
        assertTrue(page, page.contains("Signing in didn’t finish") && page.contains("Google didn’t accept the answer. Go back to Woodruff Family on your phone to try again."))
        val s = status(ready.state)
        assertEquals("failed", s.getString("status"))
        assertEquals("Google didn’t accept the answer.", s.getString("message"))
        assertFalse(page.contains("Bad Request"))
    }

    @Test fun aNewerAttemptReplacesTheOlderOne() {
        val first = PhoneSignIn.begin(ctx, GoogleAuth.Grant.DRIVE, base)
        // The TV's own QR code for the same sign-in, made meanwhile.
        CompanionHub.expect(GoogleAuth.newAttempt(ctx, base, GoogleAuth.Grant.DRIVE))
        assertEquals("replaced", status(first.state).getString("status"))
        PhoneSignIn.exchange = { _, _, _ -> throw AssertionError("a replaced attempt's answer isn't used") }
        assertTrue(callback(mapOf("state" to first.state, "code" to "4/late")).contains("This sign-in link is old"))
        // Another sign-in waiting at the same time is left alone.
        val photos = PhoneSignIn.begin(ctx, GoogleAuth.Grant.PHOTOS, base)
        PhoneSignIn.begin(ctx, GoogleAuth.Grant.CALENDAR, base)
        assertEquals("pending", status(photos.state).getString("status"))
    }

    @Test fun aTvStartedSignInKeepsTheTvsWords() {
        val a = GoogleAuth.newAttempt(ctx, base, GoogleAuth.Grant.PHOTOS)
        CompanionHub.expect(a)
        val page = callback(mapOf("state" to a.state, "error" to "access_denied"))
        assertTrue(page, page.contains("Sign-in cancelled") && page.contains("You can try again from the TV."))
        // An unknown state isn't the phone's: the TV's words.
        assertTrue(callback(mapOf("state" to "not-one-of-ours")).contains("On the TV, open the screen you were connecting and scan the new QR code."))
    }

    @Test fun anAttemptTheTvForgotIsSaidPlainly() {
        val s = status("from-before-a-restart", "drive")
        assertEquals("failed", s.getString("status"))
        assertEquals("${TvLinks.name(ctx)} restarted while you were signing in.", s.getString("message"))
    }

    @Test fun connectionsUseTheAccountsWords() {
        // What /api/settings sends as google/nest "state" and "text" (the route itself needs the pairing key, which
        // needs the Keystore tests don't have). No keys on a new TV: Drive isn't set up, the cameras aren't connected.
        val drive = SignIns.account(ctx, HealthGrant.DRIVE).state
        val cameras = SignIns.account(ctx, HealthGrant.CAMERAS).state
        assertEquals("notSetUp", drive.json)
        assertEquals("Not set up", drive.words(HealthGrant.DRIVE))
        assertEquals("notSetUp", cameras.json)
        assertEquals("Not connected", cameras.words(HealthGrant.CAMERAS))
    }

    @Test fun theAnswerPageEscapesWhatItShows() {
        TvLinks.setName(ctx, "Den <b>")
        try {
            val ready = PhoneSignIn.begin(ctx, GoogleAuth.Grant.PHOTOS, base)
            PhoneSignIn.exchange = { _, _, _ -> throw IOException("timeout") }
            val page = callback(mapOf("state" to ready.state, "code" to "4/x"))
            assertTrue(page, page.contains("Den &lt;b&gt; couldn’t reach Google."))
            assertFalse(page.contains("<b>"))
        } finally {
            TvLinks.setName(ctx, "")
        }
    }

    // ---- Requests, as NanoHTTPD hands them over (a bad request becomes a 400, as TransferServer does) ----------

    private fun status(state: String, grant: String = "drive") =
        JSONObject(text(handle(session("/api/signin/status", NanoHTTPD.Method.GET, mapOf("state" to state, "grant" to grant)))))

    private fun callback(params: Map<String, String>): String = text(routes.public(session("/oauth/callback", NanoHTTPD.Method.GET, params))!!)

    private fun text(r: NanoHTTPD.Response) = r.data.bufferedReader().readText()

    private fun handle(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
        routes.handle(s) ?: NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "")
    } catch (e: IllegalArgumentException) {
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", e.message)
    }

    private fun session(uri: String, method: NanoHTTPD.Method, params: Map<String, String> = emptyMap(), body: JSONObject? = null): NanoHTTPD.IHTTPSession {
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies(): NanoHTTPD.CookieHandler? = null
            override fun getHeaders(): Map<String, String> = mapOf("content-length" to bytes.size.toString(), "host" to "living-room.local:8080")
            override fun getInputStream() = ByteArrayInputStream(bytes)
            override fun getMethod() = method
            @Deprecated("") override fun getParms(): Map<String, String> = params
            override fun getParameters(): Map<String, List<String>> = params.mapValues { listOf(it.value) }
            override fun getQueryParameterString(): String = ""
            override fun getUri() = uri
            override fun parseBody(files: MutableMap<String, String>?) {}
            override fun getRemoteIpAddress() = "127.0.0.1"
            override fun getRemoteHostName() = "localhost"
        }
    }
}
