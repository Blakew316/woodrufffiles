package com.woodruff.tvexplorer.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenError
import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenResult
import com.woodruff.tvexplorer.util.isNetworkFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Renewing a sign-in's access token: a renewal that can't reach Google (a TV whose Wi-Fi is still
 * reconnecting after standby) keeps the sign-in and says "offline"; only Google refusing the
 * refresh token ends it. org.json needs Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GoogleAuthTokenTest {
    private val now = 1_800_000_000_000L

    /** The tokens as GoogleAuth keeps them, in a map. */
    private class MapTokens(vararg entries: Pair<String, String>) : GoogleAuth.Tokens {
        val map = mutableMapOf(*entries)
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String) { map[name] = value }
        override fun remove(vararg names: String) { names.forEach { map.remove(it) } }
    }

    /** Signed in to Drive (and the cameras), with an access token that ran out a minute ago. */
    private fun signedIn() = MapTokens(
        GoogleAuth.K_CLIENT_ID to "123-abc.apps.googleusercontent.com",
        GoogleAuth.K_CLIENT_SECRET to "secret",
        Grant.DRIVE.refreshKey to "refresh-1",
        Grant.DRIVE.accessKey to "old-access",
        Grant.DRIVE.expiresKey to (now - 60_000).toString(),
        Grant.DRIVE.scopesKey to Grant.DRIVE.scope,
        Grant.NEST.refreshKey to "nest-refresh",
    )

    private fun failing(e: Exception): (List<Pair<String, String>>) -> JSONObject = { throw e }

    @Test fun aGoodTokenIsHandedOutWithoutAskingGoogle() {
        val store = signedIn().apply { put(Grant.DRIVE.accessKey, "fresh"); put(Grant.DRIVE.expiresKey, (now + 30 * 60_000).toString()) }
        var asked = false
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) { asked = true; JSONObject() }
        assertEquals(TokenResult.Ok("fresh"), r)
        assertFalse(asked)
    }

    @Test fun aTokenInItsLastMinuteIsRenewed() {
        val store = signedIn().apply { put(Grant.DRIVE.accessKey, "nearly-gone"); put(Grant.DRIVE.expiresKey, (now + 30_000).toString()) }
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) { JSONObject().put("access_token", "new").put("expires_in", 3599) }
        assertEquals(TokenResult.Ok("new"), r)
    }

    @Test fun noNetworkIsOfflineAndKeepsTheSignIn() {
        for (e in listOf<Exception>(
            UnknownHostException("Unable to resolve host \"oauth2.googleapis.com\""),
            SocketTimeoutException("timeout"),
            TokenError(TokenError.Kind.NETWORK, "Couldn't reach Google", UnknownHostException()),
            // Google's own outage (5xx, 429) is the same: try again later.
            TokenError(TokenError.Kind.UNAVAILABLE, "busy"),
        )) {
            val store = signedIn()
            val before = store.map.toMap()
            val r = GoogleAuth.refresh(store, Grant.DRIVE, now, failing(e))
            assertTrue("$e", r is TokenResult.Offline)
            assertEquals("$e", before, store.map)
        }
    }

    @Test fun onlyARevokedApprovalEndsTheSignIn() = runBlocking {
        val store = signedIn()
        // The screens hear about it (the QR code comes up at once).
        val heard = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { GoogleAuth.expirations.first() } }
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now, failing(TokenError(TokenError.Kind.REVOKED, "Token has been expired or revoked.")))
        assertEquals(TokenResult.SignedOut, r)
        for (key in Grant.DRIVE.stored) assertNull(key, store.get(key))
        assertEquals(Grant.DRIVE, heard.await())
        // The cameras' sign-in and the keys are another matter.
        assertEquals("nest-refresh", store.get(Grant.NEST.refreshKey))
        assertEquals("123-abc.apps.googleusercontent.com", store.get(GoogleAuth.K_CLIENT_ID))
    }

    @Test fun aRevocationDoesNotEndASignInMadeMeanwhile() {
        val store = signedIn()
        // While the old refresh token was being refused, the family signed in again on the phone.
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) {
            store.put(Grant.DRIVE.refreshKey, "refresh-2")
            throw TokenError(TokenError.Kind.REVOKED, "Token has been expired or revoked.")
        }
        assertEquals(TokenResult.SignedOut, r)
        assertEquals("refresh-2", store.get(Grant.DRIVE.refreshKey))
    }

    @Test fun refusedKeysAreTheirOwnProblemNotOfflineOrSignedOut() {
        for (kind in listOf(TokenError.Kind.BAD_CLIENT, TokenError.Kind.OTHER)) {
            val store = signedIn()
            val before = store.map.toMap()
            val r = GoogleAuth.refresh(store, Grant.DRIVE, now, failing(TokenError(kind, "Google didn't accept this TV's Client ID or secret.")))
            assertEquals(TokenResult.Rejected("Google didn't accept this TV's Client ID or secret."), r)
            assertEquals(before, store.map)
        }
    }

    @Test fun withoutASignInOrKeysGoogleIsNotAsked() {
        var asked = false
        val call: (List<Pair<String, String>>) -> JSONObject = { asked = true; JSONObject() }
        assertEquals(TokenResult.SignedOut, GoogleAuth.refresh(MapTokens(), Grant.DRIVE, now, call))
        // Signed in, but the Client ID is gone (removed, or unreadable just now).
        val noClient = signedIn().apply { remove(GoogleAuth.K_CLIENT_ID) }
        assertTrue(GoogleAuth.refresh(noClient, Grant.DRIVE, now, call) is TokenResult.Rejected)
        assertEquals("refresh-1", noClient.get(Grant.DRIVE.refreshKey))
        assertFalse(asked)
    }

    @Test fun aRenewedTokenIsKeptWithItsExpiryAndScopes() {
        val store = signedIn()
        var sent: List<Pair<String, String>> = emptyList()
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) { params ->
            sent = params
            JSONObject().put("access_token", "new-access").put("expires_in", 3599).put("scope", "https://www.googleapis.com/auth/drive.readonly")
        }
        assertEquals(TokenResult.Ok("new-access"), r)
        assertEquals("new-access", store.get(Grant.DRIVE.accessKey))
        assertEquals((now + 3_599_000).toString(), store.get(Grant.DRIVE.expiresKey))
        assertEquals("https://www.googleapis.com/auth/drive.readonly", store.get(Grant.DRIVE.scopesKey))
        val params = sent.toMap()
        assertEquals("refresh-1", params["refresh_token"])
        assertEquals("refresh_token", params["grant_type"])
        assertEquals("123-abc.apps.googleusercontent.com", params["client_id"])
        assertEquals("secret", params["client_secret"])
    }

    @Test fun aTokenForASignInThatEndedMeanwhileIsNotKept() {
        val store = signedIn()
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) {
            store.remove(*Grant.DRIVE.stored) // signed out on the TV while Google answered
            JSONObject().put("access_token", "late").put("expires_in", 3599)
        }
        assertEquals(TokenResult.Ok("late"), r)
        assertNull(store.get(Grant.DRIVE.accessKey))
    }

    @Test fun anAnswerWithoutATokenIsNotTheEndOfTheSignIn() {
        val store = signedIn()
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) { JSONObject() }
        assertTrue(r is TokenResult.Offline)
        assertEquals("refresh-1", store.get(Grant.DRIVE.refreshKey))
    }

    @Test fun googlesErrorAnswersAreSortedByWhatTheyMean() {
        assertEquals(TokenError.Kind.REVOKED, GoogleAuth.tokenErrorKind(400, "invalid_grant"))
        assertEquals(TokenError.Kind.BAD_CLIENT, GoogleAuth.tokenErrorKind(401, "invalid_client"))
        assertEquals(TokenError.Kind.BAD_CLIENT, GoogleAuth.tokenErrorKind(400, "unauthorized_client"))
        for (code in listOf(500, 502, 503, 504, 429, 408)) assertEquals("$code", TokenError.Kind.UNAVAILABLE, GoogleAuth.tokenErrorKind(code, ""))
        assertEquals(TokenError.Kind.UNAVAILABLE, GoogleAuth.tokenErrorKind(503, "backend_error"))
        assertEquals(TokenError.Kind.OTHER, GoogleAuth.tokenErrorKind(400, "invalid_request"))
        assertEquals(TokenError.Kind.OTHER, GoogleAuth.tokenErrorKind(403, ""))
    }

    @Test fun onlyOfflineReadsAsANetworkFailure() {
        assertTrue(isNetworkFailure(GoogleAuth.AuthException.Offline(UnknownHostException())))
        // Google's own outage too: it isn't a network exception itself, but it's offline all the same.
        assertTrue(isNetworkFailure(GoogleAuth.AuthException.Offline(TokenError(TokenError.Kind.UNAVAILABLE, "busy"))))
        assertFalse(isNetworkFailure(GoogleAuth.AuthException.SignedOut("Connect your cameras again")))
        assertFalse(isNetworkFailure(GoogleAuth.AuthException.Rejected("Check the keys")))
        assertFalse(isNetworkFailure(IOException("Google Calendar error 500")))
    }
}
