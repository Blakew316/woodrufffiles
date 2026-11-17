package com.woodruff.tvexplorer.nest

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.Notices
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The doorbell's rules that keep a ring from being lost or shown twice: a ring heard while the screen is off is
 * announced as it comes on; visits are kept across restarts; an alert passed on by a TV too old to say its visit and
 * this TV's own copy of it show once; the family's subscription's topic is kept for when Google has deleted it, and
 * the subscription is set never to expire; and the smaller rules of listening.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DoorbellFixesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val screen = Notices.screenOn

    /** A camera no other test has used (the doorbell remembers visits and pauses per camera). */
    private fun camera() = "enterprises/p/devices/${UUID.randomUUID()}"

    @Before fun woodruffInFront() {
        Notices.load(app)
        Notices.dismissLatest()
        AppVisibility.foreground = true
    }

    @After fun asItWas() {
        Notices.screenOn = screen
        AppVisibility.foreground = false
        Notices.dismissLatest()
        Doorbell.clock = System::currentTimeMillis
    }

    private fun <T : Any> waitFor(what: String, get: () -> T?): T {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            get()?.let { return it }
            Thread.sleep(20)
        }
        fail("$what never came")
        throw IllegalStateException()
    }

    // ---- A ring while the screen is off -------------------------------------------------------------

    @Test fun aRingHeardWhileTheScreenIsOffIsKeptThenAnnouncedAsItComesOn() {
        Notices.screenOn = { false }
        Doorbell.ring(app, camera(), "Front Door", relay = false, session = "dark-1")
        val kept = Notices.items.value.first()
        assertEquals("Someone’s at the door" to "Front Door", kept.title to kept.body)
        assertFalse(kept.read)
        // Nothing on the dark screen.
        assertNull(Notices.latest.value)
        // Someone turns the TV on 15 s later to see who's there: the ring's card (no cameras here), the same notice.
        Notices.screenOn = { true }
        Doorbell.screenCameOn(app, SystemClock.elapsedRealtime() + 15_000)
        val shown = waitFor("the ring's card") { Notices.latest.value }
        assertEquals(kept.id, shown.id)
        assertEquals(kept.id, Notices.items.value.first().id)
    }

    @Test fun aRingFromLongerAgoIsOnlyKept() {
        Notices.screenOn = { false }
        Doorbell.ring(app, camera(), "Front Door", relay = false, session = "dark-2")
        Notices.screenOn = { true }
        Doorbell.screenCameOn(app, SystemClock.elapsedRealtime() + 91_000)
        Thread.sleep(2_000)
        assertNull(Notices.latest.value)
        // And it's announced once: the screen coming on again shows nothing more.
        Doorbell.screenCameOn(app, SystemClock.elapsedRealtime())
        Thread.sleep(2_000)
        assertNull(Notices.latest.value)
    }

    @Test fun aRingIsAnnouncedOnWakingOnlyWithin90Seconds() {
        assertTrue(announcedOnWaking(1_000, 1_000))
        assertTrue(announcedOnWaking(1_000, 91_000))
        assertFalse(announcedOnWaking(1_000, 91_001))
        assertFalse(announcedOnWaking(5_000, 1_000))
    }

    @Test fun aRingSentAsTheScreenCameOnIsFreshForTheScreenItsMeantFor() {
        val claims = RingClaims()
        // It rang 60 s ago, and goes to Woodruff's own screens now that the screen is on.
        val ring = Doorbell.Ring(1, "enterprises/p/devices/front-door", at = 1_000_000, host = Doorbell.Host.APP, sent = 1_060_000)
        assertTrue(claims.claim(ring, Doorbell.Host.APP, 1_061_000))
        assertFalse(claims.claim(ring.copy(id = 2, sent = 1_000_000), Doorbell.Host.APP, 1_061_000))
    }

    // ---- Visits across restarts, and alerts passed on by older TVs ------------------------------------

    @Test fun visitsAreKeptAcrossARestart() {
        val now = 1_800_000_000_000L
        val door = "enterprises/p/devices/front-door"
        val before = Visits()
        assertTrue(before.admit(door, "visit-1", "doorbell", now))
        assertTrue(before.admit(door, "visit-2", "person", now))
        // Woodruff restarted (an update): the battery doorbell's follow-up 45 s later doesn't ring again.
        val after = Visits().apply { restore(before.saved(), now + 45_000) }
        assertFalse(after.admit(door, "visit-1", "doorbell", now + 45_000))
        assertFalse(after.admit(door, "visit-2", "person", now + 45_000))
        assertTrue(after.admit(door, "visit-2", "doorbell", now + 45_000))
        // A day on, they're gone; and nothing readable is nothing.
        assertTrue(Visits().apply { restore(before.saved(), now + 25 * 60 * 60_000L) }.admit(door, "visit-1", "doorbell"))
        assertTrue(Visits().apply { restore("not a list", now) }.admit(door, "visit-1", "doorbell"))
        assertTrue(Visits().apply { restore(null, now) }.admit(door, "visit-1", "doorbell"))
    }

    @Test fun aRingPassedOnByAnOldTvThenHeardHereRingsOnce() {
        var t = 1_950_000_000_000L
        Doorbell.clock = { t }
        val door = camera()
        val before = Notices.items.value.size
        // A TV too old to say its visit passes the ring on before this TV's own copy arrives.
        Doorbell.ring(app, door, "Front Door", relay = false, from = "Bedroom")
        t += 2_000
        Doorbell.ring(app, door, "Front Door", relay = false, session = "visit-old")
        assertEquals(before + 1, Notices.items.value.size)
        // Its follow-up, in the same visit: still once.
        t += 45_000
        Doorbell.ring(app, door, "Front Door", relay = false, session = "visit-old")
        assertEquals(before + 1, Notices.items.value.size)
        // Someone else a minute later: a ring of its own.
        t += 60_000
        Doorbell.ring(app, door, "Front Door", relay = false, session = "visit-new")
        assertEquals(before + 2, Notices.items.value.size)
    }

    // ---- The family's subscription -----------------------------------------------------------------

    private fun about(topic: String, ttl: String? = "2678400s") = JSONObject().put("name", "x").put("topic", topic)
        .apply { if (ttl != null) put("expirationPolicy", JSONObject().put("ttl", ttl)) else put("expirationPolicy", JSONObject()) }

    @Test fun theFamilysTopicIsKeptForWhenGoogleHasDeletedTheSubscription() {
        val shared = "projects/home-${UUID.randomUUID()}/subscriptions/doorbell"
        var patched = 0
        val topic = "projects/sdm-prod/topics/enterprise-1"
        assertEquals(topic, Doorbell.familyTopic(app, shared, read = { about(topic) }, neverExpire = { patched++ }))
        // Set never to expire, once.
        assertEquals(1, patched)
        assertEquals(topic, Doorbell.familyTopic(app, shared, read = { about(topic) }, neverExpire = { patched++ }))
        assertEquals(1, patched)
        // A month later nothing had pulled it, and Google deleted it: a TV making its own again uses the topic kept.
        assertEquals(topic, Doorbell.familyTopic(app, shared, read = { throw PubSub.Answer(404, "NOT_FOUND") }, neverExpire = { patched++ }))
        // Another subscription Google can't find has no topic kept: still not found.
        val other = "projects/home-${UUID.randomUUID()}/subscriptions/bell"
        try {
            Doorbell.familyTopic(app, other, read = { throw PubSub.Answer(404, "NOT_FOUND") }, neverExpire = {})
            fail("no topic for a subscription never read")
        } catch (e: PubSub.Answer) {
            assertEquals(404, e.code)
        }
        // Anything else Google says goes on as before.
        try {
            Doorbell.familyTopic(app, shared, read = { throw PubSub.Answer(403, "PERMISSION_DENIED") }, neverExpire = {})
            fail("a refusal isn't a missing subscription")
        } catch (e: PubSub.Answer) {
            assertEquals(403, e.code)
        }
    }

    @Test fun aDeletedTopicIsNeverUsedAndANeverExpiringSubscriptionIsLeftAlone() {
        val shared = "projects/home-${UUID.randomUUID()}/subscriptions/doorbell"
        try {
            Doorbell.familyTopic(app, shared, read = { about(DELETED_TOPIC) }, neverExpire = { fail("nothing to set") })
            fail("a deleted topic delivers nothing")
        } catch (e: PubSub.Answer) {
            assertEquals(404, e.code)
        }
        var patched = 0
        assertEquals("projects/p/topics/t", Doorbell.familyTopic(app, shared, read = { about("projects/p/topics/t", ttl = null) }, neverExpire = { patched++ }))
        assertEquals(0, patched)
    }

    @Test fun aRefusalToSetItNeverToExpireChangesNothingAndIsTriedAgainNextTime() {
        val shared = "projects/home-${UUID.randomUUID()}/subscriptions/doorbell"
        var tries = 0
        val topic = "projects/p/topics/t"
        assertEquals(topic, Doorbell.familyTopic(app, shared, read = { about(topic) }, neverExpire = { tries++; throw PubSub.Answer(403, "PERMISSION_DENIED") }))
        assertEquals(topic, Doorbell.familyTopic(app, shared, read = { about(topic) }, neverExpire = { tries++ }))
        assertEquals(2, tries)
    }

    @Test fun neverExpiringIsAPolicyWithoutATimeToLive() {
        assertTrue(expiresUnused(JSONObject().put("expirationPolicy", JSONObject().put("ttl", "2678400s"))))
        assertFalse(expiresUnused(JSONObject().put("expirationPolicy", JSONObject())))
        // Google not saying: set never to expire, once, to be sure.
        assertTrue(expiresUnused(JSONObject()))
        val body = neverExpireBody("projects/p/subscriptions/doorbell")
        assertEquals("expirationPolicy", body.getString("updateMask"))
        assertEquals("projects/p/subscriptions/doorbell", body.getJSONObject("subscription").getString("name"))
        assertEquals(0, body.getJSONObject("subscription").getJSONObject("expirationPolicy").length())
    }
}
