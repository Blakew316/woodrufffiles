package com.woodruff.tvexplorer.cloud

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.SourceScan
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.StandInKeystore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Signing out on a TV, new keys and a reset only forget the sign-ins here. A Photos or Calendar sign-in from the
 * Woodruff app is the very token every phone, Mac and TV holds, and they all sign in with the family's one client:
 * revoking it at Google ended it on every device. Nothing in the app asks Google to revoke a token.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SignOutForgetsTest {
    private val c: Application = ApplicationProvider.getApplicationContext()
    private val secrets = StandInKeystore(c)
    private val id = "123-abc.apps.googleusercontent.com"

    @Before fun fresh() {
        secrets.use()
        SecretStore.get(c).apply { put(GoogleAuth.K_CLIENT_ID, id); put(GoogleAuth.K_CLIENT_SECRET, "secret") }
    }

    @After fun putBack() = secrets.putBack()

    /** [grant] signed in on this TV, with an access token, its scopes and both marks kept. */
    private fun signedIn(grant: Grant) = SecretStore.get(c).run {
        put(grant.refreshKey, "refresh-${grant.name}")
        put(grant.accessKey, "access-${grant.name}")
        put(grant.expiresKey, "99999999999999")
        put(grant.scopesKey, grant.scope)
        put(grant.atKey, "1000")
        put(grant.endedKey, "900")
        put(grant.refusedKey, "950")
    }

    private fun nothingKept(grant: Grant) {
        val st = SecretStore.get(c)
        for (name in grant.stored + grant.marks) assertNull("$name is forgotten", st.get(name))
        assertFalse(GoogleAuth.isConnected(c, grant))
    }

    @Test fun signingOutForgetsThatSignInHereAndKeepsTheRest() = runBlocking {
        Grant.entries.forEach(::signedIn)
        GoogleAuth.signOut(c, Grant.PHOTOS)
        nothingKept(Grant.PHOTOS)
        // "Not signed in", not "Sign-in ended": the TV signed itself out.
        assertEquals(GoogleAuth.SignIn.NOT_SIGNED_IN, GoogleAuth.signIn(c, Grant.PHOTOS))
        // The other sign-ins and the keys are as they were.
        for (other in Grant.entries - Grant.PHOTOS) assertEquals("refresh-${other.name}", SecretStore.get(c).get(other.refreshKey))
        assertEquals(id, GoogleAuth.clientId(c))
        assertEquals("secret", SecretStore.get(c).get(GoogleAuth.K_CLIENT_SECRET))
    }

    @Test fun signingOutOfDriveStillTellsTheLibrary() = runBlocking {
        signedIn(Grant.DRIVE)
        val told = async(start = CoroutineStart.UNDISPATCHED) { GoogleAuth.driveSignOuts.first() }
        GoogleAuth.signOut(c, Grant.DRIVE)
        withTimeout(3_000) { told.await() }
        nothingKept(Grant.DRIVE)
    }

    @Test fun aSignInFromTheWoodruffAppIsOnlyForgottenHere() = runBlocking {
        assertTrue(GoogleAuth.receive(c, Grant.CALENDAR, "the-family-token", Grant.CALENDAR.scope, 2_000L))
        GoogleAuth.signOut(c, Grant.CALENDAR)
        nothingKept(Grant.CALENDAR)
        // Still good everywhere else, so the Woodruff app handing it over again connects this TV again.
        assertTrue(GoogleAuth.receive(c, Grant.CALENDAR, "the-family-token", Grant.CALENDAR.scope, 2_000L))
        assertTrue(GoogleAuth.isConnected(c, Grant.CALENDAR))
    }

    @Test fun newKeysAndAResetForgetEverySignIn() {
        Grant.entries.forEach(::signedIn)
        GoogleAuth.saveClient(c, "456-def.apps.googleusercontent.com", "other")
        Grant.entries.forEach(::nothingKept)
        Grant.entries.forEach(::signedIn)
        GoogleAuth.resetClient(c)
        Grant.entries.forEach(::nothingKept)
        assertNull(GoogleAuth.clientId(c))
    }

    @Test fun nothingAsksGoogleToRevokeAToken() {
        val revokes = SourceScan.matches(Regex("""oauth2\.googleapis\.com/revoke|/o/oauth2/revoke|accounts\.google\.com/.*revoke""", RegexOption.IGNORE_CASE))
        assertEquals("No sign-in is revoked at Google, only forgotten on this TV", emptyList<String>(), revokes)
    }
}
