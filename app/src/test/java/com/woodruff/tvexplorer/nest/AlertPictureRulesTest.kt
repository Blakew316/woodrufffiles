package com.woodruff.tvexplorer.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which camera alert a picture from the family's Mac is for (its visit, else its event, else its camera
 * within 2 minutes), which of several a card shows, how many are kept and for how long, and the size
 * a picture is drawn at on a card.
 */
class AlertPictureRulesTest {
    private val door = "enterprises/p/devices/front-door"
    private val yard = "enterprises/p/devices/backyard"
    private val t = 1_800_000_000_000L

    private fun alert(camera: String = door, kind: String = "doorbell", session: String? = "visit-1", event: String? = "e-1", at: Long = t) =
        cameraAlert(camera, kind, session, event, at)

    private fun shelved(picture: CameraAlert, cropped: Boolean = true, got: Long = 0L) = Shelved(picture, cropped, got, picture.event ?: "")

    // ---- Which alert a picture is for ---------------------------------------------------------------

    @Test fun aPictureOfTheSameVisitIsTheAlertsWhicheverEventItWasTakenFor() {
        // The doorbell rang; the Mac's close-up is of the person seen in the same visit.
        val ring = alert(event = "e-press")
        assertTrue(pictureFits(alert(kind = "person", event = "e-person"), ring))
        assertTrue(pictureFits(alert(event = "e-press"), ring))
    }

    @Test fun anotherVisitOnTheSameCameraIsSomeoneElse() {
        val first = alert(session = "visit-1", event = "e-1")
        assertFalse(pictureFits(alert(session = "visit-2", event = "e-2"), first))
        // Even at the same moment, and with no event to tell them apart.
        assertFalse(pictureFits(alert(session = "visit-2", event = null), alert(session = "visit-1", event = null)))
    }

    @Test fun withoutAVisitTheEventDecides() {
        // An older camera that doesn't say which visit: its press and the person seen are two events.
        val press = alert(session = null, event = "e-press")
        assertTrue(pictureFits(alert(session = null, event = "e-press"), press))
        assertFalse(pictureFits(alert(session = null, kind = "person", event = "e-person"), press))
        // A picture that doesn't say the visit still fits by its event.
        assertTrue(pictureFits(alert(session = null, event = "e-1"), alert(session = "visit-1", event = "e-1")))
    }

    @Test fun anAlertThatNamesNeitherTakesItsCamerasPictureFromWithinTwoMinutes() {
        // Passed on by a TV too old to say its visit or event.
        val relayed = alert(session = null, event = null)
        assertTrue(pictureFits(alert(at = t + 119_000), relayed))
        assertTrue(pictureFits(alert(at = t - 120_000), relayed))
        assertFalse(pictureFits(alert(at = t + 121_000), relayed))
        assertFalse(pictureFits(alert(camera = yard), relayed))
    }

    @Test fun aPictureThatNamesNothingNeverFitsAnAlertThatNamesItsVisit() {
        // A vehicle the Mac saw itself: no visit or event of Google's, on the camera that just saw a person.
        val vehicle = alert(kind = "vehicle", session = null, event = null)
        assertFalse(pictureFits(vehicle, alert(kind = "person")))
        assertFalse(pictureFits(vehicle, alert(kind = "person", session = null)))
    }

    @Test fun blankIdsAreNone() {
        val blank = cameraAlert(" $door ", "person", " ", "", t)
        assertEquals(CameraAlert(door, "person", null, null, t), blank)
        // So it's matched by its camera and time, as one that names neither.
        assertTrue(pictureFits(alert(at = t + 30_000), blank))
    }

    // ---- Which of several a card shows --------------------------------------------------------------

    @Test fun theAlertsOwnEventsPictureComesFirstThenItsKind() {
        val ring = alert(event = "e-press")
        val person = shelved(alert(kind = "person", event = "e-person"), got = 2)
        val press = shelved(alert(kind = "doorbell", event = "e-press"), cropped = false, got = 1)
        assertSame(press, bestPicture(ring, listOf(person, press)))
        // Without its own event's, one of its kind.
        val motion = shelved(alert(kind = "motion", event = "e-motion"), got = 3)
        val bell = shelved(alert(kind = "doorbell", event = "e-other-press"), cropped = false, got = 1)
        assertSame(bell, bestPicture(alert(event = "e-none"), listOf(motion, bell)))
    }

