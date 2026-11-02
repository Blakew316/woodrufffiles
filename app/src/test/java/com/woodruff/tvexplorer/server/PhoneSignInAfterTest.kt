package com.woodruff.tvexplorer.server

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.calendar.TodayCalendar
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.SignIn
import com.woodruff.tvexplorer.cloud.SignInStep
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.StandInKeystore
import com.woodruff.tvexplorer.tvs.HealthGrant
import com.woodruff.tvexplorer.tvs.SignInState
import com.woodruff.tvexplorer.tvs.SignIns
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A sign-in started from the phone finishing on the TV, with no screen of its own: GoogleAuth's record of the
 * ending (or of keys refused) goes with the new sign-in, Streaming shows the library again and warms both libraries,
 * Home's calendar stops asking to connect it, and the cameras are listed again with the doorbell listening. The real
 * code exchange runs (GoogleAuth.exchange), with Google's answers stood in for: tokens for the phone's code, and no
 * network for any renewal, so nothing here reaches Google or Drive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneSignInAfterTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val secrets = StandInKeystore(app)
    private val endpoint = GoogleAuth.tokenEndpoint
    private val base = "http://living-room.local:8080"
    private val owner = ViewModelStore()

    /** What Google answers a renewal; the phone's code always gets tokens (good for no time at all, so nothing uses them). */
    private var renewal: () -> JSONObject = { throw GoogleAuth.TokenError(GoogleAuth.TokenError.Kind.NETWORK, "no network in tests") }

    @Before fun setUp() {
        secrets.use()
        SecretStore.get(app).apply {
            put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
            put(GoogleAuth.K_CLIENT_SECRET, "secret")
            put(GoogleAuth.K_NEST_PROJECT, "1a2b3c4d-1111-2222-3333-444455556666")
        }
        GoogleAuth.tokenEndpoint = { params ->
            if (params.toMap()["grant_type"] == "authorization_code") {
                JSONObject().put("access_token", "access-new").put("refresh_token", "refresh-new").put("expires_in", 0)
            } else renewal()
        }
        // Sharing progress would sync with Drive after a Drive sign-in: not here.
        FamilySync.setEnabled(app, false)
    }

    @After fun putBack() {
        owner.clear()
        GoogleAuth.tokenEndpoint = endpoint
        CompanionHub.googleAttempts.clear()
        secrets.putBack()
    }

    /** Runs the main thread's work until [done] (at most 3 s: background work it waits on goes meanwhile). */
    private fun idleUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 3_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < until) { "never: $what" }
            Thread.sleep(10)
        }
    }

    /** The phone's attempt for [grant], and Google's answer with its code reaching this TV. */
    private fun signInFromThePhone(grant: Grant): PhoneSignIn.Start.Ready = runBlocking {
        val ready = PhoneSignIn.begin(app, grant, base)
        val outcome = PhoneSignIn.finish(app, "4/one-time", CompanionHub.googleAttempts.getValue(ready.state))!!.await()
        assertEquals(PhoneSignIn.Outcome.SignedIn, outcome)
        ready
    }

    @Test fun aDriveSignInFromThePhoneEndsTheEndingAndStreamingShowsTheLibraryAgain() {
        // Google ended the Drive sign-in an hour ago: Streaming asks to sign in again.
        SecretStore.get(app).put(Grant.DRIVE.endedKey, (System.currentTimeMillis() - 3_600_000).toString())
        val drive = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[DriveViewModel::class.java]
        assertEquals(SignInStep.SIGN_IN, drive.state.value.step)
        assertTrue(drive.state.value.ended)
        val warmed = drive.prefetchRun

        val ready = signInFromThePhone(Grant.DRIVE)
        assertTrue("the phone's sheet said Again", ready.again)
        // The record went with the new sign-in: Accounts, the report and the phone all say signed in.
        assertEquals(SignIn.SIGNED_IN, GoogleAuth.signIn(app, Grant.DRIVE))
        assertFalse(GoogleAuth.hasEnded(app, Grant.DRIVE))
        assertNull(SignIns.endedAt(app, Grant.DRIVE))
        assertEquals(SignInState.SIGNED_IN, SignIns.account(app, HealthGrant.DRIVE).state)

        // Streaming: the library, nothing left of "Sign in to Google Drive again", and both libraries warming.
        idleUntil("Streaming signed in") { drive.state.value.step == SignInStep.CONNECTED }
        assertFalse(drive.state.value.ended)
        assertNull(drive.state.value.qrUrl)
        val run = drive.prefetchRun
        assertNotSame(warmed, run)
        assertTrue(run!!.isActive)
        // Its listing asked for a token (no network here): settled before Google's answers are put back.
        idleUntil("the listing tried") { drive.state.value.status is DriveStatus.Offline }
    }

    @Test fun keysRefusedGoWithASignInFromThePhoneToo() {
        // Signed in to Photos, but Google turned down the keys renewing it: "Keys not accepted".
        SecretStore.get(app).apply { put(Grant.PHOTOS.refreshKey, "refresh-old"); put(Grant.PHOTOS.refusedKey, "1800000000000") }
        assertEquals(SignIn.KEYS_REFUSED, GoogleAuth.signIn(app, Grant.PHOTOS))
        val ready = signInFromThePhone(Grant.PHOTOS)
        assertFalse(ready.again)
        assertEquals(SignIn.SIGNED_IN, GoogleAuth.signIn(app, Grant.PHOTOS))
        assertFalse(GoogleAuth.keysRefused(app, Grant.PHOTOS))
    }

    @Test fun aCalendarSignInFromThePhoneConnectsHomesCalendarAgain() {
        SecretStore.get(app).put(Grant.CALENDAR.refreshKey, "refresh-old")
        // Google ends it (any renewal finds out): Home's calendar asks to connect it again.
        renewal = { throw GoogleAuth.TokenError(GoogleAuth.TokenError.Kind.REVOKED, "Token has been expired or revoked.") }
        assertEquals(GoogleAuth.TokenResult.SignedOut, GoogleAuth.blockingTokenResult(app, Grant.CALENDAR))
        idleUntil("Home's calendar heard it ended") { TodayCalendar.state.value.ended }
        renewal = { throw GoogleAuth.TokenError(GoogleAuth.TokenError.Kind.NETWORK, "no network in tests") }

        val ready = signInFromThePhone(Grant.CALENDAR)
        assertTrue(ready.again)
        assertFalse(GoogleAuth.hasEnded(app, Grant.CALENDAR))
        idleUntil("Home's calendar connected") { TodayCalendar.state.value.connected && !TodayCalendar.state.value.ended }
        assertNull(TodayCalendar.state.value.qrUrl)
        // Its refresh asked for a token (no network here): settled before Google's answers are put back.
        idleUntil("the calendar tried") { TodayCalendar.state.value.offline }
    }

    @Test fun aCamerasSignInFromThePhoneListsThemAndWakesTheDoorbell() {
        val nudges = Doorbell.nudgesNow()
        signInFromThePhone(Grant.NEST)
        assertEquals(SignIn.SIGNED_IN, GoogleAuth.signIn(app, Grant.NEST))
        idleUntil("the cameras connected") { Nest.state.value.step == NestStep.CONNECTED }
        assertNull(Nest.state.value.qrUrl)
        // The doorbell looks again at once (it listens on this TV from now on), as after the Command Center's code.
        assertTrue(Doorbell.nudgesNow() > nudges)
        // The cameras are listed again (no network here: they wait, offline), settled before Google's answers are put back.
        idleUntil("the cameras' list tried") { Nest.state.value.offline }
    }
}
