package com.woodruff.tvexplorer.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One ring per visit, each TV's own subscription, Settings' words and where a ring's camera opens. */
class DoorbellRulesTest {
    private val door = "enterprises/p/devices/front-door"
    private val yard = "enterprises/p/devices/backyard"

    // ---- One ring per visit -----------------------------------------------------------------------

    @Test fun aBatteryDoorbellsFollowUpDoesntRingAgain() {
        val visits = Visits()
        // The press, then ~45 s later the same press again with its clip, in the same visit.
        assertTrue(visits.admit(door, "visit-1", "doorbell"))
        assertFalse(visits.admit(door, "visit-1", "doorbell"))
    }

    @Test fun aPersonSeenBeforeThePressStillShowsAndThePressStillRings() {
        val visits = Visits()
        assertTrue(visits.admit(door, "visit-1", "person"))
        assertTrue(visits.admit(door, "visit-1", "doorbell"))
        // Once it has rung, no person or motion card follows in that visit.
        assertFalse(visits.admit(door, "visit-1", "person"))
        assertFalse(visits.admit(door, "visit-1", "motion"))
    }

    @Test fun eachKindShowsOncePerVisit() {
        val visits = Visits()
        assertTrue(visits.admit(yard, "visit-7", "person"))
        assertFalse(visits.admit(yard, "visit-7", "person"))
        assertTrue(visits.admit(yard, "visit-7", "motion"))
    }

    @Test fun aNewVisitRingsAgain() {
        val visits = Visits()
        assertTrue(visits.admit(door, "visit-1", "doorbell"))
        assertTrue(visits.admit(door, "visit-2", "doorbell"))
    }

    @Test fun hearingAVisitFromTheHubAndFromItsOwnSubscriptionRingsOnce() {
        val visits = Visits()
        // The hub's relay carries the visit; the TV's own pull of the same event comes a moment later.
        assertTrue(visits.admit(door, "visit-1", "doorbell"))
        assertFalse(visits.admit(door, "visit-1", "doorbell"))
    }

    @Test fun eventsWithoutAVisitAreAlwaysNewHere() {
        val visits = Visits()
        repeat(2) { assertTrue(visits.admit(door, null, "doorbell")) }
        repeat(2) { assertTrue(visits.admit(door, " ", "doorbell")) }
    }

    @Test fun visitsAreKeptPerCamera() {
        val visits = Visits()
        assertTrue(visits.admit(door, "same-id", "doorbell"))
        assertTrue(visits.admit(yard, "same-id", "doorbell"))
    }

    @Test fun onlyTheLatestVisitsAreRemembered() {
        val visits = Visits(max = 2)
        visits.admit(door, "a", "doorbell"); visits.admit(door, "b", "doorbell"); visits.admit(door, "c", "doorbell")
        // "a" was forgotten to make room; "c" is still known.
        assertTrue(visits.admit(door, "a", "doorbell"))
        assertFalse(visits.admit(door, "c", "doorbell"))
    }

    @Test fun googlesTimesAreReadInUtc() {
        assertEquals(1_793_043_725_000L, eventTime("2026-10-26T19:42:05.123Z"))
        assertEquals(1_793_043_725_000L, eventTime("2026-10-26T19:42:05Z"))
        assertNull(eventTime(""))
        assertNull(eventTime(null))
        assertNull(eventTime("yesterday at seven"))
    }

    @Test fun aBacklogFromWhileTheTvWasOffIsNoNews() {
        val now = 10_000_000L
        assertFalse(isStale(null, now))
        assertFalse(isStale(now - 30_000, now))
        assertTrue(isStale(now - 91_000, now))
        // Google's clock a little ahead of the TV's.
        assertFalse(isStale(now + 5_000, now))
    }

    // ---- Each TV's own subscription -----------------------------------------------------------------

    @Test fun eachTvsSubscriptionIsNamedAfterTheFamilysAndTheTv() {
        assertEquals(
            "projects/home-123/subscriptions/front-door-tv-1a2b3c4d5e6f",
            ownSubscription("projects/home-123/subscriptions/front-door", "1A2B3C4D-5E6F-7890-abcd-ef0123456789"),
        )
        // Another TV, another subscription; the same TV, always the same one.
        val a = ownSubscription("projects/p/subscriptions/bell", "aaaaaaaa-0000-0000-0000-000000000000")
        val b = ownSubscription("projects/p/subscriptions/bell", "bbbbbbbb-0000-0000-0000-000000000000")
        assertTrue(a != b)
        assertEquals(a, ownSubscription(" projects/p/subscriptions/bell ", "aaaaaaaa-0000-0000-0000-000000000000"))
    }

