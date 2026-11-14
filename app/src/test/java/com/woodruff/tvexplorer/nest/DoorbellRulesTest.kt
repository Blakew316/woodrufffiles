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

    @Test fun aPersonAndMotionInOneMessageShowAsAPersonSeenOnce() {
        fun event(kind: String) = CameraEvent(yard, kind, "visit-3", 1_000L, "e-$kind")
        val both = listOf(event("person"), event("motion"))
        // Motion alerts on: "Person seen", not "Motion seen" over it a moment later.
        assertEquals(event("person"), messageAlert(both) { true })
        // Person alerts off: the motion, as Google had sent it alone.
        assertEquals(event("motion"), messageAlert(both) { it != "person" })
        // A press with them: the press alone.
        assertEquals(event("doorbell"), messageAlert(listOf(event("doorbell")) + both) { true })
        // None wanted: nothing.
        assertNull(messageAlert(both) { it == "doorbell" })
        assertNull(messageAlert(emptyList()) { true })
    }

    // ---- Messages shown once, across restarts ----------------------------------------------------------

    private val day = 24 * 60 * 60_000L

    @Test fun aMessageShownBeforeARestartIsntShownAgain() {
        val now = 1_800_000_000_000L
        val seen = SeenMessages()
        assertTrue(seen.add("17402841902", now))
        assertFalse(seen.add("17402841902", now + 1_000))
        // Woodruff stopped before Google heard it was acknowledged; started again, it reads what it kept.
        val again = SeenMessages().apply { restore(seen.saved(), now + 60_000) }
        assertFalse(again.add("17402841902", now + 60_000))
        assertTrue(again.add("17402841903", now + 60_000))
    }

    @Test fun shownMessagesAreKeptADayAndAFewHundredAtMost() {
        val now = 1_800_000_000_000L
        val seen = SeenMessages(max = 3)
        for (i in 1..4) assertTrue(seen.add("m$i", now + i))
        // The oldest went to make room.
        assertTrue(seen.add("m1", now + 5))
        assertFalse(seen.add("m4", now + 6))
        // A day on, none is known any more, in this run or the next.
        assertTrue(SeenMessages().apply { restore(seen.saved(), now + day + 10) }.add("m4", now + day + 10))
        assertTrue(seen.add("m4", now + day + 10))
        // 300 at most, unless told otherwise.
        val many = SeenMessages()
        for (i in 0 until 301) many.add("n$i", now)
        assertTrue(many.add("n0", now))
        assertFalse(many.add("n300", now))
    }

    @Test fun aMessageWithoutAnIdIsAlwaysNewAndAnUnreadableListIsNone() {
        val seen = SeenMessages()
        repeat(2) { assertTrue(seen.add("", 0)); assertTrue(seen.add(" ", 0)) }
        seen.restore("not a list\n12 \n\n99 m1", 100)
        assertFalse(seen.add("m1", 100))
        assertEquals("99 m1", seen.saved())
        seen.restore(null, 100)
        assertTrue(seen.add("m1", 100))
    }

    @Test fun googlesTimesAreReadInUtc() {
        assertEquals(1_793_043_725_000L, eventTime("2026-10-26T19:42:05.123Z"))
        assertEquals(1_793_043_725_000L, eventTime("2026-10-26T19:42:05Z"))
        assertNull(eventTime(""))
        assertNull(eventTime(null))
        assertNull(eventTime("yesterday at seven"))
    }

    @Test fun aBacklogFromWhileTheTvWasOffIsNoNews() {
        // [since]: when the TV began listening (Woodruff started, the screen came on).
        val since = 10_000_000L
        assertFalse(isStale(null, since))
        assertFalse(isStale(since - 30_000, since))
        assertTrue(isStale(since - 91_000, since))
        // Google's clock a little ahead of the TV's.
        assertFalse(isStale(since + 5_000, since))
    }

    @Test fun anAlertThatComesLateWhileTheTvListensStillShows() {
        val since = 10_000_000L
        // Rung 5 minutes after the TV began listening, and only sent now (the network was down, Google
        // was slow): it isn't judged by when it arrived.
        assertFalse(isStale(since + 5 * 60_000, since))
        // Rung just before the TV woke: still news.
        assertFalse(isStale(since - 60_000, since))
    }

    @Test fun aTvWhoseClockRunsFastStillShowsAlertsJustAfterWaking() {
        // Pub/Sub answered at Google's [google]; the TV woke 20 s before by its steady clock, its own clock 5 minutes fast.
        val google = 1_800_000_000_000L
        val since = sinceByGoogle(google, listenedMs = 20_000, tvNow = google + 5 * 60_000)
        assertEquals(google - 20_000, since)
        // Rung 10 s ago: news (by the TV's own clock it rang nearly 5 minutes before it woke, and was dropped).
        assertFalse(isStale(google - 10_000, since))
        assertTrue(isStale(google - 10_000, google + 5 * 60_000 - 20_000))
        // A minute before the TV woke: still news; two minutes before, a backlog, by Google's clock as well.
        assertFalse(isStale(google - 20_000 - 60_000, since))
        assertTrue(isStale(google - 20_000 - 120_000, since))
        // Pub/Sub's publishTime is read as Google's other times are (to the second).
        assertEquals(1_793_043_725_000L, eventTime("2026-10-26T19:42:05.123456789Z"))
    }

    @Test fun withoutGooglesTimeTheTvsClockStandsIn() {
        assertEquals(10_000_000L - 20_000, sinceByGoogle(0, listenedMs = 20_000, tvNow = 10_000_000))
        // A steady clock read just before listening began again: no time to take off.
        assertEquals(10_000_000L, sinceByGoogle(10_000_000, listenedMs = -50, tvNow = 0))
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

    @Test fun notAllowedToListenOnTheOneGoogleJustLetItMakeIsARefusal() {
        // Right after making it: 403 means this TV can't listen on its own; it hears the doorbell as before.
        assertEquals(OwnPullFailure.REFUSED, ownPullFailed(403, justMade = true))
        // Google may take a moment to know a subscription it just made: made again in a moment, not at once.
        assertEquals(OwnPullFailure.MAKE_AGAIN_LATER, ownPullFailed(404, justMade = true))
        // Later: gone (it deleted itself after a day unused) or no longer allowed: made again now,
        // which finds out whether Google still allows it.
        assertEquals(OwnPullFailure.MAKE_AGAIN, ownPullFailed(404, justMade = false))
        assertEquals(OwnPullFailure.MAKE_AGAIN, ownPullFailed(403, justMade = false))
    }

    @Test fun aTokenGoogleKeepsRefusingIsntRenewedEveryFewSeconds() {
        val minutes5 = 5 * 60_000L
        // The first refusal: a stale token, renewed and tried again in 5 s.
        assertEquals(5_000L, unauthorizedPauseMs(now = 1_000_000, lastAt = 0, failingMs = minutes5))
        assertEquals(5_000L, unauthorizedPauseMs(now = 1_000_000, lastAt = 1_000_000 - 61_000, failingMs = minutes5))
        // Again within a minute: it isn't the token.
        assertEquals(minutes5, unauthorizedPauseMs(now = 1_000_000, lastAt = 1_000_000 - 6_000, failingMs = minutes5))
        // The TV's clock stepped back meanwhile: taken as a first refusal.
        assertEquals(5_000L, unauthorizedPauseMs(now = 1_000_000, lastAt = 1_000_000 + 30_000, failingMs = minutes5))
    }

    @Test fun aVisitIdFromAnotherTvIsKeptToASaneLength() {
        assertEquals("CjY5Y3VKaTZwR3o4Y19YbTVfMF", cleanSession(" CjY5Y3VKaTZwR3o4Y19YbTVfMF "))
        assertNull(cleanSession(null))
        assertNull(cleanSession("   "))
        assertEquals(200, cleanSession("v".repeat(70_000))!!.length)
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

    @Test fun aTvRelayingWithoutItsNameStillCountsAsTheOneItHearsThrough() {
        // An older Woodruff passes alerts on without saying its name.
        assertEquals("Through another TV", alerts(cameras = false, relaysOut = true, from = "", listening = DoorbellListening.Idle).value)
        val a = alerts(subscription = false, from = "")
        assertEquals("Through another TV", a.value)
        assertEquals("This TV hears the doorbell from another TV while that TV is on.", a.about)
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
        assertEquals("Google’s project doesn’t let this TV listen on its own; it hears the doorbell through Living Room instead.", a.about)
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
        assertNull(pickHost(false, dreaming = false, canOverlay = { true }, views = 0, slideshows = 0, inApp = true, viewOpening = true))
    }

    @Test fun aSecondDoorbellWhileTheViewIsOnItsWayGoesToThatView() {
        // Two doorbells within the moment a doorbell view takes to come up: the second switches it
        // (from the replayed ring, once it listens) rather than opening a second view over it.
        val never = { throw AssertionError("only asked for the screensaver") }
        assertEquals(Doorbell.Host.VIEW, pickHost(true, false, never, views = 0, slideshows = 0, inApp = true, viewOpening = true))
        assertEquals(Doorbell.Host.VIEW, pickHost(true, false, never, views = 0, slideshows = 1, inApp = true, viewOpening = true))
        // Even under the screensaver that started it (it's still going away): not a second task.
        assertEquals(Doorbell.Host.VIEW, pickHost(true, dreaming = true, canOverlay = never, views = 0, slideshows = 0, inApp = false, viewOpening = true))
    }

    @Test fun aViewIsOnItsWayOnlyForAFewSeconds() {
        assertFalse(viewOnItsWay(startedAt = 0, now = 5_000))
        assertTrue(viewOnItsWay(startedAt = 1_000_000, now = 1_000_000))
        assertTrue(viewOnItsWay(startedAt = 1_000_000, now = 1_000_000 + VIEW_OPENING_MS - 1))
        // One that never came up (Android wouldn't start it) stops counting.
        assertFalse(viewOnItsWay(startedAt = 1_000_000, now = 1_000_000 + VIEW_OPENING_MS))
        // A clock that stepped back doesn't make it count for ever.
        assertFalse(viewOnItsWay(startedAt = 1_000_000, now = 999_000))
    }

    @Test fun anEmptyPullGoogleHeldOpenIsFollowedAtOnce() {
        // Google held it (a long poll): the next pull goes now, so a ring in between isn't heard up to 5 s late.
        assertEquals(0L, emptyPullPauseMs(tookMs = LONG_POLL_MS))
        assertEquals(0L, emptyPullPauseMs(tookMs = 25_000))
        // Back at once: the rest of 2 s, so pulls are never closer than that.
        assertEquals(LONG_POLL_MS, emptyPullPauseMs(tookMs = 0))
        assertEquals(500L, emptyPullPauseMs(tookMs = 1_500))
        assertEquals(LONG_POLL_MS, emptyPullPauseMs(tookMs = -40))
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

    @Test fun aRingAScreenCouldntOpenAfterAllIsntCountedAsOpened() {
        val claims = RingClaims()
        val ring = Doorbell.Ring(9, door, at = 1_000, host = Doorbell.Host.APP)
        assertTrue(claims.claim(ring, Doorbell.Host.APP, now = 1_200))
        claims.release(9)
        // Its card pops up instead (the doorbell checks whether it was taken)...
        assertFalse(claims.taken(9))
        // ...and that screen, started again while the ring is fresh, may still open it.
        assertTrue(claims.claim(ring, Doorbell.Host.APP, now = 2_000))
    }

    @Test fun claimedRingsAreBounded() {
        val claims = RingClaims(max = 2)
        for (id in 1L..3L) assertTrue(claims.claim(Doorbell.Ring(id, door, 0, Doorbell.Host.VIEW), Doorbell.Host.VIEW, 0))
        assertFalse(claims.taken(1))
        assertTrue(claims.taken(3))
    }
}
