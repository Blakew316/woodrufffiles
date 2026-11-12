package com.woodruff.tvexplorer.nowplaying

import android.media.session.PlaybackState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.tvs.TvLinks
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the phone may do to what a TV plays, and how ↺10, 30↻ and Next reach Woodruff's own player. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NowPlayingTest {
    private val film = Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000, key = "drive:m1")

    // NowPlaying is the process's own: a player test that ran earlier in this JVM may have left its film "playing"
    // there, so each test starts from nothing playing.
    @Before fun nothingPlaysYet() { NowPlaying.ownPlayer = null }
    @After fun closePlayer() { NowPlaying.ownPlayer = null }

    @Test fun ownPlayerOffersOnlyPlayPauseUntilItListens() {
        assertEquals(listOf("playpause"), ownCan(film, listens = false))
        assertEquals(listOf("playpause", "back10", "fwd30"), ownCan(film, listens = true))
        assertEquals(listOf("playpause", "back10", "fwd30", "next"), ownCan(film.copy(hasNext = true), listens = true))
        // Next waits for the player too: nothing is promised that nothing would do.
        assertEquals(listOf("playpause"), ownCan(film.copy(hasNext = true), listens = false))
    }

    @Test fun skipsAreOfferedOnlyWhereTheOwnPlayerCanJump() {
        // Still opening, or a file without an index: ↺10 and 30↻ would do nothing (or start it over), Next still goes on.
        assertEquals(listOf("playpause"), ownCan(film.copy(seekable = false), listens = true))
        assertEquals(listOf("playpause", "next"), ownCan(film.copy(seekable = false, hasNext = true), listens = true))
        // Like Next, it stays on the TV: the phone only sees what it can do.
        val sent = Playing.fromJson(JSONObject(film.copy(seekable = false, can = listOf("playpause")).toJson().toString()))!!
        assertEquals(listOf("playpause"), sent.can)
    }

    @Test fun otherAppsOfferWhatTheirSessionSaysTheyCanDo() {
        assertEquals(listOf("playpause"), sessionCan(0L))
        assertEquals(listOf("playpause"), sessionCan(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE))
        assertEquals(listOf("playpause", "back10", "fwd30"), sessionCan(PlaybackState.ACTION_SEEK_TO))
        assertEquals(listOf("playpause", "next"), sessionCan(PlaybackState.ACTION_SKIP_TO_NEXT))
        assertEquals(listOf("playpause", "back10", "fwd30", "next"), sessionCan(PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT))
    }

    @Test fun skipsNeverLandBeforeTheStartOrPastTheEnd() {
        assertEquals(2_700_000L, PlayerCommand.BACK_10.target(2_710_000, 6_840_000))
        assertEquals(0L, PlayerCommand.BACK_10.target(4_000, 6_840_000))
        assertEquals(2_740_000L, PlayerCommand.FORWARD_30.target(2_710_000, 6_840_000))
        assertEquals(6_840_000L, PlayerCommand.FORWARD_30.target(6_830_000, 6_840_000))
        assertEquals(90_000L, PlayerCommand.FORWARD_30.target(60_000, 0))   // length not known: no end to stop at
        assertNull(PlayerCommand.NEXT.target(2_710_000, 6_840_000))
    }

    @Test fun actionsTravelByTheirWireWords() {
        assertEquals(MediaAction.FORWARD_30, MediaAction.of("fwd30"))
        assertEquals(MediaAction.PLAY_PAUSE, MediaAction.of("playpause"))
        assertNull(MediaAction.of("rewind"))
        assertNull(MediaAction.of(null))
        assertEquals(PlayerCommand.BACK_10, PlayerCommand.of(MediaAction.BACK_10))
        assertEquals(PlayerCommand.NEXT, PlayerCommand.of(MediaAction.NEXT))
        assertNull(PlayerCommand.of(MediaAction.PLAY_PAUSE))   // Play/Pause goes by the remote's key
    }

    @Test fun whatIsPlayingCrossesTheLinkWithWhatItCanDo() {
        val sent = film.copy(can = listOf("playpause", "back10", "fwd30"), hasNext = true)
        val o = JSONObject(sent.toJson().toString())
        assertEquals("drive:m1", o.getString("key"))
        assertEquals(3, o.getJSONArray("can").length())
        val back = Playing.fromJson(o)!!
        assertEquals(listOf("playpause", "back10", "fwd30"), back.can)
        assertEquals("drive:m1", back.key)
        assertEquals(2_710_000L, back.positionMs)
        assertFalse("Next stays a decision of the TV that plays", back.hasNext)
        // An older Woodruff says nothing about it: Play/Pause only. Words this phone doesn't know are dropped.
        val old = Playing.fromJson(JSONObject().put("title", "Ironwood").put("app", "Woodruff Family").put("playing", true))!!
        assertTrue(old.can.isEmpty()); assertNull(old.key)
        assertEquals(listOf("playpause"), Playing.fromJson(JSONObject().put("title", "Orbit").put("can", org.json.JSONArray(listOf("playpause", "rewind"))))!!.can)
        assertNull(Playing.fromJson(JSONObject().put("title", " ")))
    }

    @Test fun commandsReachTheOwnPlayerOnlyWhileItListens() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        NowPlaying.ownPlayer = film
        assertFalse(NowPlaying.playerListens)
        assertEquals(listOf("playpause"), NowPlaying.current(c)!!.can)
        assertEquals(CommandResult.CantDo, NowPlaying.command(c, PlayerCommand.FORWARD_30))

        val got = kotlinx.coroutines.CompletableDeferred<PlayerCommand>()
        val player = launch(start = CoroutineStart.UNDISPATCHED) { got.complete(NowPlaying.commands.first()) }
        yield()
        assertTrue(NowPlaying.playerListens)
        assertEquals(listOf("playpause", "back10", "fwd30"), NowPlaying.current(c)!!.can)
        assertEquals(CommandResult.CantDo, NowPlaying.command(c, PlayerCommand.NEXT))   // nothing queued after it
        assertEquals(CommandResult.Done(true), NowPlaying.command(c, PlayerCommand.FORWARD_30))
        assertEquals(PlayerCommand.FORWARD_30, withTimeout(2_000) { got.await() })
        player.cancelAndJoin()
    }

    @Test fun nothingPlayingWhenNoAppCanBeSeen() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No player of ours, and Robolectric's media sessions list nothing: nothing to steer.
        assertEquals(CommandResult.NothingPlaying, NowPlaying.command(c, PlayerCommand.BACK_10))
        assertNull(NowPlaying.current(c))
    }

    @Test fun theAppleTvsInputIsToldFromAnAppPlaying() {
        val input = Playing("Apple TV HDMI 4", null, "TV", true, 0, 0)
        assertTrue(isAppleTvInput(input))
        assertTrue(isAppleTvInput(input.copy(title = "Apple TV", app = "Live TV")))
        // Another input; an input left behind (no longer playing); the app called Apple TV, with a
        // film's name or none; Woodruff's own film; nothing.
        assertFalse(isAppleTvInput(input.copy(title = "HDMI 2")))
        assertFalse(isAppleTvInput(input.copy(playing = false)))
        assertFalse(isAppleTvInput(Playing("Severance", "Apple TV", "Apple TV", true, 60_000, 3_000_000)))
        assertFalse(isAppleTvInput(Playing("Apple TV", null, "Apple TV", true, 0, 0)))
        assertFalse(isAppleTvInput(film))
        assertFalse(isAppleTvInput(null))
    }

    @Test fun aLinkedTvMayAlsoPassOnTheControlsButNothingMore() {
        assertTrue("/api/media" in TvLinks.LINK_ROUTES)
        // Starting a video on a linked TV was already allowed; marking and listing stay with the hub's own phone.
        assertTrue("/api/watch" in TvLinks.LINK_ROUTES)
        for (r in listOf("/api/library", "/api/library/title", "/api/library/episodes", "/api/library/search", "/api/library/watched",
            "/api/continue/remove", "/api/watch/home", "/api/poster", "/api/tvs/watch", "/api/tvs/media", "/api/play")) {
            assertFalse("$r is for the hub's own phone only", r in TvLinks.LINK_ROUTES)
        }
    }
}
