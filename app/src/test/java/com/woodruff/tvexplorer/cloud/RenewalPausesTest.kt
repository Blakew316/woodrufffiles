package com.woodruff.tvexplorer.cloud

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.RenewalPauses
import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenError
import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenResult
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.StandInKeystore
import com.woodruff.tvexplorer.tvs.Health
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A renewal Google turned down (busy, failing, or refusing the keys) is given again for half a minute
 * to whatever asks, without asking Google: the player asks for a token on every request to Drive, and
 * with the keys refused each one asked Google's sign-in server again. A new sign-in, a new run of the
 * app, and a renewal that never got an answer (the TV's own network) all ask again at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RenewalPausesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val secrets = StandInKeystore(app)
    private val endpoint = GoogleAuth.tokenEndpoint
    private val run = Any()
    private val refused = TokenResult.Rejected("Google doesn’t accept the Client ID or secret saved on this TV.")
    private val busy = TokenResult.Offline(TokenError(TokenError.Kind.UNAVAILABLE, "busy"))

    @After fun putBack() {
        GoogleAuth.tokenEndpoint = endpoint
        secrets.putBack()
        Health.forget()
    }

    @Test fun aRefusalIsGivenAgainForHalfAMinuteWithoutAskingGoogle() {
        val pauses = RenewalPauses()
        pauses.note(run, "refresh-1", Grant.DRIVE, now = 1_000, answer = refused)
        assertEquals(refused, pauses.answer(run, "refresh-1", Grant.DRIVE, now = 1_000))
        assertEquals(refused, pauses.answer(run, "refresh-1", Grant.DRIVE, now = 30_999))
        // Half a minute on: Google is asked again.
        assertNull(pauses.answer(run, "refresh-1", Grant.DRIVE, now = 31_000))
        // Google busy or failing (5xx, 429) too.
        pauses.note(run, "refresh-1", Grant.DRIVE, now = 50_000, answer = busy)
        assertEquals(busy, pauses.answer(run, "refresh-1", Grant.DRIVE, now = 60_000))
        // Only that sign-in's: the cameras' isn't held back by Drive's.
        assertNull(pauses.answer(run, "refresh-1", Grant.NEST, now = 60_000))
    }

    @Test fun aNewSignInOrANewRunAsksAtOnce() {
        val pauses = RenewalPauses()
        pauses.note(run, "refresh-1", Grant.DRIVE, now = 1_000, answer = refused)
        assertNull("signed in again", pauses.answer(run, "refresh-2", Grant.DRIVE, now = 2_000))
        assertNull("Woodruff started again", pauses.answer(Any(), "refresh-1", Grant.DRIVE, now = 2_000))
    }

    @Test fun aTokenOrAnEndingClearsIt() {
        val pauses = RenewalPauses()
        pauses.note(run, "refresh-1", Grant.DRIVE, now = 1_000, answer = refused)
        pauses.note(run, "refresh-1", Grant.DRIVE, now = 2_000, answer = TokenResult.Ok("new"))
        assertNull(pauses.answer(run, "refresh-1", Grant.DRIVE, now = 3_000))
        pauses.note(run, "refresh-1", Grant.DRIVE, now = 4_000, answer = refused)
        pauses.note(run, "refresh-1", Grant.DRIVE, now = 5_000, answer = TokenResult.SignedOut)
        assertNull(pauses.answer(run, "refresh-1", Grant.DRIVE, now = 6_000))
    }

    @Test fun noAnswerAtAllIsNotPaused() {
        // No network, or the Keystore still waking: the TV's own, asked again the moment it may work.
        val pauses = RenewalPauses()
        for (answer in listOf(
            TokenResult.Offline(TokenError(TokenError.Kind.NETWORK, "no answer")),
            TokenResult.Offline(IOException("reset")),
            TokenResult.Offline(GoogleAuth.KeysUnreadable()),
        )) {
            pauses.note(run, "refresh-1", Grant.DRIVE, now = 1_000, answer = answer)
            assertNull("$answer", pauses.answer(run, "refresh-1", Grant.DRIVE, now = 1_001))
        }
    }

    @Test fun thePlayersRequestsAskGoogleOnceWhileItRefusesTheKeys() {
        secrets.use()
        SecretStore.get(app).run {
            put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
            put(GoogleAuth.K_CLIENT_SECRET, "secret")
            put(Grant.DRIVE.refreshKey, "refresh-drive")
        }
        var asked = 0
        GoogleAuth.tokenEndpoint = { asked++; throw TokenError(TokenError.Kind.BAD_CLIENT, "Google sign-in failed") }
        // A film's requests to Drive, one after another, each asking for a token.
        repeat(8) { assertTrue(GoogleAuth.blockingTokenResult(app, Grant.DRIVE) is TokenResult.Rejected) }
        assertEquals("Google's sign-in server asked once", 1, asked)
        // The keys fixed and Woodruff started again: asked at once, and the token comes.
        secrets.use()
        GoogleAuth.tokenEndpoint = { asked++; JSONObject().put("access_token", "new").put("expires_in", 3599) }
        assertEquals(TokenResult.Ok("new"), GoogleAuth.blockingTokenResult(app, Grant.DRIVE))
        assertEquals(2, asked)
    }

    @Test fun googleBusyIsAskedOnceInHalfAMinuteToo() {
        secrets.use()
        SecretStore.get(app).run {
            put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
            put(GoogleAuth.K_CLIENT_SECRET, "secret")
            put(Grant.DRIVE.refreshKey, "refresh-drive")
        }
        var asked = 0
        GoogleAuth.tokenEndpoint = { asked++; throw TokenError(TokenError.Kind.UNAVAILABLE, "Google’s sign-in service is busy.") }
        repeat(5) { assertTrue(GoogleAuth.blockingTokenResult(app, Grant.DRIVE) is TokenResult.Offline) }
        assertEquals(1, asked)
        // No answer at all (the TV's network): asked every time, so it works the moment the network is back.
        GoogleAuth.tokenEndpoint = { asked++; throw TokenError(TokenError.Kind.NETWORK, "no network in tests") }
        secrets.use() // a fresh run: nothing paused
        repeat(3) { assertTrue(GoogleAuth.blockingTokenResult(app, Grant.DRIVE) is TokenResult.Offline) }
        assertEquals(4, asked)
    }
}
