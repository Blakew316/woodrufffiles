package com.woodruff.tvexplorer.cloud

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.SignIn
import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenResult
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import com.woodruff.tvexplorer.util.isNetworkFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Sign-ins just after a power cut, while the TV's Keystore is still waking up and every saved secret
 * reads as unset for now: a signed-in TV stays signed in. Streaming shows the library, not the
 * sign-in QR code; Settings says "Signed in"; tokens are "offline" (tried again soon), never "signed
 * out"; nothing saved is lost. Read through GoogleAuth's own calls, over the real encrypted store and
 * a stand-in Keystore holding a real AES key (as SecretStoreTest's); time is a counter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlowKeystoreSignInTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The TV's Keystore: has the key, but says it has none while [missing] (just after the TV turned on). */
    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private var now = 70_000_000L
    private val project = "1a2b3c4d-1111-2222-3333-444455556666"

    /** A run of the app over the same saved values; [mainThread]: reads that never wait, as the screens' are. */
    private fun run(mainThread: Boolean = false) = SecretStore(app, vault, onMainThread = { mainThread }, clock = { now }, pause = {})

    private var swapped = false
    private var before: SecretStore? = null

    /** GoogleAuth reads [store] from now on (put back after each test). */
    private fun use(store: SecretStore): SecretStore {
        val was = SecretStore.swapForTest(store)
        if (!swapped) { before = was; swapped = true }
        return store
    }

    @After fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        leaveNoSecrets(app)
    }

    /** Signed in to Drive (its access token ran out), the cameras and the calendar, as before the power cut. */
    private fun signedInBeforeThePowerCut() {
        val st = run()
        st.put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
        st.put(GoogleAuth.K_CLIENT_SECRET, "secret")
        st.put(Grant.DRIVE.refreshKey, "refresh-1")
        st.put(Grant.DRIVE.accessKey, "old-access")
        st.put(Grant.DRIVE.expiresKey, (System.currentTimeMillis() - 60_000).toString())
        st.put(Grant.DRIVE.scopesKey, Grant.DRIVE.scope)
        st.put(GoogleAuth.K_NEST_PROJECT, project)
        st.put(Grant.NEST.refreshKey, "nest-refresh")
        st.put(Grant.CALENDAR.refreshKey, "calendar-refresh")
    }

    @Test fun aSignedInTvStaysSignedInWhileItsKeystoreWakes() {
        signedInBeforeThePowerCut()
        vault.missing = true
        val waking = use(run())

        // Settings → Accounts, Streaming, the cameras and the calendar read it as signed in.
        assertTrue(GoogleAuth.isConfigured(app))
        assertTrue(GoogleAuth.isConnected(app))
        assertTrue(GoogleAuth.isConnected(app, Grant.NEST))
        assertTrue(GoogleAuth.isConnected(app, Grant.CALENDAR))
        assertFalse("never signed in, so not made up", GoogleAuth.isConnected(app, Grant.PHOTOS))
        assertTrue(GoogleAuth.hasNestProject(app))
        assertTrue("sharing with the other TVs isn't flagged as needing a new sign-in", GoogleAuth.hasScope(app, GoogleAuth.APPDATA))
        assertEquals(SignIn.SIGNED_IN, GoogleAuth.signIn(app, Grant.DRIVE))
        assertEquals(SignIn.SIGNED_IN, GoogleAuth.signIn(app, Grant.NEST))
        assertEquals(SignIn.NOT_SIGNED_IN, GoogleAuth.signIn(app, Grant.PHOTOS))
        assertFalse(GoogleAuth.hasEnded(app, Grant.DRIVE))
        assertFalse(GoogleAuth.keysRefused(app, Grant.DRIVE))

        // Its tokens: offline, tried again soon, never "signed out" (and Google isn't asked).
        assertTrue(GoogleAuth.blockingTokenResult(app) is TokenResult.Offline)
        assertTrue(runBlocking { GoogleAuth.tokenResult(app, Grant.NEST) } is TokenResult.Offline)
        assertNull(GoogleAuth.blockingToken(app))
        try {
            GoogleAuth.requireToken(app, Grant.NEST, signedOut = "Connect your cameras again.")
            fail("a token without a readable sign-in")
        } catch (e: GoogleAuth.AuthException.Offline) {
            assertTrue(isNetworkFailure(e))
        }
        // The cameras' project: offline too, not "Enter your Device Access Project ID".
        try {
            GoogleAuth.requireNestProject(app)
            fail("a project ID that can't be read")
        } catch (e: GoogleAuth.AuthException.Offline) {
            assertTrue(isNetworkFailure(e))
        }

        // The key turns up: every value is as it was, in this run and the next.
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
        assertEquals("refresh-1", waking.get(Grant.DRIVE.refreshKey))
        assertEquals(project, GoogleAuth.requireNestProject(app))
        val next = run()
        assertEquals("refresh-1", next.get(Grant.DRIVE.refreshKey))
        assertEquals("nest-refresh", next.get(Grant.NEST.refreshKey))
        assertEquals("calendar-refresh", next.get(Grant.CALENDAR.refreshKey))
    }

    @Test fun streamingShowsTheLibraryNotTheSignInCode() {
        signedInBeforeThePowerCut()
        vault.missing = true
        // Drive's ViewModel reads its step as it's made, on the main thread, which never waits for the key.
        use(run(mainThread = true))
        val drive = DriveViewModel(app)
        assertEquals(SignInStep.CONNECTED, drive.state.value.step)
        assertFalse(drive.state.value.ended)
    }

    @Test fun aSignInGoogleEndedStillAsksToSignInAgain() {
        val st = run()
        st.put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
        st.put(GoogleAuth.K_CLIENT_SECRET, "secret")
        st.put(Grant.DRIVE.endedKey, "1800000000000")
        vault.missing = true
        use(run(mainThread = true))
        // "Sign in to Google Drive again", not the first-time panel; Settings: "Sign-in ended".
        val drive = DriveViewModel(app)
        assertEquals(SignInStep.SIGN_IN, drive.state.value.step)
        assertTrue(drive.state.value.ended)
        assertEquals(SignIn.ENDED, GoogleAuth.signIn(app, Grant.DRIVE))
        assertTrue(GoogleAuth.hasEnded(app, Grant.DRIVE))
        // When it ended can't be read yet; that it ended can.
        assertNull(GoogleAuth.endedAt(app, Grant.DRIVE))
        // And its token is what it was: there's no sign-in.
        assertEquals(TokenResult.SignedOut, GoogleAuth.blockingTokenResult(app))
    }

    @Test fun nothingIsMadeUpForATvThatWasNeverSetUp() {
        // Only a TMDB key saved: the Keystore waking finds no Google keys to stand in for.
        run().put("tmdb.key", "abc123")
        vault.missing = true
        use(run(mainThread = true))
        assertFalse(GoogleAuth.isConfigured(app))
        assertFalse(GoogleAuth.isConnected(app))
        assertFalse(GoogleAuth.hasNestProject(app))
        assertEquals(SignIn.NOT_SET_UP, GoogleAuth.signIn(app, Grant.DRIVE))
        assertEquals(SignInStep.SETUP, DriveViewModel(app).state.value.step)
    }

    @Test fun aSignInNoKeyCanReadAnyMoreIsSignedOut() {
        // Saved under a key that's gone for good (data restored onto another TV): unset for good,
        // so signed out, not "offline" for ever.
        signedInBeforeThePowerCut()
        vault.create()
        use(run())
        assertFalse(GoogleAuth.isConnected(app))
        assertFalse(GoogleAuth.isConfigured(app))
        assertEquals(SignIn.NOT_SET_UP, GoogleAuth.signIn(app, Grant.DRIVE))
        assertEquals(TokenResult.SignedOut, GoogleAuth.blockingTokenResult(app))
    }
}
