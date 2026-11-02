package com.woodruff.tvexplorer.tvs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.PhoneSignIn
import com.woodruff.tvexplorer.server.watchState
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import com.woodruff.tvexplorer.util.NetworkWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The health report, the phone's Connections and Watch, and a sign-in from the phone, just after a power cut while
 * the TV's Keystore is still waking and every saved secret reads as unset for now: a signed-in TV is still signed
 * in, its keys are still saved, and a token asked meanwhile isn't taken for Google being out of reach. Over the
 * real encrypted store and a stand-in Keystore holding a real AES key; time is a counter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlowKeystoreHealthTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val base = "http://living-room.local:8080"

    /** The TV's Keystore: has the key, but says it has none while [missing] (just after the TV turned on). */
    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private var now = 90_000_000L

    private fun run() = SecretStore(app, vault, onMainThread = { false }, clock = { now }, pause = {})

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
        CompanionHub.googleAttempts.clear()
        Health.forget()
    }

    /** Signed in to Drive, the cameras and the calendar, with every key saved, as before the power cut. */
    private fun signedInBeforeThePowerCut() {
        run().apply {
            put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
            put(GoogleAuth.K_CLIENT_SECRET, "secret")
            put(Grant.DRIVE.refreshKey, "refresh-1")
            put(GoogleAuth.K_NEST_PROJECT, "1a2b3c4d-1111-2222-3333-444455556666")
            put(Grant.NEST.refreshKey, "nest-refresh")
            put(Grant.CALENDAR.refreshKey, "calendar-refresh")
            put("tmdb.key", "tmdb-key")
        }
    }

    /** A sign-in that's still there, as the report says it: signed in, or "Offline" while the TV has no network. */
    private fun stillSignedIn(s: SignInState) = s == SignInState.SIGNED_IN || s == SignInState.OFFLINE

    @Test fun aSignedInTvStaysSignedInWithItsKeysSavedWhileItsKeystoreWakes() {
        signedInBeforeThePowerCut()
        vault.missing = true
        use(run())

        // A token asked meanwhile (the calendar starting, a report's look): offline, for the Keystore. Google wasn't
        // asked, so it isn't recorded as Google being out of reach ("Can't reach Google" for 15 minutes).
        val asked = GoogleAuth.lastAsk(Grant.CALENDAR)
        assertTrue(GoogleAuth.blockingTokenResult(app, Grant.CALENDAR) is GoogleAuth.TokenResult.Offline)
        assertSame(asked, GoogleAuth.lastAsk(Grant.CALENDAR))

        // The phone's Connections (and Settings' words): signed in; the cameras' project counts as saved.
        assertEquals(SignInState.SIGNED_IN, SignIns.account(app, HealthGrant.DRIVE).state)
        assertEquals(SignInState.SIGNED_IN, SignIns.account(app, HealthGrant.CAMERAS).state)
        assertEquals(SignInState.SIGNED_IN, SignIns.account(app, HealthGrant.CALENDAR).state)
        assertEquals("never signed in, so not made up", SignInState.NOT_SIGNED_IN, SignIns.account(app, HealthGrant.PHOTOS).state)

        // The health report: still signed in, nothing needing attention, every key saved.
        for (g in listOf(HealthGrant.DRIVE, HealthGrant.CAMERAS, HealthGrant.CALENDAR)) {
            val s = SignIns.state(app, g).state
            assertTrue("$g: $s", stillSignedIn(s))
        }
        val report = Health.report(app, fresh = true)
        assertEquals("All saved", report.keys.text)
        assertTrue(report.keys.googleClientId && report.keys.googleClientSecret && report.keys.camerasProject && report.keys.tmdb)
        assertFalse(report.attention.any { it.id.startsWith("signin") })

        // Watch on the phone: the saved library, not "Sign In to Google Drive".
        val watch = watchState(SignIns.state(app, HealthGrant.DRIVE).state, saved = true, empty = false, listing = false)
        assertEquals(if (NetworkWatch.online.value) "ready" else "offline", watch)

        // A sign-in from the phone: not "add the keys first", but "try again in a moment" (Google's page needs them read).
        val cameras = PhoneSignIn.start(app, Grant.NEST, base)
        assertTrue("$cameras", cameras is PhoneSignIn.Start.CantStart)
        assertEquals("${TvLinks.name(app)} can’t read its Google keys just now. Try again in a moment.", (cameras as PhoneSignIn.Start.CantStart).message)
        assertTrue(PhoneSignIn.start(app, Grant.PHOTOS, base) is PhoneSignIn.Start.CantStart)
        // The Keystore wakes: it starts.
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
        assertTrue(PhoneSignIn.start(app, Grant.PHOTOS, base) is PhoneSignIn.Start.Ready)
    }

    @Test fun aTvThatWasNeverSetUpStillSaysSo() {
        // Only a TMDB key: the Keystore waking finds no Google keys or project to stand in for.
        run().put("tmdb.key", "tmdb-key")
        vault.missing = true
        use(run())
        assertEquals(SignInState.NOT_SET_UP, SignIns.state(app, HealthGrant.DRIVE).state)
        assertEquals(SignInState.NOT_SET_UP, SignIns.state(app, HealthGrant.CAMERAS).state)
        assertEquals("2 keys not set", Health.report(app, fresh = true).keys.text)
        val start = PhoneSignIn.start(app, Grant.DRIVE, base)
        assertEquals("keys", (start as PhoneSignIn.Start.Needs).need)
    }
}
