package com.woodruff.tvexplorer.server

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.io.ByteArrayInputStream

/**
 * A resume started from the phone begins where the family last stopped, worked out by the TV that
 * plays it, as Home's Continue watching tile does: not at the spot the phone was shown, which the hub
 * may have known for an hour.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchStartTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val shadowApp get() = shadowOf(ctx as Application)

    @Before fun fresh() {
        FamilySync.setEnabled(ctx, false)
        EpisodeShows.clear(ctx)
        PendingPlay.clear()
        ShadowSettings.setCanDrawOverlays(true)
        WatchHome.forget()
    }

    @After fun reset() { EpisodeShows.clear(ctx); PendingPlay.clear(); WatchHome.forget() }

    private fun body(key: String, position: Long, resume: Boolean = false, latest: Boolean = false) =
        JSONObject().put("key", key).put("title", "Backyard Science (2019) - S02E04 - The Rain").put("position", position)
            .apply { if (resume) put("resume", true); if (latest) put("latest", true) }

    @Test fun aResumeStartsWhereTheFamilyLastStopped() {
        val known = setOf("drive:e4")
        val start = WatchStart.pick(body("drive:e4", 720_000, resume = true), "Backyard Science (2019) - S02E04 - The Rain", { emptyList() }) { it in known }
        assertEquals("drive:e4", start.key)
        assertNull("the player reads the spot itself", start.positionMs)
        // Start Over, or a spot given without "resume": where it says.
        assertEquals(0L, WatchStart.pick(body("drive:e4", 0), "x", { emptyList() }) { true }.positionMs)
        assertEquals(720_000L, WatchStart.pick(body("drive:e4", 720_000), "x", { emptyList() }) { true }.positionMs)
        // Nothing known of it on this TV (sharing off, never played here): the spot the phone was shown.
        assertEquals(720_000L, WatchStart.pick(body("drive:e4", 720_000, resume = true), "x", { emptyList() }) { false }.positionMs)
    }

    @Test fun aShowsGoOnPlaysTheEpisodeAnotherTvMovedOnTo() {
        // Den finished S2 E4 and went on: S2 E5 is up next in the merged Continue watching.
        val watching = listOf(RecentItem("drive:e5", "Backyard Science S02E05 The Volcano", 0, 0, 0, upNext = true))
        val start = WatchStart.pick(body("drive:e4", 2_400_000, resume = true, latest = true), "Backyard Science (2019) - S02E04 - The Rain", { watching }) { false }
        assertEquals("drive:e5", start.key)
        assertEquals("Backyard Science S02E05 The Volcano", start.title)
        assertNull(start.positionMs)
        // An episode picked from the list stays that episode.
        assertEquals("drive:e4", WatchStart.pick(body("drive:e4", 0, resume = true), "x", { watching }) { true }.key)
    }

    @Test fun theTvsPlayButtonsAskForAResume() {
        val inProgress = RecentItem("drive:e4", "Backyard Science (2019) - S02E04 - The Rain", 720_000, 1_500_000, 0)
        val tile = WatchJson.episodePlay(inProgress)
        assertTrue(tile.getBoolean("resume") && tile.getBoolean("latest"))
        val film = com.woodruff.tvexplorer.cloud.DriveFile("m1", "Midnight Harbor (2024).mkv", "video/x-matroska", 1, 0, null)
        val seen = listOf(RecentItem("drive:m1", "Midnight Harbor (2024).mkv", 2_710_000, 6_840_000, 0))
        assertTrue(WatchJson.filmPlay(film, seen).getBoolean("resume"))
        assertFalse(WatchJson.filmPlay(film, seen).has("latest"))
        val over = WatchJson.filmRestart(film, seen)!!
        assertFalse("Start Over starts at 0, wherever anyone stopped", over.has("resume"))
    }

    @Test fun thePhonesResumeBeatsTheSpotItWasShown() {
        // Den watched to 40:00 and its progress was merged in here; the phone was shown 12:00 an hour ago.
        RecentStore(ctx).save("drive:e4", "Backyard Science (2019) - S02E04 - The Rain", 2_400_000, 3_000_000)
        val r = post("/api/watch", body("drive:e4", 720_000, resume = true))
        assertTrue(JSONObject(text(r)).getBoolean("started"))
        val started = shadowApp.nextStartedActivity
        assertFalse("no spot given: the player starts where the family last stopped", started.hasExtra("position"))
        assertEquals(2_400_000L, RecentStore(ctx).resumePosition("drive:e4"))
        // Start Over (no resume) keeps its spot.
        post("/api/watch", body("drive:e4", 0))
        assertEquals(0L, shadowApp.nextStartedActivity.getLongExtra("position", -1L))
    }

    @Test fun thePhonesWatchTabIsBuiltAgainOnceTheOtherTvsProgressIsIn() {
        var built = 0
        WatchHome.get { built++; "{}" }
        WatchHome.get { built++; "{}" }
        assertEquals(1, built)
        // Another TV's progress merged in (FamilySync.updates ticks): the next answer is built afresh.
        @Suppress("UNCHECKED_CAST")
        val ticks = FamilySync::class.java.getDeclaredField("_updates").apply { isAccessible = true }.get(FamilySync) as MutableStateFlow<Int>
        ticks.value = ticks.value + 1
        WatchHome.get { built++; "{}" }
        assertEquals(2, built)
    }

    // ---- A request, as NanoHTTPD hands it over (a bad request becomes a 400, as TransferServer does) ----

    private val routes = CompanionRoutes(ctx)

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
