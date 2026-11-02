package com.woodruff.tvexplorer.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.tvs.TvLinks
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The phone's keyboard on this TV (/api/type) and on a linked TV through this one (/api/tvs/type): the TV is named
 * when there's nothing to type into, and text meant for a box that has closed never lands in another one (finding 42).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompanionTypeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)
    private val ids = mutableListOf<Long>()

    @After fun closeBoxes() = ids.forEach(RemoteInput::unregister)

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
