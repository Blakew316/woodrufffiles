package com.woodruff.tvexplorer.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.nowplaying.Playing
import com.woodruff.tvexplorer.nowplaying.PlayerCommand
import com.woodruff.tvexplorer.tvs.TvLinks
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.io.ByteArrayInputStream
import java.io.File

/** The phone's controls for what this TV plays (/api/media), and a phone start that says whether it's on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompanionMediaTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val routes = CompanionRoutes(ctx)
    private val film = Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000, key = "drive:m1")

    // NowPlaying and PendingPlay are the process's own: a player test that ran earlier in this JVM may have left its
    // film "playing" there, so each test starts from nothing playing and nothing waiting.
    @Before fun nothingPlaysYet() { NowPlaying.ownPlayer = null; PendingPlay.clear() }
    @After fun closePlayer() { NowPlaying.ownPlayer = null; PendingPlay.clear() }

    @Test fun skipsReachWoodruffsPlayerOnlyWhileItListens() = runBlocking {
        NowPlaying.ownPlayer = film
        // Nothing collects the commands yet: the phone was only offered Play/Pause, and asking anyway says why.
        val refused = post("/api/media", JSONObject().put("action", "fwd30"))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, refused.status)
        assertEquals("That doesn’t work with what’s playing on ${TvLinks.name(ctx)}", text(refused))

        val got = CompletableDeferred<PlayerCommand>()
        val player = launch(start = CoroutineStart.UNDISPATCHED) { got.complete(NowPlaying.commands.first()) }
        val done = post("/api/media", JSONObject().put("action", "back10"))
        assertEquals(NanoHTTPD.Response.Status.OK, done.status)
        val o = JSONObject(text(done))
        assertTrue(o.getBoolean("ok"))
        assertTrue(o.getBoolean("playing"))
        assertEquals(PlayerCommand.BACK_10, withTimeout(2_000) { got.await() })
        player.cancelAndJoin()
    }

    @Test fun nothingPlayingAndUnknownActionsAreSaidPlainly() {
        val none = post("/api/media", JSONObject().put("action", "next"))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, none.status)
        assertEquals("Nothing is playing on ${TvLinks.name(ctx)}", text(none))
        val odd = post("/api/media", JSONObject().put("action", "rewind"))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, odd.status)
        assertEquals("Unknown action", text(odd))
    }

    @Test fun playPauseAnswersWhatItWillBe() {
        NowPlaying.ownPlayer = film
        val r = post("/api/media", JSONObject().put("action", "playpause"))
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        assertFalse("playing → paused", JSONObject(text(r)).getBoolean("playing"))
    }

    @Test fun aFileThatWentAwaySaysSo() {
        val gone = File(ctx.cacheDir, "not-here.mp4").path
        val r = post("/api/play", JSONObject().put("key", gone).put("title", "Family vacation.mp4").put("position", 0))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
        assertEquals("That video isn’t on ${TvLinks.name(ctx)} any more.", text(r))
    }

    @Test fun aStartSaysWhetherItIsOnScreen() {
        val f = File(ctx.cacheDir, "Family vacation.mp4").apply { writeBytes(ByteArray(16)) }
        // Another app in front and no pop-up permission: it waits on the TV, and the answer says what to do there.
        ShadowSettings.setCanDrawOverlays(false)
        val waits = JSONObject(text(post("/api/play", JSONObject().put("path", f.path))))
        assertTrue(waits.getBoolean("ok"))
        assertFalse(waits.getBoolean("started"))
        assertEquals("It’s waiting on the TV: open Woodruff Family there.", waits.getString("message"))
        assertTrue(PendingPlay.holding)
        // Allowed to pop up: on screen at once, and nothing waits any more.
        ShadowSettings.setCanDrawOverlays(true)
        val drive = JSONObject(text(post("/api/watch", JSONObject().put("key", "drive:m1").put("title", "Midnight Harbor (2024).mkv").put("position", 2_710_000))))
        assertTrue(drive.getBoolean("started"))
        assertFalse(drive.has("message"))
        assertFalse(PendingPlay.holding)
        val notDrive = post("/api/watch", JSONObject().put("key", f.path))
        assertEquals("Only Google Drive videos can be watched together", text(notDrive))
    }

    // ---- A request, as NanoHTTPD hands it over (a bad request becomes a 400, as TransferServer does) ----

    private fun text(r: NanoHTTPD.Response) = r.data.bufferedReader().readText()

    private fun post(uri: String, body: JSONObject): NanoHTTPD.Response {
        val bytes = body.toString().toByteArray()
        val s = object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies(): NanoHTTPD.CookieHandler? = null
            override fun getHeaders(): Map<String, String> = mapOf("content-length" to bytes.size.toString())
            override fun getInputStream() = ByteArrayInputStream(bytes)
            override fun getMethod() = NanoHTTPD.Method.POST
            @Deprecated("") override fun getParms(): Map<String, String> = emptyMap()
            override fun getParameters(): Map<String, List<String>> = emptyMap()
            override fun getQueryParameterString(): String = ""
            override fun getUri() = uri
            override fun parseBody(files: MutableMap<String, String>?) {}
            override fun getRemoteIpAddress() = "127.0.0.1"
            override fun getRemoteHostName() = "localhost"
        }
        return try {
            routes.handle(s) ?: NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "")
        } catch (e: IllegalArgumentException) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", e.message)
        }
    }
}