    @Test fun thenACloseUpThenTheNearestInTimeThenTheNewest() {
        val relayed = alert(kind = "person", session = null, event = null)
        val whole = shelved(alert(kind = "person", at = t + 5_000), cropped = false)
        val close = shelved(alert(kind = "person", at = t + 60_000), cropped = true)
        assertSame(close, bestPicture(relayed, listOf(whole, close)))
        val far = shelved(alert(kind = "person", at = t + 60_000))
        val near = shelved(alert(kind = "person", at = t - 5_000))
        assertSame(near, bestPicture(relayed, listOf(far, near)))
        val older = shelved(alert(kind = "person"), got = 1)
        val newer = shelved(alert(kind = "person"), got = 2)
        assertSame(newer, bestPicture(relayed, listOf(older, newer)))
    }

    @Test fun noPictureFitsNoAlert() {
        assertNull(bestPicture(alert(session = "visit-9", event = "e-9"), listOf(shelved(alert()))))
        assertNull(bestPicture(alert(), emptyList<Shelved<String>>()))
    }

    // ---- How many are kept, and for how long --------------------------------------------------------

    @Test fun aPictureThatCameFirstWaitsThreeMinutesForItsAlert() {
        val shelf = PictureShelf<String>()
        shelf.add(Shelved(alert(), true, 1_000L, "close-up"), 1_000L)
        assertEquals("close-up", shelf.find(alert(), 1_000L + 180_000)?.picture)
        assertNull(shelf.find(alert(), 1_000L + 180_001))
        assertEquals(0, shelf.count(1_000L + 180_001))
    }

    @Test fun atMostTenWaitTheOldestGoingFirst() {
        val shelf = PictureShelf<String>()
        for (i in 0 until 11) shelf.add(Shelved(alert(session = "visit-$i", event = "e-$i"), true, i.toLong(), "picture $i"), i.toLong())
        assertEquals(10, shelf.count(11L))
        assertNull(shelf.find(alert(session = "visit-0", event = "e-0"), 11L))
        assertEquals("picture 1", shelf.find(alert(session = "visit-1", event = "e-1"), 11L)?.picture)
        assertEquals("picture 10", shelf.find(alert(session = "visit-10", event = "e-10"), 11L)?.picture)
    }

    // ---- Its size on a card -------------------------------------------------------------------------

    @Test fun aPictureIsAsWideAsItIsAtItsCardsHeightSquareTo16By9() {
        val h = 160 // 80 dp on a 1080p screen
        assertEquals(PictureSize(284, 160), pictureBox(720, 405, h)) // a camera's whole picture
        assertEquals(PictureSize(213, 160), pictureBox(720, 540, h)) // a close-up, 4:3
        assertEquals(PictureSize(160, 160), pictureBox(540, 540, h)) // a square close-up
        assertEquals(PictureSize(160, 160), pictureBox(405, 720, h)) // narrower than square: square
        assertEquals(PictureSize(284, 160), pictureBox(1000, 300, h)) // wider than 16:9: 16:9
    }

    @Test fun onlyAPictureNarrowerThanSquareOrWiderThan16By9IsCut() {
        assertEquals(PictureSize(720, 405), pictureCrop(720, 405))
        assertEquals(PictureSize(720, 540), pictureCrop(720, 540))
        assertEquals(PictureSize(405, 405), pictureCrop(405, 720))
        assertEquals(PictureSize(533, 300), pictureCrop(1000, 300))
    }

    @Test fun aPictureIsDecodedAtNoLessThanItsCardsSize() {
        assertEquals(4, pictureSample(720, 160)) // 180 px high, drawn at 160
        assertEquals(2, pictureSample(405, 160))
        assertEquals(1, pictureSample(300, 160))
        assertEquals(1, pictureSample(100, 160))
    }
}
