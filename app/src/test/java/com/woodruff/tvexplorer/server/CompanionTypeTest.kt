package com.woodruff.tvexplorer.server

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.tvs.TvLinks
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The phone's keyboard on this TV (/api/type) and on a linked TV through this one (/api/tvs/type): the TV is named
 * when there's nothing to type into, text meant for a box that has closed never lands in another one (finding 42),
 * and a box left open behind another app or one of Android's screens is never typed into, as the remote's keys
 * never press there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompanionTypeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)
    private val ids = mutableListOf<Long>()
    /** Woodruff's screen, in front (each of its screens tells TvScreen as it starts and stops). */
    private lateinit var screen: ActivityController<ComponentActivity>

    @Before fun woodruffInFront() {
        screen = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        TvScreen.attach(screen.get())
    }

    @After fun closeBoxes() {
        ids.forEach(RemoteInput::unregister)
        TvScreen.detach(screen.get())
    }

    private fun box(label: String, text: String = ""): Long =
        RemoteInput.newId().also { RemoteInput.register(it, label, false, false, text); ids += it }

    private fun type(body: JSONObject) = routes.answerFor(phoneRequest("/api/type", Method.POST, body = body))

    @Test fun theTextBoxOpenOnTheTv() {
        assertEquals(JSONObject.NULL, JSONObject(routes.answerFor(phoneRequest("/api/type")).bodyText()).get("field"))
        val search = box("Search", "har")
        val f = JSONObject(routes.answerFor(phoneRequest("/api/type")).bodyText()).getJSONObject("field")
        assertEquals(search, f.getLong("id"))
        assertEquals("Search", f.getString("label"))
        assertEquals("har", f.getString("text"))
    }

    @Test fun nothingOpenSaysWhichTv() {
        val r = type(JSONObject().put("text", "har"))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
        assertEquals("No text box is open on ${TvLinks.name(ctx)}", r.bodyText())
    }

    @Test fun textGoesToTheBoxItWasTypedFor() {
        val notes = box("Notes")
        box("Search")
        val r = type(JSONObject().put("id", notes).put("text", "milk"))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertEquals("milk", RemoteInput.fields.value.first { it.id == notes }.text)
    }

    @Test fun textForABoxThatClosedNeverLandsInAnother() {
        val search = box("Search")
        RemoteInput.unregister(search)
        val rename = box("Rename", "Den")
        val r = type(JSONObject().put("id", search).put("text", "harbor"))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
        assertEquals("That text box isn’t open on ${TvLinks.name(ctx)} any more.", r.bodyText())
        assertEquals("Den", RemoteInput.fields.value.first { it.id == rename }.text)
    }

    @Test fun withoutAnIdItGoesToTheBoxInFront() {
        val search = box("Search")
        assertEquals(NanoHTTPD.Response.Status.OK, type(JSONObject().put("text", "har")).status)
        assertEquals("har", RemoteInput.target?.text)
        assertEquals(search, RemoteInput.target?.id)
    }

    @Test fun aBoxLeftOpenBehindAndroidsOwnScreenIsNeverTypedIntoNorItsDonePressed() {
        val secret = box("Google Client secret", "old")
        val heard = mutableListOf<RemoteInput.Typed>()
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { RemoteInput.typed.collect { heard += it } }
        try {
            // Android's install prompt over Woodruff: its screen is paused under it and has lost the focus.
            screen.pause().windowFocusChanged(false)
            val get = JSONObject(routes.answerFor(phoneRequest("/api/type")).bodyText())
            assertEquals(JSONObject.NULL, get.get("field"))
            assertEquals(NOT_IN_FRONT, get.getString("note"))
            val r = type(JSONObject().put("id", secret).put("text", "new").put("submit", true))
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
            assertEquals(NOT_IN_FRONT, r.bodyText())
            assertEquals("old", RemoteInput.fields.value.first { it.id == secret }.text)
            assertTrue(heard.isEmpty())
            // In front again: typed, and Done pressed, as before.
            screen.resume().windowFocusChanged(true)
            assertEquals(NanoHTTPD.Response.Status.OK, type(JSONObject().put("id", secret).put("text", "new").put("submit", true)).status)
            assertEquals(listOf(RemoteInput.Typed(secret, "new", true)), heard)
        } finally {
            listening.cancel()
        }
    }

    @Test fun aBoxLeftOpenAfterHomeWasPressedIsNeverTypedInto() {
        val rename = box("This TV's name", "Den")
        // Home pressed, YouTube opened: Woodruff's screen has stopped behind it, the box still registered. (Its
        // screens also leave TvScreen as they stop; this one stays, so nothing another test left there counts.)
        screen.pause().windowFocusChanged(false)
        screen.stop()
        assertEquals(JSONObject.NULL, JSONObject(routes.answerFor(phoneRequest("/api/type")).bodyText()).get("field"))
        val r = type(JSONObject().put("id", rename).put("text", "Garage").put("submit", true))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
        assertEquals(NOT_IN_FRONT, r.bodyText())
        assertEquals("Den", RemoteInput.fields.value.first { it.id == rename }.text)
    }

    @Test fun aLinkedTvThatIsNotLinkedSaysSo() {
        val get = routes.answerFor(phoneRequest("/api/tvs/type", params = mapOf("id" to "no-such-tv")))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, get.status)
        assertEquals("That TV isn’t linked any more.", get.bodyText())
        val post = routes.answerFor(phoneRequest("/api/tvs/type", Method.POST, body = JSONObject().put("id", "no-such-tv").put("field", 3).put("text", "har")))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, post.status)
        assertEquals("That TV isn’t linked any more.", post.bodyText())
    }

    @Test fun aLinkedHubCanTypeButNotRelay() {
        // A linked TV's key opens this TV's own keyboard route, never the relay to the others.
        assertTrue(routes.public(phoneRequest("/api/tvs/type", params = mapOf("k" to "someone-elses-key"))) == null)
    }
}

/** What the TV answers when Woodruff isn't in front: the same words as for the remote's keys. */
private const val NOT_IN_FRONT = "Woodruff Family isn’t on the TV screen. Press Home first."
