package com.woodruff.tvexplorer.server

import com.woodruff.tvexplorer.cloud.GoogleAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException

/** A sign-in started from the phone: where it stands as it goes, and the words for each outcome (plain logic; Android's JSON). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneSignInTest {
    private var now = 1_000_000L
    private val attempts = PhoneAttempts(waitMs = 10 * 60_000L) { now }

    @Test fun anAttemptOnlyMovesForward() {
        attempts.started("a", GoogleAuth.Grant.DRIVE)
        assertEquals(SignInProgress.PENDING, attempts.get("a")!!.progress)
        assertTrue(attempts.finishing("a"))
        // Once Google's answer is being used, nothing can call it replaced, cancelled or finishing again.
        assertFalse(attempts.replaced("a"))
        assertFalse(attempts.cancelled("a"))
        assertFalse(attempts.finishing("a"))
        assertEquals(SignInProgress.FINISHING, attempts.get("a")!!.progress)
        attempts.signedIn("a", weekly = true)
        val done = attempts.get("a")!!
        assertEquals(SignInProgress.SIGNED_IN, done.progress)
        assertTrue(done.weekly)
        // Over: a late failure doesn't undo it.
        attempts.failed("a", "Living Room couldn’t reach Google.")
        assertEquals(SignInProgress.SIGNED_IN, attempts.get("a")!!.progress)
        assertEquals("""{"status":"signedIn","message":null,"weekly":true}""", done.toJson().toString())
    }

    @Test fun replacedCancelledAndFailedSayWhy() {
        attempts.started("r", GoogleAuth.Grant.DRIVE)
        assertTrue(attempts.replaced("r"))
        assertEquals(SignInProgress.REPLACED, attempts.get("r")!!.progress)
        assertFalse("a replaced attempt's answer is never used", attempts.finishing("r"))

        attempts.started("c", GoogleAuth.Grant.NEST)
        assertTrue(attempts.cancelled("c"))
        assertEquals("cancelled", attempts.get("c")!!.toJson().getString("status"))

        attempts.started("f", GoogleAuth.Grant.PHOTOS)
        attempts.finishing("f")
        attempts.failed("f", "Google didn’t accept the answer.")
        val f = attempts.get("f")!!.toJson()
        assertEquals("failed", f.getString("status"))
        assertEquals("Google didn’t accept the answer.", f.getString("message"))
        assertFalse(f.getBoolean("weekly"))
    }

    @Test fun anAttemptNobodyAnsweredExpiresAfterTenMinutes() {
        attempts.started("e", GoogleAuth.Grant.CALENDAR)
        now += 10 * 60_000L
        assertEquals("still waiting at exactly ten minutes", SignInProgress.PENDING, attempts.get("e")!!.progress)
        now += 1
        assertEquals(SignInProgress.EXPIRED, attempts.get("e")!!.progress)
        assertFalse("too late: its answer isn't used", attempts.finishing("e"))
        assertFalse(attempts.cancelled("e"))
        assertEquals(SignInProgress.EXPIRED, attempts.get("e")!!.progress)
    }

    @Test fun oldAttemptsAreForgottenAndTheListStaysSmall() {
        attempts.started("old", GoogleAuth.Grant.DRIVE)
        now += 16 * 60_000L
        attempts.started("new", GoogleAuth.Grant.DRIVE)
        assertNull("forgotten a while after its ten minutes", attempts.get("old"))
        repeat(40) { attempts.started("n$it", GoogleAuth.Grant.DRIVE) }
        assertNull("at most 32 kept: the oldest go first", attempts.get("new"))
        assertEquals(SignInProgress.PENDING, attempts.get("n39")!!.progress)
    }

    @Test fun whyItDidntFinishInPlainWords() {
        val tv = "Living Room"
        fun e(kind: GoogleAuth.TokenError.Kind) = GoogleAuth.TokenError(kind, "Google sign-in failed: invalid_grant")
        assertEquals("Living Room couldn’t reach Google.", exchangeReason(e(GoogleAuth.TokenError.Kind.NETWORK), tv))
        assertEquals("Google’s sign-in is busy right now.", exchangeReason(e(GoogleAuth.TokenError.Kind.UNAVAILABLE), tv))
        assertEquals("Google didn’t accept the answer.", exchangeReason(e(GoogleAuth.TokenError.Kind.REVOKED), tv))
        assertEquals("Google didn’t accept the answer.", exchangeReason(e(GoogleAuth.TokenError.Kind.OTHER), tv))
        assertEquals("Google doesn’t accept the Client ID or secret saved on Living Room.", exchangeReason(e(GoogleAuth.TokenError.Kind.BAD_CLIENT), tv))
        assertEquals("Google didn’t give Living Room a sign-in that lasts.", exchangeReason(IOException("Google didn't return a refresh token. Try signing in again."), tv))
        assertEquals("The Google keys aren’t on Living Room any more.", exchangeReason(IOException("Google isn't set up"), tv))
        assertEquals("Living Room couldn’t reach Google.", exchangeReason(IOException("timeout"), tv))
        assertEquals("Living Room couldn’t finish with Google.", exchangeReason(IllegalStateException("boom"), tv))
        // Never Google's own wording, codes or the banned words.
        for (k in GoogleAuth.TokenError.Kind.entries) {
            val r = exchangeReason(e(k), tv)
            assertFalse(r, Regex("invalid|failed|error|grant", RegexOption.IGNORE_CASE).containsMatchIn(r))
        }
    }

    @Test fun thePageGooglesAnswerLandsOnPointsBackToThePhone() {
        val tv = "Living Room"
        assertEquals("Living Room is signed in ✓" to "Google Drive is back on Living Room. Go back to Woodruff Family on your phone.",
            phonePage(PhonePage.Done(GoogleAuth.Grant.DRIVE), tv))
        assertEquals("Google Calendar connected ✓" to "Today’s events show on Living Room’s Home in a moment. Go back to Woodruff Family on your phone.",
            phonePage(PhonePage.Done(GoogleAuth.Grant.CALENDAR), tv))
        assertEquals("Google Photos connected ✓" to "To add photos, open Family Memories on Living Room and choose Add from Google Photos. Go back to Woodruff Family on your phone.",
            phonePage(PhonePage.Done(GoogleAuth.Grant.PHOTOS), tv))
        assertEquals("Cameras connected ✓" to "Living Room is loading your cameras. Go back to Woodruff Family on your phone.",
            phonePage(PhonePage.Done(GoogleAuth.Grant.NEST), tv))
        assertEquals("Sign-in cancelled" to "Nothing changed. You can try again from your phone.", phonePage(PhonePage.Cancelled, tv))
        assertEquals("This sign-in link is old" to "Start again from your phone or the TV.", phonePage(PhonePage.Old, tv))
        assertEquals("Signing in didn’t finish" to "Google didn’t accept the answer. Go back to Woodruff Family on your phone to try again.",
            phonePage(PhonePage.NotFinished("Google didn’t accept the answer."), tv))
        assertEquals("Finishing on Living Room…" to "Go back to Woodruff Family on your phone. It says when Living Room is signed in.",
            phonePage(PhonePage.Finishing, tv))
    }
}
