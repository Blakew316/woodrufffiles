package com.woodruff.tvexplorer.tvs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.GoogleAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Google ending one of this TV's sign-ins, on the TV: it's remembered (the report says since when), it's told to the
 * phones once (remembered as told), twice within 8 days is "every week" (the phone's Testing tip), and signing in
 * again from the phone clears it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SignInEndingTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = c.getSharedPreferences("health", Context.MODE_PRIVATE)

    @Test fun anEndingIsRememberedAndToldOnce() = runBlocking {
        val at = System.currentTimeMillis() - 60_000
        SignIns.markEnded(c, GoogleAuth.Grant.CALENDAR, at)
        assertEquals(at, SignIns.endedAt(c, GoogleAuth.Grant.CALENDAR))
        // With no keys on this TV the report says "Not set up"; the ending stays kept for when they come back.
        val key = "signin:${TvLinks.id(c)}:calendar:$at"
        // No other TV to wait for: told at once (to no phones here), and remembered as told.
        val told = withTimeoutOrNull(3_000) {
            while (!JSONObject(prefs.getString("signin.told", null) ?: "{}").has(key)) delay(20)
            true
        }
        assertNotNull("the ending was told (and remembered as told)", told)
        assertFalse("once is no week", SignIns.endsWeekly(c, GoogleAuth.Grant.CALENDAR))
    }

    @Test fun twoEndingsWithinEightDaysAreTheWeeklyLimit() {
        val now = System.currentTimeMillis()
        SignIns.markEnded(c, GoogleAuth.Grant.PHOTOS, now - 7 * 24 * 3_600_000L)
        SignIns.signedIn(c, GoogleAuth.Grant.PHOTOS)
        assertNull("signed in again: not ended any more", SignIns.endedAt(c, GoogleAuth.Grant.PHOTOS))
        assertFalse(SignIns.endsWeekly(c, GoogleAuth.Grant.PHOTOS))
        SignIns.markEnded(c, GoogleAuth.Grant.PHOTOS, now)
        assertTrue("ended again within 8 days: every week", SignIns.endsWeekly(c, GoogleAuth.Grant.PHOTOS))
        assertEquals(now, SignIns.endedAt(c, GoogleAuth.Grant.PHOTOS))
    }
}
