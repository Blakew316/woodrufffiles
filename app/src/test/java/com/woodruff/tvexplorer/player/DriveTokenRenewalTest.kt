package com.woodruff.tvexplorer.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenResult
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The player's Drive token after Drive refused it (see [RetryAtOnce]): GoogleAuth drops just that
 * token and keeps the sign-in, so the player's one try asks Google for a new one, which is then
 * handed out. When that renewal finds Google ended the sign-in, it's gone, which is what the player
 * reads before it stops trying and says how to sign in again. Over the real encrypted store and a
 * stand-in Keystore (as SlowKeystoreSignInTest's); Google's token endpoint is a stand-in too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DriveTokenRenewalTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        override fun find(): SecretKey? = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private val store = SecretStore(app, vault, onMainThread = { false }, clock = { 0L }, pause = {})
    private var before: SecretStore? = null
    private val now = System.currentTimeMillis()

    /** GoogleAuth's own view of the same store, for renewing with a stand-in for Google's token endpoint. */
    private val tokens = object : GoogleAuth.Tokens {
        override fun get(name: String) = store.get(name)
        override fun put(name: String, value: String) = store.put(name, value)
        override fun remove(vararg names: String) = store.remove(*names)
        override fun unreadableNow(name: String) = store.isUnreadableNow(name)
    }

    @Before fun signedIn() {
        before = SecretStore.swapForTest(store)
        store.put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
        store.put(GoogleAuth.K_CLIENT_SECRET, "secret")
        store.put(Grant.DRIVE.refreshKey, "refresh-1")
        // Good for most of an hour by the TV's clock, which was off when it was kept: Drive says no.
        store.put(Grant.DRIVE.accessKey, "access-1")
        store.put(Grant.DRIVE.expiresKey, (now + 50 * 60_000).toString())
        store.put(Grant.DRIVE.scopesKey, Grant.DRIVE.scope)
    }

    @After fun putBack() {
        SecretStore.swapForTest(before)
        leaveNoSecrets(app)
    }

    @Test fun aTokenDriveRefusedIsDroppedTheSignInKeptAndTheNextAskRenewsIt() {
        // Handed out without asking Google, request after request: it looks good.
        assertEquals(TokenResult.Ok("access-1"), GoogleAuth.blockingTokenResult(app))

        GoogleAuth.forgetAccess(app)

        assertNull(store.get(Grant.DRIVE.accessKey))
        assertNull(store.get(Grant.DRIVE.expiresKey))
        // Still signed in: Streaming, Settings and the player's own check say so; nothing asks to sign in.
        assertTrue(GoogleAuth.isConnected(app))
        assertEquals(GoogleAuth.SignIn.SIGNED_IN, GoogleAuth.signIn(app))
        assertTrue(GoogleAuth.hasScope(app, GoogleAuth.APPDATA))
        assertFalse(GoogleAuth.hasEnded(app, Grant.DRIVE))

        // The player's next request asks for a token: Google is asked for a new one, once.
        val asked = ArrayList<List<Pair<String, String>>>()
        val renewed = GoogleAuth.refresh(tokens, Grant.DRIVE, now) { params ->
            asked += params
            JSONObject().put("access_token", "access-2").put("expires_in", 3599)
        }
        assertEquals(TokenResult.Ok("access-2"), renewed)
        assertEquals(1, asked.size)
        assertTrue(("refresh_token" to "refresh-1") in asked.single())
        // And from then on, that's the one handed out.
        assertEquals(TokenResult.Ok("access-2"), GoogleAuth.blockingTokenResult(app))
    }

    @Test fun aRenewalThatFindsTheSignInEndedLeavesItSignedOutForThePlayersCheck() {
        GoogleAuth.forgetAccess(app)

        val renewed = GoogleAuth.refresh(tokens, Grant.DRIVE, now) {
            throw GoogleAuth.TokenError(GoogleAuth.TokenError.Kind.REVOKED, "Token has been expired or revoked.")
        }

        assertEquals(TokenResult.SignedOut, renewed)
        // What the player reads (off the main thread) once Drive still says no: signed out. So it stops
        // trying, and the ring's card says how to sign in again.
        assertFalse(GoogleAuth.isConnected(app))
        assertEquals(GoogleAuth.SignIn.ENDED, GoogleAuth.signIn(app))
        assertEquals(
            BufferingLook(ring = false, caption = Buffering.SIGN_IN, detail = Buffering.SIGN_IN_HOW),
            Buffering.look(waitingMs = 3_000, stall = Stall.SIGNED_OUT, stalledMs = 0, reduced = false),
        )
    }

    @Test fun whileTheKeystoreWakesTheTokenIsDroppedAndTheSignInStillCounts() {
        // Just after a power cut nothing kept can be read for a while: Drive got "Bearer " and said no.
        val waking = SecretStore(app, object : SecretStore.KeyVault {
            override fun find(): SecretKey? = null
            override fun create(): SecretKey = throw java.security.KeyStoreException("not ready")
        }, onMainThread = { false }, clock = { 0L }, pause = {})
        SecretStore.swapForTest(waking)

        GoogleAuth.forgetAccess(app)

        // The player's check still counts the sign-in: it keeps trying ("Google Drive turned this
        // down. Trying again…") rather than ask to sign in again.
        assertTrue(GoogleAuth.isConnected(app))
        // And once the key turns up (the next run here), only the refused token is gone: the sign-in renews.
        val awake = SecretStore(app, vault, onMainThread = { false }, clock = { 0L }, pause = {})
        assertEquals("refresh-1", awake.get(Grant.DRIVE.refreshKey))
        assertEquals(Grant.DRIVE.scope, awake.get(Grant.DRIVE.scopesKey))
        assertEquals("123-abc.apps.googleusercontent.com", awake.get(GoogleAuth.K_CLIENT_ID))
        assertNull(awake.get(Grant.DRIVE.accessKey))
    }
}
