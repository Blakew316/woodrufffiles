package com.woodruff.tvexplorer.nest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What Google's doorbell messages say (Google's own examples, from the Device Access guide for
 * battery doorbells), how a TV's own subscription is made, and the saved camera list. Robolectric
 * for org.json.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DoorbellEventsTest {
    private val door = "enterprises/project-id/devices/device-id"

    private fun message(timestamp: String, events: String) = """
        {"eventId":"0120ecc7-3b57-4eb4-9941-91609f189fb4","timestamp":"$timestamp",
         "resourceUpdate":{"name":"$door","events":{$events}},
         "userId":"AVPHwEuBfnPOnTqzVFT4IONX2Qqhu9EJ4ubO-bNnQ-yi","eventThreadId":"d67cd3f7","eventThreadState":"STARTED",
         "resourceGroup":["$door"]}
    """.trimIndent()

    private val chime = """"sdm.devices.events.DoorbellChime.Chime":{"eventSessionId":"CjY5Y3VKaTZwR3o4Y19YbTVfMF","eventId":"n:1"}"""
    private val clip = """"sdm.devices.events.CameraClipPreview.ClipPreview":{"eventSessionId":"CjY5Y3VKaTZwR3o4Y19YbTVfMF","previewUrl":"https://previewurl.example/abc"}"""
    private val person = """"sdm.devices.events.CameraPerson.Person":{"eventSessionId":"CjY5Y3VKaTZwR3o4Y19YbTVfMF","eventId":"n:2"}"""
    private val motion = """"sdm.devices.events.CameraMotion.Motion":{"eventSessionId":"CjY5Y3VKaTZwR3o4Y19YbTVfMF","eventId":"n:3"}"""

    @Test fun aPressIsADoorbellEventOfItsVisit() {
        val events = cameraEvents(message("2026-10-26T19:42:05.000Z", chime))
        // With its own id, which the family's Mac names its picture of it by.
        assertEquals(listOf(CameraEvent(door, "doorbell", "CjY5Y3VKaTZwR3o4Y19YbTVfMF", 1_793_043_725_000L, event = "n:1")), events)
    }

    @Test fun theBatteryDoorbellsFollowUpIsTheSameVisitAndRingsOnce() {
        val first = cameraEvents(message("2026-10-26T19:42:01Z", chime)).single()
        // 44 s later: the press again, with its clip preview, same visit (past the 30 s pause).
        val second = cameraEvents(message("2026-10-26T19:42:45Z", "$chime,$clip"))
        assertEquals(listOf("doorbell"), second.map { it.kind }) // the clip itself isn't an alert
        val visits = Visits()
        assertTrue(visits.admit(first.camera, first.session, first.kind))
        assertFalse(visits.admit(second.single().camera, second.single().session, second.single().kind))
    }

    @Test fun eachEventInAMessageIsReadThePressFirstAndItShowsThePressAlone() {
        val events = cameraEvents(message("2026-10-26T19:42:05Z", "$person,$chime"))
        assertEquals(listOf("doorbell", "person"), events.map { it.kind })
        assertEquals(listOf("n:1", "n:2"), events.map { it.event })
        // The person seen with the press doesn't pop up a card of its own.
        assertEquals("n:1", messageAlert(events) { true }?.event)
    }

    @Test fun aPersonAndMotionInOneMessageShowPersonSeenOnce() {
        val events = cameraEvents(message("2026-10-26T19:42:05Z", "$motion,$person"))
        assertEquals(listOf("person", "motion"), events.map { it.kind })
        // With motion alerts on: "Person seen", and no "Motion seen" over it.
        assertEquals("person", messageAlert(events) { true }?.kind)
        // With person alerts off: the motion.
        assertEquals("motion", messageAlert(events) { it == "motion" }?.kind)
    }

    @Test fun anythingElseIsNoAlert() {
        assertTrue(cameraEvents(message("2026-10-26T19:42:05Z", clip)).isEmpty())
        assertTrue(cameraEvents("""{"relationUpdate":{"type":"CREATED"}}""").isEmpty())
        assertTrue(cameraEvents("not json").isEmpty())
        // A message without a visit (older devices): still an event, with no visit to group it by.
        val bare = cameraEvents(message("2026-10-26T19:42:05Z", """"sdm.devices.events.CameraMotion.Motion":{}"""))
        assertEquals(listOf(CameraEvent(door, "motion", null, 1_793_043_725_000L)), bare)
    }

    @Test fun aTvsOwnSubscriptionKeepsTenMinutesAndGoesAfterADayUnused() {
        val body = ownSubscriptionBody("projects/home-123/topics/doorbell")
        assertEquals("projects/home-123/topics/doorbell", body.getString("topic"))
        assertEquals("600s", body.getString("messageRetentionDuration"))
        assertEquals("86400s", body.getJSONObject("expirationPolicy").getString("ttl"))
        assertEquals("woodruff-family-tv", body.getJSONObject("labels").getString("made-by"))
    }

    @Test fun theCameraListComesBackAsItWasSaved() {
        val cams = listOf(
            Camera("enterprises/p/devices/1", "Front Door", "Entryway", webRtc = true, doorbell = true),
            Camera("enterprises/p/devices/2", "Backyard", null, webRtc = true, doorbell = false),
            Camera("enterprises/p/devices/3", "Garage", "Garage", webRtc = false, doorbell = false),
        )
        assertEquals(cams, camerasFromJson(camerasToJson(cams)))
        assertEquals(emptyList<Camera>(), camerasFromJson(camerasToJson(emptyList())))
    }

    @Test fun anUnreadableSavedListIsNoList() {
        assertTrue(camerasFromJson(null).isEmpty())
        assertTrue(camerasFromJson("{").isEmpty())
        // An entry without a name is left out; the rest stay.
        val one = camerasFromJson("""[{"label":"Ghost"},{"name":"enterprises/p/devices/9","label":"Porch","webRtc":true}]""")
        assertEquals(listOf(Camera("enterprises/p/devices/9", "Porch", null, webRtc = true, doorbell = false)), one)
    }
}
