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
import kotlinx.coroutines.withTimeoutOrNull
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
        /** Kept, but read as unset just now: the TV's Keystore hasn't found its key yet (after a power cut). */
        val unreadable = mutableSetOf<String>()
        override fun get(name: String) = if (name in unreadable) null else map[name]
        // As SecretStore does: a value put meanwhile is this run's to read.
        override fun put(name: String, value: String) { map[name] = value; unreadable -= name }
        override fun remove(vararg names: String) { names.forEach { map.remove(it); unreadable -= it } }
        override fun unreadableNow(name: String) = name in unreadable && name in map
        /** The Keystore still waking: nothing kept can be read. */
        fun asleep() = apply { unreadable += map.keys }
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
            // So is anything else Google's sign-in service refuses that neither ends the sign-in nor is the keys.
            TokenError(TokenError.Kind.OTHER, "Google sign-in failed: invalid_request"),
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
        // Remembered as ended, and when (Settings: "Sign-in ended"; Streaming: "Sign in to Google Drive
        // again"; its notice, once per ending).
        assertEquals(now.toString(), store.get(Grant.DRIVE.endedKey))
        // Asking again finds it signed out, without ending it a second time.
        assertEquals(TokenResult.SignedOut, GoogleAuth.refresh(store, Grant.DRIVE, now + 60_000, failing(IOException("offline"))))
        assertEquals(now.toString(), store.get(Grant.DRIVE.endedKey))
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
        // Nor is the new one marked as ended.
        assertNull(store.get(Grant.DRIVE.endedKey))
    }

    @Test fun refusedKeysAreTheirOwnProblemNotOfflineOrSignedOut() {
        val store = signedIn()
        val before = store.map.toMap()
        val refused = failing(TokenError(TokenError.Kind.BAD_CLIENT, "Google doesn’t accept the Client ID or secret saved on this TV."))
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now, refused)
        assertEquals(TokenResult.Rejected("Google doesn’t accept the Client ID or secret saved on this TV."), r)
        // The sign-in is kept, and marked: "Keys not accepted" (written once, not on every refusal).
        assertEquals(before + (Grant.DRIVE.refusedKey to now.toString()), store.map)
        GoogleAuth.refresh(store, Grant.DRIVE, now + 5_000, refused)
        assertEquals(now.toString(), store.get(Grant.DRIVE.refusedKey))
        // Fixed in Google Cloud: the next renewal works, and the mark goes.
        val r2 = GoogleAuth.refresh(store, Grant.DRIVE, now + 10_000) { JSONObject().put("access_token", "works").put("expires_in", 3599) }
        assertEquals(TokenResult.Ok("works"), r2)
        assertNull(store.get(Grant.DRIVE.refusedKey))
    }

    @Test fun withoutASignInOrKeysGoogleIsNotAsked() {
        var asked = false
        val call: (List<Pair<String, String>>) -> JSONObject = { asked = true; JSONObject() }
        assertEquals(TokenResult.SignedOut, GoogleAuth.refresh(MapTokens(), Grant.DRIVE, now, call))
        // Signed in, but the Client ID can't be read just now (removing the keys removes the sign-ins
        // with them): offline, tried again later, never "signed out" or "keys not accepted".
        val noClient = signedIn().apply { remove(GoogleAuth.K_CLIENT_ID) }
        assertTrue(GoogleAuth.refresh(noClient, Grant.DRIVE, now, call) is TokenResult.Offline)
        assertEquals("refresh-1", noClient.get(Grant.DRIVE.refreshKey))
        assertFalse(asked)
    }

    @Test fun aSignInTheKeystoreCantReadYetIsOfflineNotSignedOut() = runBlocking {
        // A power cut: the TV is back, its Keystore not yet. Every token reads as unset for now.
        val store = signedIn().asleep()
        val before = store.map.toMap()
        val heard = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(300) { GoogleAuth.expirations.first() } }
        var asked = false
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) { asked = true; JSONObject() }
        // Tried again later, like Google not answering: never the sign-in QR code, never Google asked.
        assertTrue(r is TokenResult.Offline)
        assertFalse(asked)
        // Nothing ended, nothing removed, nobody told it ended.
        assertEquals(before, store.map)
        assertNull(heard.await())
        // The key turns up: the very same sign-in renews.
        store.unreadable.clear()
        assertEquals(TokenResult.Ok("new"), GoogleAuth.refresh(store, Grant.DRIVE, now) { JSONObject().put("access_token", "new").put("expires_in", 3599) })
    }

    @Test fun theKeystoreWakingIsNotGoogleOutOfReach() {
        // Offline because the sign-in, or the Client ID it needs, can't be read yet: its own kind, which the health
        // report doesn't take for "Can't reach Google" (Google was never asked).
        assertTrue(GoogleAuth.keysUnreadable(GoogleAuth.refresh(signedIn().asleep(), Grant.DRIVE, now) { JSONObject() }))
        val clientAsleep = signedIn().apply { unreadable += GoogleAuth.K_CLIENT_ID }
        assertTrue(GoogleAuth.keysUnreadable(GoogleAuth.refresh(clientAsleep, Grant.DRIVE, now) { JSONObject() }))
        // Google not answering is.
        val noAnswer = GoogleAuth.refresh(signedIn(), Grant.DRIVE, now, failing(SocketTimeoutException("timeout")))
        assertTrue(noAnswer is TokenResult.Offline)
        assertFalse(GoogleAuth.keysUnreadable(noAnswer))
        assertFalse(GoogleAuth.keysUnreadable(TokenResult.SignedOut))
    }

    @Test fun aRefreshTokenReadByAnotherThreadMeanwhileIsUsed() {
        // The key turned up between the first look and asking whether it's unreadable: another
        // thread read it (so it isn't "unreadable" any more). Read again, it's there.
        val values = signedIn().map
        val store = object : GoogleAuth.Tokens {
            var looks = 0
            override fun get(name: String): String? = if (name == Grant.DRIVE.refreshKey && looks++ == 0) null else values[name]
            override fun put(name: String, value: String) { values[name] = value }
            override fun remove(vararg names: String) { names.forEach { values.remove(it) } }
        }
        val r = GoogleAuth.refresh(store, Grant.DRIVE, now) { JSONObject().put("access_token", "new").put("expires_in", 3599) }
        assertEquals(TokenResult.Ok("new"), r)
        assertEquals("refresh-1", values[Grant.DRIVE.refreshKey])
    }

    @Test fun whereASignInStandsWhileTheKeystoreWakes() {
        // Read as Settings → Accounts (and Streaming, the cameras, the calendar) read it.
        assertEquals(GoogleAuth.SignIn.SIGNED_IN, GoogleAuth.signIn(signedIn().asleep(), Grant.DRIVE))
        assertEquals(GoogleAuth.SignIn.SIGNED_IN, GoogleAuth.signIn(signedIn().asleep(), Grant.NEST))
        // The keys refused at the last renewal: still that.
        val refused = signedIn().apply { put(Grant.DRIVE.refusedKey, now.toString()) }.asleep()
        assertEquals(GoogleAuth.SignIn.KEYS_REFUSED, GoogleAuth.signIn(refused, Grant.DRIVE))
        // Google ended it before the power cut: "Sign-in ended", so the panel asks to sign in *again*.
        val ended = signedIn().apply { remove(*Grant.DRIVE.stored); put(Grant.DRIVE.endedKey, now.toString()) }.asleep()
        assertEquals(GoogleAuth.SignIn.ENDED, GoogleAuth.signIn(ended, Grant.DRIVE))
        // Keys, and never a sign-in: "Not signed in", not "Not set up".
        assertEquals(GoogleAuth.SignIn.NOT_SIGNED_IN, GoogleAuth.signIn(signedIn().asleep(), Grant.CALENDAR))
        // Nothing is made up for what was never kept.
        assertEquals(GoogleAuth.SignIn.NOT_SET_UP, GoogleAuth.signIn(MapTokens().asleep(), Grant.DRIVE))
        // Read as before once the Keystore has its key; a blank Client ID is still no keys.
        assertEquals(GoogleAuth.SignIn.SIGNED_IN, GoogleAuth.signIn(signedIn(), Grant.DRIVE))
        val blank = MapTokens(GoogleAuth.K_CLIENT_ID to " ", GoogleAuth.K_CLIENT_SECRET to "secret")
        assertFalse(GoogleAuth.configured(blank))
        assertEquals(GoogleAuth.SignIn.NOT_SET_UP, GoogleAuth.signIn(blank, Grant.DRIVE))
    }

    @Test fun whereASignInStands() {
        assertEquals(GoogleAuth.SignIn.SIGNED_IN, GoogleAuth.signInOf(configured = true, connected = true, ended = false, refused = false))
        assertEquals(GoogleAuth.SignIn.KEYS_REFUSED, GoogleAuth.signInOf(configured = true, connected = true, ended = false, refused = true))
        assertEquals(GoogleAuth.SignIn.ENDED, GoogleAuth.signInOf(configured = true, connected = false, ended = true, refused = false))
        assertEquals(GoogleAuth.SignIn.NOT_SIGNED_IN, GoogleAuth.signInOf(configured = true, connected = false, ended = false, refused = false))
        assertEquals(GoogleAuth.SignIn.NOT_SET_UP, GoogleAuth.signInOf(configured = false, connected = false, ended = false, refused = false))
        // Signed in again since it ended: signed in.
        assertEquals(GoogleAuth.SignIn.SIGNED_IN, GoogleAuth.signInOf(configured = true, connected = true, ended = true, refused = false))
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
        // No OAuth error at all: a Wi-Fi sign-in page or a proxy answered, not Google.
        assertEquals(TokenError.Kind.UNAVAILABLE, GoogleAuth.tokenErrorKind(403, ""))
        assertEquals(TokenError.Kind.UNAVAILABLE, GoogleAuth.tokenErrorKind(302, ""))
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
