package com.woodruff.tvexplorer.cloud

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Typing the Google keys or the cameras' project in again while the TV's Keystore is still waking after a power cut
 * (when Settings can't show them yet): the saved ones can't be read, so they can't be compared, and saving would take
 * even the same keys for new ones and end every sign-in. It's turned down, in words to act on, and nothing is lost.
 * Over the real encrypted store and a stand-in Keystore holding a real AES key; time is a counter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlowKeystoreKeysTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The TV's Keystore: has the key, but says it has none while [missing] (just after the TV turned on). */
    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private var now = 75_000_000L
    private val clientId = "123-abc.apps.googleusercontent.com"
    private val project = "1a2b3c4d-1111-2222-3333-444455556666"
    private val secrets get() = app.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun run(mainThread: Boolean = false) = SecretStore(app, vault, onMainThread = { mainThread }, clock = { now }, pause = {})

    private var swapped = false
    private var before: SecretStore? = null

    private fun use(store: SecretStore): SecretStore {
        val was = SecretStore.swapForTest(store)
        if (!swapped) { before = was; swapped = true }
        return store
    }

    @After fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        leaveNoSecrets(app)
    }

    /** Set up and signed in to Drive and the cameras, as before the power cut. */
    private fun signedIn() {
        val st = run()
        st.put(GoogleAuth.K_CLIENT_ID, clientId)
        st.put(GoogleAuth.K_CLIENT_SECRET, "secret")
        st.put(GoogleAuth.K_NEST_PROJECT, project)
        st.put(Grant.DRIVE.refreshKey, "drive-refresh")
        st.put(Grant.NEST.refreshKey, "nest-refresh")
        st.put(Grant.CALENDAR.refreshKey, "calendar-refresh")
    }

    private val tokens = listOf(Grant.DRIVE.refreshKey, Grant.NEST.refreshKey, Grant.CALENDAR.refreshKey)

    @Test fun theSameKeysTypedAgainWhileTheKeystoreWakesEndNoSignIn() {
        signedIn()
        vault.missing = true
        use(run(mainThread = true))
        // Settings can tell they're saved, though it can't show them yet.
        assertNull(GoogleAuth.clientId(app))
        assertTrue(GoogleAuth.hasClientId(app))
        assertTrue(GoogleAuth.hasNestProject(app))
        assertTrue(GoogleAuth.keysUnreadableNow(app))
        // Typed in again: turned down, in words to act on, and nothing is removed.
        for (save in listOf<() -> Unit>(
            { GoogleAuth.saveClientId(app, clientId) },
            { GoogleAuth.saveClientSecret(app, "secret") },
            { GoogleAuth.saveClient(app, clientId, "secret") },
            { GoogleAuth.saveNestProject(app, project) },
            { GoogleAuth.saveNestProject(app, null) },
        )) {
            val e = assertThrows(IllegalArgumentException::class.java) { save() }
            assertEquals("This TV can’t read its keys just yet. Try again in a moment.", e.message)
        }
        tokens.forEach { assertTrue("$it kept", secrets.contains(it)) }
        assertTrue(secrets.contains(GoogleAuth.K_NEST_PROJECT))
        // The Keystore wakes: the same keys again change nothing, and every sign-in stays.
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
        assertFalse(GoogleAuth.keysUnreadableNow(app))
        GoogleAuth.saveClient(app, clientId, "secret")
        GoogleAuth.saveNestProject(app, project)
        val next = run()
        assertEquals("drive-refresh", next.get(Grant.DRIVE.refreshKey))
        assertEquals("nest-refresh", next.get(Grant.NEST.refreshKey))
        assertEquals("calendar-refresh", next.get(Grant.CALENDAR.refreshKey))
        assertEquals(clientId, next.get(GoogleAuth.K_CLIENT_ID))
    }

    @Test fun newKeysStillMeanSigningInAgainOnceTheyCanBeRead() {
        signedIn()
        use(run())
        GoogleAuth.saveClientId(app, "456-new.apps.googleusercontent.com")
        tokens.forEach { assertFalse("$it ended with the old client", secrets.contains(it)) }
        assertEquals("secret", GoogleAuth.clientSecret(app))
        GoogleAuth.saveNestProject(app, "9f8e7d6c-1111-2222-3333-444455556666")
        assertFalse(GoogleAuth.isConnected(app, Grant.NEST))
    }

    @Test fun aSignInThatCantBeReadYetIsWaitingNotOutOfReach() {
        signedIn()
        vault.missing = true
        use(run())
        // Its token is offline, but Google was never asked: the screens wait calmly rather than say "Can't reach Google".
        assertTrue(GoogleAuth.blockingTokenResult(app) is GoogleAuth.TokenResult.Offline)
        assertTrue(GoogleAuth.waitingForKeys(app, Grant.DRIVE))
        assertTrue(GoogleAuth.waitingForKeys(app, Grant.NEST))
        assertTrue(GoogleAuth.waitingForKeys(app, Grant.CALENDAR))
        // The Keystore wakes: it isn't any more.
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
        assertFalse(GoogleAuth.waitingForKeys(app, Grant.DRIVE))
        assertFalse(GoogleAuth.waitingForKeys(app, Grant.NEST))
    }

    @Test fun aTvNeverSignedInWaitsForNothing() {
        run().put("tmdb.key", "abc123")
        vault.missing = true
        use(run())
        assertFalse(GoogleAuth.waitingForKeys(app, Grant.DRIVE))
        assertFalse(GoogleAuth.waitingForKeys(app, Grant.NEST))
    }

    @Test fun aTvNeverSetUpSavesItsFirstKeysAsBefore() {
        // Only a TMDB key saved, and the Keystore waking: no Google key is kept, so none is waited for.
        run().put("tmdb.key", "abc123")
        vault.missing = true
        use(run(mainThread = true))
        assertFalse(GoogleAuth.hasClientId(app))
        assertFalse(GoogleAuth.keysUnreadableNow(app))
        GoogleAuth.saveClient(app, clientId, "secret")
        assertEquals(clientId, GoogleAuth.clientId(app))
    }
}
