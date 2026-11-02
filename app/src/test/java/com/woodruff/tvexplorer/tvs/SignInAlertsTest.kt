package com.woodruff.tvexplorer.tvs

import com.woodruff.tvexplorer.cloud.GoogleAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/** What the phones are told when Google ends a sign-in, and which endings that covers (plain logic; Android's JSON). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SignInAlertsTest {
    private val now = 1_762_100_000_000L
    private val day = 24 * 3_600_000L

    private fun here(grant: HealthGrant = HealthGrant.DRIVE, since: Long = now) = Ending("tv-1", "Living Room", here = true, grant = grant, since = since)
    private fun den(grant: HealthGrant = HealthGrant.DRIVE, since: Long = now) = Ending("den-1", "Den", here = false, grant = grant, since = since)
    private fun kitchen(since: Long = now) = Ending("kitchen-1", "Kitchen", here = false, grant = HealthGrant.DRIVE, since = since)

    @Test fun thisTvsEndingCanBeFixedFromThePhone() {
        val m = signInMessage(listOf(here()))
        assertEquals("Living Room", m.from)
        assertEquals("Sign in to Google Drive again", m.title)
        assertEquals("Google ended Living Room’s sign-in. Tap to sign it in again from this phone.", m.body)
        assertEquals("#settings/tvs/this/signin/drive", m.link)
        assertEquals("Living Room: Sign in to Google Drive again", m.pushTitle)
        assertEquals("Google ended its sign-in. Open Woodruff Family to sign it in again.", m.pushBody)
        assertEquals("signin:tv-1:drive:$now", m.id)
    }

    @Test fun anotherTvsEndingSaysHowToFixIt() {
        val m = signInMessage(listOf(den(HealthGrant.CAMERAS)))
        assertEquals("Den", m.from)
        assertEquals("Connect your cameras again", m.title)
        assertEquals("Google ended Den’s sign-in. Tap to see how to sign it in again.", m.body)
        assertEquals("#settings/tvs/den-1", m.link)
        assertEquals("Den: Connect your cameras again", m.pushTitle)
        assertEquals("Google ended its sign-in. Open Woodruff Family to see how to sign it in again.", m.pushBody)
    }

    @Test fun theSameEndingOnSeveralTvsIsOneMessage() {
        val m = signInMessage(listOf(den(), kitchen(now - 60_000), here(since = now + 30_000)))
        assertEquals("Woodruff", m.from)
        assertEquals("Sign in to Google Drive again on 3 TVs", m.title)
        assertEquals("Google ended the sign-ins of Living Room, Den and Kitchen. Tap to see them.", m.body)
        assertEquals("#settings/tvs", m.link)
        assertEquals("Sign in to Google Drive again on 3 TVs", m.pushTitle)
        assertEquals("Google ended the sign-ins of Living Room, Den and Kitchen. Open Woodruff Family to see them.", m.pushBody)
        assertEquals("signin:all:drive:${now - 60_000}", m.id)
        assertEquals("Google ended the sign-ins of Den and Kitchen. Tap to see them.", signInMessage(listOf(den(), kitchen())).body)
    }

    @Test fun eachSignInHasTheTvNoticesTitle() {
        assertEquals("Sign in to Google Drive again", againTitle(HealthGrant.DRIVE))
        assertEquals("Connect Google Photos again", againTitle(HealthGrant.PHOTOS))
        assertEquals("Connect Google Calendar again", againTitle(HealthGrant.CALENDAR))
        assertEquals("Connect your cameras again", againTitle(HealthGrant.CAMERAS))
        assertEquals("Connect Google Calendar again on 2 TVs", signInMessage(listOf(den(HealthGrant.CALENDAR), here(HealthGrant.CALENDAR))).title)
        assertEquals("#settings/tvs/this/signin/photos", signInMessage(listOf(here(HealthGrant.PHOTOS))).link)
        assertEquals("", namesList(emptyList()))
        assertEquals("Den", namesList(listOf("Den")))
    }

    @Test fun onlyRealFreshEndingsFromTvsThatAnswer() {
        fun status(id: String, name: String, ended: Map<String, Long>, online: Boolean = true, refused: Boolean = false) =
            TvStatus(id, name, online, false, null, health = HealthSummary("attention", ended.size, null, ended), reportsHealth = true, refused = refused)
        val me = status("tv-1", "Living Room", mapOf("drive" to now))
        val statuses = listOf(
            me,
            status("den-1", "Den", mapOf("drive" to now - 3 * day, "cameras" to now - 9 * day, "mystery" to now)),
            status("kitchen-1", "Kitchen", mapOf("drive" to now), online = false),
            status("bed-1", "Bedroom", mapOf("drive" to now), refused = true),
            status("attic-1", "Attic", mapOf("photos" to now + 3 * day)),
        )
        val found = linkedEndings(statuses, now, freshMs = 8 * day)
        // This TV comes first and tells its own; an old ending, a TV that's off or refuses its link, and a clock days ahead don't count.
        assertEquals(listOf(Ending("den-1", "Den", false, HealthGrant.DRIVE, now - 3 * day)), found)
    }

    @Test fun batchesHoldEachSignInsEndingsOnceEach() {
        val b = EndingBatches()
        assertTrue("the first opens its sign-in's batch", b.offer(den(), emptySet()))
        assertFalse(b.offer(kitchen(), emptySet()))
        assertFalse("seen again: still once", b.offer(den(), emptySet()))
        assertTrue("another sign-in has its own", b.offer(here(HealthGrant.CAMERAS), emptySet()))
        assertFalse("told already: never again", b.offer(here(), setOf(here().key)))
        assertEquals(listOf("den-1", "kitchen-1"), b.take(HealthGrant.DRIVE).map { it.tvId })
        assertTrue(b.take(HealthGrant.DRIVE).isEmpty())
        assertTrue(b.offer(den(since = now + day), emptySet()))
        assertEquals(1, b.take(HealthGrant.CAMERAS).size)
    }

    @Test fun theNewestEndingOfATvCounts() {
        val b = EndingBatches()
        b.offer(den(since = now), emptySet())
        b.offer(den(since = now + 1_000), emptySet())
        b.offer(den(since = now - 1_000), emptySet())
        assertEquals(listOf(now + 1_000), b.take(HealthGrant.DRIVE).map { it.since })
    }

    @Test fun aSummaryCarriesTheEndingsAndTravelsAsJson() {
        val s = HealthSummary("attention", 1, "Google Drive’s sign-in ended", mapOf("drive" to now))
        assertEquals(s, HealthSummary.fromJson(s.toJson()))
        assertEquals(now, s.toJson().getJSONObject("ended").getLong("drive"))
        // From a Woodruff before endings were reported: none.
        val old = HealthSummary.fromJson(org.json.JSONObject().put("status", "good").put("count", 0))
        assertEquals(emptyMap<String, Long>(), old!!.ended)
        assertFalse(HealthSummary("good", 0, null).toJson().has("ended"))
    }

    @Test fun theReportsSummaryNamesWhatEnded() {
        val facts = HealthTest.facts().copy(signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } +
            (HealthGrant.DRIVE to SignInFact(SignInState.EXPIRED, now - day)) + (HealthGrant.CAMERAS to SignInFact(SignInState.OFFLINE)))
        val summary = buildHealth(facts, HealthTest.clock()).summary
        assertEquals(mapOf("drive" to now - day), summary.ended)
        assertEquals("attention", summary.status)
    }

    @Test fun theWeeklyLimitIsTwoEndingsWithinEightDays() {
        assertEquals("$now", SignIns.withEnding(null, now))
        assertEquals("$now,${now + day}", SignIns.withEnding("$now", now + day))
        assertEquals("${now + day},${now + 2 * day}", SignIns.withEnding("$now,${now + day}", now + 2 * day))
        assertFalse(SignIns.endedTwiceWithin(null, 8 * day))
        assertFalse(SignIns.endedTwiceWithin("$now", 8 * day))
        assertTrue(SignIns.endedTwiceWithin("$now,${now + 7 * day}", 8 * day))
        assertFalse(SignIns.endedTwiceWithin("$now,${now + 9 * day}", 8 * day))
        assertFalse("garbage counts for nothing", SignIns.endedTwiceWithin("x,y", 8 * day))
    }

    @Test fun accountsNeverSayOffline() {
        assertEquals(SignInState.SIGNED_IN, SignIns.accountState(SignInState.OFFLINE))
        assertEquals(SignInState.SIGNED_IN, SignIns.accountState(SignInState.UNREACHABLE))
        for (s in listOf(SignInState.SIGNED_IN, SignInState.EXPIRED, SignInState.KEYS_REJECTED, SignInState.NOT_SIGNED_IN, SignInState.NOT_SET_UP)) {
            assertEquals(s, SignIns.accountState(s))
        }
        assertEquals(GoogleAuth.Grant.NEST, SignIns.googleGrant(HealthGrant.CAMERAS))
        assertEquals(HealthGrant.CAMERAS, SignIns.healthGrant(GoogleAuth.Grant.NEST))
        for (g in HealthGrant.entries) assertEquals(g, SignIns.healthGrant(SignIns.googleGrant(g)))
    }
}