    @Test fun aSubscriptionNameStaysWithinPubSubsLimit() {
        val long = "projects/p/subscriptions/" + "b".repeat(255)
        val own = ownSubscription(long, "1a2b3c4d-5e6f-7890-abcd-ef0123456789")!!
        assertEquals(255, own.substringAfterLast('/').length)
        assertTrue(own.endsWith("-tv-1a2b3c4d5e6f"))
    }

    @Test fun nothingIsMadeFromANameThatIsntASubscription() {
        assertNull(ownSubscription("front-door", "1a2b3c4d"))
        assertNull(ownSubscription("projects/p/topics/doorbell", "1a2b3c4d"))
        assertNull(ownSubscription("projects/p/subscriptions/bell", "----"))
        assertTrue(isSubscription("projects/p/subscriptions/bell"))
        assertFalse(isSubscription("projects/p/subscriptions/"))
        assertFalse(isSubscription("projects/p/subscriptions/a/b"))
    }

    @Test fun onlyARealRefusalGivesUpOnATvsOwnSubscription() {
        // Not allowed, or not in that form: the TV hears the doorbell as before.
        for (code in listOf(400, 403, 409, 412)) assertTrue("$code", isRefusal(code))
        // A token to renew, something missing, Google busy: tried again.
        for (code in listOf(401, 404, 408, 429, 500, 503)) assertFalse("$code", isRefusal(code))
    }

    // ---- What Settings says ----------------------------------------------------------------------

    private fun alerts(subscription: Boolean = true, cameras: Boolean = true, relaysOut: Boolean = false, from: String? = null, listening: DoorbellListening = DoorbellListening.Own) =
        doorbellAlerts(subscription, cameras, relaysOut, from, listening)

    @Test fun aTvListeningOnItsOwnSaysWatching() {
        for (l in listOf(DoorbellListening.Own, DoorbellListening.Shared, DoorbellListening.Idle)) {
            val a = alerts(listening = l)
            assertEquals(DoorbellAlerts.State.WATCHING, a.state)
            assertEquals("Watching", a.value)
            assertEquals("Doorbell presses pop up on this TV, even when the other TVs are off.", a.about)
            assertFalse(a.attention)
        }
    }

    @Test fun aTvWithoutCamerasHearsItThroughTheHub() {
        val a = alerts(cameras = false, from = "Living Room", listening = DoorbellListening.Idle)
        assertEquals(DoorbellAlerts.State.THROUGH, a.state)
        assertEquals("Through Living Room", a.value)
        assertEquals("This TV hears the doorbell from Living Room while that TV is on. Connect the cameras on this TV to hear it on its own.", a.about)
        // Before any alert came through, the other TV isn't known by name yet.
        assertEquals("Through another TV", alerts(cameras = false, listening = DoorbellListening.Idle).value)
    }

    @Test fun aHubWithoutCamerasHearsNothing() {
        val a = alerts(cameras = false, relaysOut = true, listening = DoorbellListening.Idle)
        assertEquals(DoorbellAlerts.State.OFF, a.state)
        assertEquals("Off", a.value)
        assertEquals("Connect the cameras on this TV to hear the doorbell.", a.about)
    }

    @Test fun nothingSetUpIsOff() {
        val a = alerts(subscription = false, cameras = false, listening = DoorbellListening.Idle)
        assertEquals(DoorbellAlerts.State.OFF, a.state)
        assertEquals("Off", a.value)
        assertEquals("Set it up in the phone app.", a.about)
        assertEquals(DoorbellAlerts.State.OFF, alerts(subscription = false).state)
    }

    @Test fun camerasHereButTheSubscriptionNotYetHereHearsItThroughTheOtherTv() {
        val a = alerts(subscription = false, from = "Living Room")
        assertEquals("Through Living Room", a.value)
        assertEquals("This TV hears the doorbell from Living Room while that TV is on.", a.about)
    }

    @Test fun googleRefusingThisTvItsOwnNeedsAttention() {
        val a = alerts(from = "Living Room", listening = DoorbellListening.Refused)
        assertEquals(DoorbellAlerts.State.FAILING, a.state)
        assertEquals("Google refused", a.value)
        assertEquals("Google's project doesn't let this TV listen on its own; it hears the doorbell through Living Room instead.", a.about)
        assertTrue(a.attention)
    }

