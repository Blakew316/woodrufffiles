package com.woodruff.tvexplorer.tvs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.SignIn
import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenResult
import com.woodruff.tvexplorer.cloud.signInWords
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.StandInKeystore
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
 * Each sign-in has one record of what happened to it, GoogleAuth's own, kept encrypted with its tokens: Google
 * ending it (and when), or turning down the keys. A renewal that finds out writes it once and posts the TV's quiet
 * notice once; Settings → Accounts, the phone's Connections and the health report all read that record with one set
 * of words, so after a restart they still agree. Renewals go through GoogleAuth's own path, with Google's answers
 * stood in for (tests never reach Google), over the real encrypted store and a stand-in Keystore.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SignInRecordsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val secrets = StandInKeystore(app)
    private val endpoint = GoogleAuth.tokenEndpoint

    @Before fun clean() {
        AppVisibility.foreground = false
        Notices.load(app)
        Notices.clear(app)
        Notices.dismissLatest()
        Notices.prefs(app).edit().clear().commit()
    }

    @After fun putBack() {
        GoogleAuth.tokenEndpoint = endpoint
        secrets.putBack()
        Health.forget()
        AppVisibility.foreground = false
    }

    /** The Google keys and [grant]'s sign-in, its access token not got yet (the next ask renews it). */
    private fun signedIn(grant: Grant) = SecretStore.get(app).run {
        put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
        put(GoogleAuth.K_CLIENT_SECRET, "secret")
        put(grant.refreshKey, "refresh-${grant.name.lowercase()}")
    }

    /** Google's token endpoint answering with [kind] of refusal. */
    private fun googleRefuses(kind: GoogleAuth.TokenError.Kind) {
        GoogleAuth.tokenEndpoint = { throw GoogleAuth.TokenError(kind, "Google sign-in failed") }
    }

    @Test fun anEndingIsOneRecordTheNoticeAccountsAndTheReportAllGoBy() {
        secrets.use()
        signedIn(Grant.CALENDAR)
        googleRefuses(GoogleAuth.TokenError.Kind.REVOKED)
        assertEquals(TokenResult.SignedOut, GoogleAuth.blockingTokenResult(app, Grant.CALENDAR))
        val at = GoogleAuth.endedAt(app, Grant.CALENDAR)
        assertTrue("kept, with when", at != null)
        // Whatever asks again (the player, the doorbell, a screen): nothing more is written or posted.
        assertEquals(TokenResult.SignedOut, GoogleAuth.blockingTokenResult(app, Grant.CALENDAR))
        assertEquals(at, GoogleAuth.endedAt(app, Grant.CALENDAR))
        assertEquals("the TV's quiet notice, once", listOf("Connect Google Calendar again"), Notices.items.value.map { it.title })

        // Accounts, the phone's Connections and the report: one record, one set of words.
        assertEquals(SignIn.ENDED, GoogleAuth.signIn(app, Grant.CALENDAR))
        assertEquals(SignInFact(SignInState.EXPIRED, at), SignIns.account(app, HealthGrant.CALENDAR))
        assertEquals(SignInFact(SignInState.EXPIRED, at), SignIns.state(app, HealthGrant.CALENDAR))
        assertEquals("Sign-in ended", signInWords(Grant.CALENDAR, SignIn.ENDED))
        assertEquals("Sign-in ended", SignInState.EXPIRED.words(HealthGrant.CALENDAR))

        // After a restart they still agree, by the same time.
        secrets.use()
        assertEquals(SignIn.ENDED, GoogleAuth.signIn(app, Grant.CALENDAR))
        val report = Health.report(app, fresh = true)
        assertEquals(SignInRow(SignInState.EXPIRED, "Sign-in ended", at), report.signIns.getValue(HealthGrant.CALENDAR))
        assertTrue(report.attention.any { it.id == "signin.calendar" })
        assertEquals("what the hub tells its phones by", at, report.summary.ended["calendar"])
    }

    @Test fun keysGoogleRefusesAreKeptTooSoAccountsAndTheReportAgreeAfterARestart() {
        secrets.use()
        signedIn(Grant.DRIVE)
        googleRefuses(GoogleAuth.TokenError.Kind.BAD_CLIENT)
        assertTrue(GoogleAuth.blockingTokenResult(app, Grant.DRIVE) is TokenResult.Rejected)
        assertEquals(SignIn.KEYS_REFUSED, GoogleAuth.signIn(app, Grant.DRIVE))

        // A restart: nothing of that renewal is needed, the record says it.
        secrets.use()
        assertEquals(SignIn.KEYS_REFUSED, GoogleAuth.signIn(app, Grant.DRIVE))
        assertEquals(SignInState.KEYS_REJECTED, SignIns.account(app, HealthGrant.DRIVE).state)
        assertEquals(SignInState.KEYS_REJECTED, SignIns.state(app, HealthGrant.DRIVE).state)
        val report = Health.report(app, fresh = true)
        assertEquals("Keys not accepted", report.signIns.getValue(HealthGrant.DRIVE).text)
        assertEquals("Keys not accepted", signInWords(Grant.DRIVE, SignIn.KEYS_REFUSED))
        assertTrue(report.attention.any { it.id == "signin.keys" })

        // The keys fixed in Google Cloud: the next renewal works, and both say signed in again.
        GoogleAuth.tokenEndpoint = { JSONObject().put("access_token", "new").put("expires_in", 3599) }
        assertEquals(TokenResult.Ok("new"), GoogleAuth.blockingTokenResult(app, Grant.DRIVE))
        assertEquals(SignIn.SIGNED_IN, GoogleAuth.signIn(app, Grant.DRIVE))
        assertEquals(SignInState.SIGNED_IN, SignIns.account(app, HealthGrant.DRIVE).state)
        assertFalse(Health.report(app, fresh = true).attention.any { it.id.startsWith("signin") })
    }
}
