package com.woodruff.tvexplorer.nest

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.server.CompanionRoutes
import com.woodruff.tvexplorer.server.answerFor
import com.woodruff.tvexplorer.server.bodyText
import com.woodruff.tvexplorer.server.phoneRequest
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.UUID

/**
 * A camera alert's picture from the family's Mac (POST /api/camera-picture): taken at once, a body that
 * isn't one turned down, the picture decoded to its card's size and rounded, and the card of its alert
 * showing it, whether the picture came first or the card did. Real pictures (native graphics) on a
 * 1080p TV (xhdpi: a card's picture is 160 px high).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class AlertPicturesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val routes = CompanionRoutes(app)
    private val at = 1_800_000_000_000L

    /** A camera no other test has used. */
    private fun camera() = "enterprises/p/devices/${UUID.randomUUID()}"

    /** A camera's picture as the Mac sends it: [width] × [height], speckled (so it's well over 64 KB as a JPEG). */
    private fun jpeg(width: Int = 720, height: Int = 405): ByteArray {
        val rnd = Random(7)
        val pixels = IntArray(width * height) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun body(camera: String, session: String, event: String, kind: String = "person", picture: ByteArray = jpeg()) = JSONObject()
        .put("camera", camera).put("name", "Backyard").put("kind", kind).put("event", event).put("session", session)
        .put("at", at).put("cropped", true).put("picture", Base64.encodeToString(picture, Base64.NO_WRAP))

    private fun post(body: JSONObject?, raw: ByteArray? = null) =
        routes.answerFor(phoneRequest("/api/camera-picture", NanoHTTPD.Method.POST, body = body, raw = raw))

    /** Waits for [get] (decoding is in the background), at most 10 s. */
    private fun <T> waitFor(what: String, get: () -> T?): T {
        val until = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            get()?.let { return it }
            check(System.currentTimeMillis() < until) { "never: $what" }
            Thread.sleep(20)
        }
    }

    @Test fun aPictureIsTakenAtOnceAndDrawnAtItsCardsSizeWithRoundedCorners() {
        val cam = camera()
        val b = body(cam, "visit-a", "e-a")
        assertTrue("well over the 64 KB other requests are held to", b.toString().length > 64 * 1024)
        val r = post(b)
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertTrue(JSONObject(r.bodyText()).getBoolean("ok"))
        val picture = waitFor("the picture") { AlertPictures.forAlert(CameraAlert(cam, "person", "visit-a", "e-a", at)) }
        // 80 dp high, 16:9 as the camera's picture is.
        assertEquals(160, picture.height)
        assertEquals(284, picture.width)
        // Its corners rounded: see-through at the very corner, the picture itself in the middle.
        assertEquals(0, Color.alpha(picture.getPixel(0, 0)))
        assertEquals(255, Color.alpha(picture.getPixel(picture.width / 2, picture.height / 2)))
    }

    @Test fun aBodyThatIsntAPictureIsTurnedDown() {
        val cam = camera()
        fun status(body: JSONObject?, raw: ByteArray? = null) = post(body, raw).status
        val bad = NanoHTTPD.Response.Status.BAD_REQUEST
        assertEquals(bad, status(body(cam, "s", "e").apply { remove("camera") }))
        assertEquals(bad, status(body(cam, "s", "e").put("picture", Base64.encodeToString("hello".toByteArray(), Base64.NO_WRAP))))
        assertEquals(bad, status(body(cam, "s", "e").put("picture", "")))
        assertEquals(bad, status(null, raw = "not a picture".toByteArray()))
        assertEquals(bad, status(null, raw = ByteArray(0)))
    }

    @Test fun aCardUpTakesItsPictureInPlace() {
        Notices.load(app)
        Notices.dismissLatest()
        val cam = camera()
        val n = Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard", alert = CameraAlert(cam, "person", "visit-b", "e-b", at))
        assertNull(Notices.latest.value?.picture)
        assertEquals(NanoHTTPD.Response.Status.OK, post(body(cam, "visit-b", "e-b")).status)
        val shown = waitFor("the picture on the card") { Notices.latest.value?.takeIf { it.picture != null } }
        // The same card, with the same words.
        assertEquals(n.id, shown.id)
        assertEquals("Person seen", shown.title)
        // The Notifications list never holds a picture.
        assertNull(Notices.items.value.first { it.id == n.id }.picture)
        Notices.dismissLatest()
    }

    @Test fun aPictureThatComesFirstWaitsForItsCard() {
        Notices.load(app)
        Notices.dismissLatest()
        val cam = camera()
        // The Mac's close-up of the person seen, then the doorbell rings in the same visit (passed on by another TV).
        assertEquals(NanoHTTPD.Response.Status.OK, post(body(cam, "visit-c", "e-person")).status)
        waitFor("the picture") { AlertPictures.forAlert(CameraAlert(cam, "doorbell", "visit-c", null, at)) }
        val r = routes.answerFor(phoneRequest("/api/doorbell", NanoHTTPD.Method.POST, body = JSONObject()
            .put("camera", cam).put("label", "Front Door").put("session", "visit-c").put("event", "e-press").put("at", at)))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertEquals(CameraAlert(cam, "doorbell", "visit-c", "e-press", at), Notices.items.value.first().alert)
        val card = Notices.latest.value
        assertEquals("Someone’s at the door", card?.title)
        assertEquals(160, card?.picture?.height)
        Notices.dismissLatest()
    }

    @Test fun anotherVisitsPictureIsntTaken() {
        Notices.load(app)
        Notices.dismissLatest()
        val cam = camera()
        Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard", alert = CameraAlert(cam, "person", "visit-d", "e-d", at))
        assertEquals(NanoHTTPD.Response.Status.OK, post(body(cam, "visit-e", "e-e")).status)
        waitFor("the other picture") { AlertPictures.forAlert(CameraAlert(cam, "person", "visit-e", "e-e", at)) }
        Thread.sleep(200)
        assertEquals("Person seen", Notices.latest.value?.title)
        assertNull(Notices.latest.value?.picture)
        Notices.dismissLatest()
    }
}