    @Test fun aProblemAPersonMustFixSaysWhat() {
        val a = alerts(listening = DoorbellListening.Failing("Subscription not found", "Check its name."))
        assertEquals(DoorbellAlerts.State.FAILING, a.state)
        assertEquals("Subscription not found", a.value)
        assertEquals("Check its name.", a.about)
    }

    @Test fun theHealthReportGetsOneWordPerState() {
        assertEquals(listOf("watching", "through", "off", "failing"), DoorbellAlerts.State.entries.map { it.id })
    }

    @Test fun thePersonAndMotionChoiceTravelsAsOneValue() {
        assertEquals("person=1;motion=0", alertsKey(person = true, motion = false))
        for (p in listOf(true, false)) for (m in listOf(true, false)) assertEquals(p to m, parseAlertsKey(alertsKey(p, m)))
        assertEquals(true to false, parseAlertsKey(" motion=0 ; person=1 "))
        assertNull(parseAlertsKey(null))
        assertNull(parseAlertsKey("person=1"))
        assertNull(parseAlertsKey("person=yes;motion=0"))
        assertNull(parseAlertsKey("projects/p/subscriptions/bell"))
    }

    @Test fun anotherTvsNameIsCleanedBeforeItsShown() {
        assertEquals("Living Room", cleanTvName("  Living\u0000 Room \n"))
        assertEquals(40, cleanTvName("x".repeat(80))!!.length)
        assertNull(cleanTvName("   "))
        assertNull(cleanTvName(null))
    }

    // ---- Where a ring's camera opens ------------------------------------------------------------------

    @Test fun theScreensaverWakesForTheCameraOnlyWithPopUpsAllowed() {
        assertEquals(Doorbell.Host.SCREENSAVER, pickHost(true, dreaming = true, canOverlay = { true }, views = 1, slideshows = 1, inApp = true))
        // It covers everything, so without the right to open the camera, the card.
        assertNull(pickHost(true, dreaming = true, canOverlay = { false }, views = 1, slideshows = 1, inApp = true))
    }

    @Test fun aViewAlreadyUpTakesTheRingThenTheSlideshowThenWoodruff() {
        val never = { throw AssertionError("only asked for the screensaver") }
        assertEquals(Doorbell.Host.VIEW, pickHost(true, false, never, views = 1, slideshows = 1, inApp = true))
        assertEquals(Doorbell.Host.SLIDESHOW, pickHost(true, false, never, views = 0, slideshows = 1, inApp = true))
        assertEquals(Doorbell.Host.APP, pickHost(true, false, never, views = 0, slideshows = 0, inApp = true))
        // The player, the photo viewer, another app: the card.
        assertNull(pickHost(true, false, never, views = 0, slideshows = 0, inApp = false))
    }

    @Test fun nothingOpensWithoutThisTvsOwnCameras() {
        assertNull(pickHost(false, dreaming = true, canOverlay = { true }, views = 1, slideshows = 1, inApp = true))
    }

    @Test fun eachRingOpensOnceOnTheScreenItWasMeantFor() {
        val claims = RingClaims()
        val ring = Doorbell.Ring(1, door, at = 1_000, host = Doorbell.Host.SLIDESHOW)
        assertFalse(claims.claim(ring, Doorbell.Host.APP, now = 1_500))
        assertTrue(claims.claim(ring, Doorbell.Host.SLIDESHOW, now = 1_500))
        // The slideshow back a moment later (the camera closed) doesn't open it again.
        assertFalse(claims.claim(ring, Doorbell.Host.SLIDESHOW, now = 2_000))
        assertTrue(claims.taken(1))
        assertFalse(claims.taken(2))
    }

    @Test fun aRingNoLongerFreshOpensNothing() {
        val claims = RingClaims(freshMs = 10_000)
        assertFalse(claims.claim(Doorbell.Ring(5, door, at = 0, host = Doorbell.Host.APP), Doorbell.Host.APP, now = 10_001))
    }

    @Test fun claimedRingsAreBounded() {
        val claims = RingClaims(max = 2)
        for (id in 1L..3L) assertTrue(claims.claim(Doorbell.Ring(id, door, 0, Doorbell.Host.VIEW), Doorbell.Host.VIEW, 0))
        assertFalse(claims.taken(1))
        assertTrue(claims.taken(3))
    }
}
