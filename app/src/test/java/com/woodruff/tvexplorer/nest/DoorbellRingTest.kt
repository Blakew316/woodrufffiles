package com.woodruff.tvexplorer.nest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * A ring or a camera alert arriving at a TV (from its own subscription or passed on by another
 * TV): what it says, that one visit rings once, and what Settings then says. This TV's cameras
 * aren't connected here (no Keystore in tests), so a ring is a card, as on a TV without cameras.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DoorbellRingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A camera no other test has used (the doorbell remembers visits and pauses per camera). */
    private fun camera() = "enterprises/p/devices/${UUID.randomUUID()}"

    private fun latest() = Notices.items.value.first().let { it.title to it.body }

    @Test fun alertsSayWhatHappenedThenWhichCamera() {
        Notices.load(app)
        Doorbell.ring(app, camera(), "Backyard", relay = false, kind = "person", session = "v1")
        assertEquals("Person seen" to "Backyard", latest())
        Doorbell.ring(app, camera(), "Garage", relay = false, kind = "motion", session = "v2")
        assertEquals("Motion seen" to "Garage", latest())
        Doorbell.ring(app, camera(), "Front Door", relay = false, session = "v3")
        assertEquals("Someone’s at the door" to "Front Door", latest())
    }

    @Test fun aCameraNobodyNamedIsNeverCalledTheDoorbell() {
        Notices.load(app)
        Doorbell.ring(app, camera(), "", relay = false, kind = "person", session = "v1")
        assertEquals("Person seen" to "One of your cameras", latest())
        Doorbell.ring(app, camera(), " ", relay = false, session = "v2")
        assertEquals("Someone’s at the door" to "Doorbell", latest())
    }

    @Test fun oneVisitRingsOnceHoweverItArrives() {
        Notices.load(app)
        val door = camera()
        val before = Notices.items.value.size
        // From the hub, then the same visit from this TV's own subscription, then its follow-up.
        Doorbell.ring(app, door, "Front Door", relay = false, session = "visit-1", from = "Living Room")
        Doorbell.ring(app, door, "Front Door", relay = false, session = "visit-1")
        Doorbell.ring(app, door, "Front Door", relay = false, session = "visit-1")
        // A person seen after the ring, in the same visit: no card.
        Doorbell.ring(app, door, "Front Door", relay = false, kind = "person", session = "visit-1")
        assertEquals(before + 1, Notices.items.value.size)
    }

    @Test fun anAlertKindNobodyKnowsIsIgnored() {
        Notices.load(app)
        val before = Notices.items.value.size
        Doorbell.ring(app, camera(), "Front Door", relay = false, kind = "package")
        assertEquals(before, Notices.items.value.size)
    }

    @Test fun thePersonAndMotionChoiceFromAnotherTvIsTakenAsIs() {
        // Nobody chose yet: the defaults (person on, motion off), and nothing to share.
        assertNull(Doorbell.alertsChoice(app))
        assertEquals(true to false, Doorbell.personAlerts(app) to Doorbell.motionAlerts(app))
        Doorbell.receivedAlerts(app, "person=0;motion=1")
        assertEquals(false to true, Doorbell.personAlerts(app) to Doorbell.motionAlerts(app))
        assertEquals("person=0;motion=1", Doorbell.alertsChoice(app))
        // Removed on the other TV: back to the defaults.
        Doorbell.receivedAlerts(app, null)
        assertNull(Doorbell.alertsChoice(app))
        assertEquals(true to false, Doorbell.personAlerts(app) to Doorbell.motionAlerts(app))
        // Anything else isn't taken.
        assertEquals(true, runCatching { Doorbell.receivedAlerts(app, "loud") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun aRelayFromAnOlderTvThatDoesntSayItsNameStillCounts() {
        Doorbell.ring(app, camera(), "Front Door", relay = false, session = "v10")
        assertEquals("Through another TV", Doorbell.alerts(app).value)
        // A later relay that says its name: named from then on; one that doesn't keeps the name.
        Doorbell.ring(app, camera(), "Front Door", relay = false, session = "v11", from = "Living Room")
        Doorbell.ring(app, camera(), "Front Door", relay = false, session = "v12")
        assertEquals("Through Living Room", Doorbell.alerts(app).value)
    }

    @Test fun aTvWithoutCamerasSaysItHearsTheDoorbellThroughTheTvThatPassedItOn() {
        Doorbell.ring(app, camera(), "Front Door", relay = false, session = "v9", from = "  Living Room ")
        val alerts = Doorbell.alerts(app)
        assertEquals(DoorbellAlerts.State.THROUGH, alerts.state)
        assertEquals("Through Living Room", alerts.value)
        // Nothing for the phone to warn about.
        assertNull(Doorbell.status.value)
    }
}
