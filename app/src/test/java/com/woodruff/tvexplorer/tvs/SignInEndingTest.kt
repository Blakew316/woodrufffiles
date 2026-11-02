package com.woodruff.tvexplorer.tvs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.phones.PhoneInbox
import com.woodruff.tvexplorer.phones.Phones
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.StandInKeystore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Google ending one of this TV's sign-ins, as the phones hear it: by GoogleAuth's one record of the ending (the time
 * the TV's notice, Accounts and the report go by too), told once however often it's heard (as it happened, again by
 * another renewal, by the look for endings missed at start-up and every few minutes), a new ending told again, and
 * two within 8 days "every week" (the phone's Testing tip). A phone without Lock-Screen alerts (no network) holds
 * what it's told in its inbox.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SignInEndingTest {
    private val c: Application = ApplicationProvider.getApplicationContext()
    private val secrets = StandInKeystore(c)
    private val phone = "test-phone-1"
    private val day = 24 * 3_600_000L

    @Before fun fresh() {
        c.getSharedPreferences("health", Context.MODE_PRIVATE).edit().clear().commit()
        TvLinks.forgetForTest()
        secrets.use()
        SecretStore.get(c).apply { put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com"); put(GoogleAuth.K_CLIENT_SECRET, "secret") }
        Phones.hello(c, phone, "Phone", alerts = false)
    }

    @After fun putBack() {
        Phones.remove(c, phone)
        PhoneInbox.settle()
        secrets.putBack()
        TvLinks.forgetForTest()
    }

    /** Google ended [grant]'s sign-in at [at], as GoogleAuth keeps it: its tokens gone, when it ended kept. */
    private fun googleEnded(grant: Grant, at: Long) = SecretStore.get(c).run {
        remove(*grant.stored)
        put(grant.endedKey, at.toString())
    }

    /** Signed in again (from the phone or the TV): the new sign-in's tokens kept, its record of the ending gone. */
    private fun signedInAgain(grant: Grant) = SecretStore.get(c).run {
        remove(grant.endedKey, grant.refusedKey)
        put(grant.refreshKey, "refresh-again")
    }

    /** How many messages this phone was sent about [grant]'s ending at [at] on this TV. */
    private fun told(grant: HealthGrant, at: Long): Int {
        PhoneInbox.settle()
        val all = PhoneInbox.since(c, phone, 0L, "another inbox").getJSONArray("messages")
        val id = "signin:${TvLinks.id(c)}:${grant.json}:$at"
        return (0 until all.length()).count { all.getJSONObject(it).optString("id") == id }
    }

    /** Waits (up to 3 s) until the phone has been told of [grant]'s ending at [at] (told at once: no linked TVs to wait for). */
    private suspend fun awaitTold(grant: HealthGrant, at: Long) {
        assertNotNull("the phone was told", withTimeoutOrNull(3_000) { while (told(grant, at) == 0) delay(20); true })
    }

    @Test fun anEndingIsToldToThePhonesOnceHoweverOftenItsHeard() = runBlocking {
        val at = System.currentTimeMillis() - 60_000
        googleEnded(Grant.CALENDAR, at)
        SignIns.ended(c, Grant.CALENDAR) // heard as it happened
        awaitTold(HealthGrant.CALENDAR, at)
        SignIns.ended(c, Grant.CALENDAR) // another renewal found out too
        SignInAlerts.sync(c) // the look for endings missed (start-up, the 5-minute loop)
        delay(300)
        assertEquals(1, told(HealthGrant.CALENDAR, at))
        // The report says since when, from the same record; once is no week.
        assertEquals(SignInFact(SignInState.EXPIRED, at), SignIns.state(c, HealthGrant.CALENDAR))
        assertEquals(at, SignIns.endedAt(c, Grant.CALENDAR))
        assertFalse(SignIns.endsWeekly(c, Grant.CALENDAR))
    }

    @Test fun anEndingMissedAsItHappenedIsToldByTheLookForEndings() = runBlocking {
        // Ended while nothing heard it (Woodruff closed before it could tell anyone): the record is all there is.
        val at = System.currentTimeMillis() - 5 * 60_000
        googleEnded(Grant.DRIVE, at)
        SignInAlerts.sync(c)
        awaitTold(HealthGrant.DRIVE, at)
        SignInAlerts.sync(c)
        delay(300)
        assertEquals(1, told(HealthGrant.DRIVE, at))
    }

    @Test fun signedInAgainAndEndedAgainWithinAWeekIsToldAgainAndIsTheWeeklyLimit() = runBlocking {
        val now = System.currentTimeMillis()
        googleEnded(Grant.PHOTOS, now - 7 * day)
        SignIns.ended(c, Grant.PHOTOS)
        awaitTold(HealthGrant.PHOTOS, now - 7 * day)
        // Signed in again: nothing has ended any more, and nothing more is told.
        signedInAgain(Grant.PHOTOS)
        assertNull(SignIns.endedAt(c, Grant.PHOTOS))
        assertEquals(SignInState.SIGNED_IN, SignIns.account(c, HealthGrant.PHOTOS).state)
        SignInAlerts.sync(c)
        assertFalse(SignIns.endsWeekly(c, Grant.PHOTOS))
        // Google ends it again a week later: a new ending, told again, and now "every week".
        googleEnded(Grant.PHOTOS, now)
        SignIns.ended(c, Grant.PHOTOS)
        awaitTold(HealthGrant.PHOTOS, now)
        delay(300)
        assertEquals(1, told(HealthGrant.PHOTOS, now - 7 * day))
        assertEquals(1, told(HealthGrant.PHOTOS, now))
        assertTrue(SignIns.endsWeekly(c, Grant.PHOTOS))
    }
}
