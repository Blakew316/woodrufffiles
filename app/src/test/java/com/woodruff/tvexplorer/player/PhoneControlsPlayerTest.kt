package com.woodruff.tvexplorer.player

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nowplaying.CommandResult
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.nowplaying.PlayerCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * The phone's ↺10, 30↻ and Next reach Woodruff's own player while it's on screen, as the phone would
 * send them (NowPlaying.command, from /api/media), and the phone is told what the player can do. A
 * real PlayerActivity and ExoPlayer over small sound files standing in for films (Media3 opens them
 * with no decoder), paused so their positions hold still on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PhoneControlsPlayerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    /** Every player opened, closed after each test: one left on screen would answer the next test's phone. */
    private val opened = ArrayList<ActivityController<PlayerActivity>>()

    // NowPlaying is the process's own: start each test from nothing playing, whatever ran before.
    @Before fun nothingPlaysYet() { NowPlaying.ownPlayer = null }

    @After fun closeThePlayers() {
        opened.asReversed().forEach { it.close() }
        idle(50)
        NowPlaying.ownPlayer = null
    }

    @Test fun whileOnScreenItAnswersThePhoneAndSaysWhatItCanDo() {
        val c = open("Midnight Harbor (2024).wav")
        ready(c, 20_000)
        assertTrue(NowPlaying.playerListens)
        val now = NowPlaying.current(app)!!
        assertEquals("Midnight Harbor (2024)", now.title)
        assertFalse("paused", now.playing)
        // One film, nothing after it: ↺10 and 30↻, no Next.
        assertEquals(listOf("playpause", "back10", "fwd30"), now.can)
        // A file on this TV: the phone isn't told its path (only a Drive film's key, for its poster).
        assertNull(now.key)

        c.pause().stop()
        idle(50)
        assertFalse("stopped: it no longer listens", NowPlaying.playerListens)
        assertNull(NowPlaying.ownPlayer)
    }

    @Test fun skipsLandTenBackAndThirtyAheadWithTheControlsUp() {
        val c = open("Midnight Harbor (2024).wav")
        val p = ready(c, 20_000)
        val view = playerView(c)
        // Media3 brings the controls up by itself over a paused film: off here, to see the phone's skip do it.
        view.controllerAutoShow = false
        view.hideController()
        idle(300)
        assertFalse(view.isControllerFullyVisible)

        assertEquals(CommandResult.Done(false), NowPlaying.command(app, PlayerCommand.BACK_10))
        settleAt(p, 10_000)
        assertFalse("paused stays paused", p.playWhenReady)
        assertTrue("the controls come up to show where it went", view.isControllerFullyVisible)

        assertEquals(CommandResult.Done(false), NowPlaying.command(app, PlayerCommand.FORWARD_30))
        settleAt(p, 40_000)
    }

    @Test fun skipsAreAimedAsTheRemotesAre() {
        val p = ready(open("Midnight Harbor (2024).wav", at = 29_000), 29_000)
        waitFor("the whole film loaded", p) { p.bufferedPosition >= p.duration }
        // Just short of the end of what's loaded: exactly on the spot (snapping to the next clean frame
        // would ask for more), where the player's own default is to snap.
        NowPlaying.command(app, PlayerCommand.FORWARD_30)
        assertEquals(SeekParameters.EXACT, p.seekParameters)
        settleAt(p, 59_000)
        // Well inside it: to the first clean frame after the spot, aimed afresh for each skip.
        NowPlaying.command(app, PlayerCommand.BACK_10)
        assertEquals(SeekAim.SNAP, p.seekParameters)
        settleAt(p, 49_000)
    }

    @Test fun aWatchTogetherLeaderSkipsExactlySoItsTvsLineUp() {
        val p = ready(open("Midnight Harbor (2024).wav", together = true), 20_000)
        NowPlaying.command(app, PlayerCommand.BACK_10)
        assertEquals(SeekParameters.EXACT, p.seekParameters)
        settleAt(p, 10_000)
    }

    @Test fun nextIsThePlayersOwnNextEpisode() {
        val first = File(tmp.root, "Backyard Science S01E01.wav")
        val c = open(first.name, "Backyard Science S01E02.wav")
        val p = ready(c, 20_000)
        // It plays on into the next one: by the player's own clock (sound would race to its end on the JVM).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        assertEquals(listOf("playpause", "back10", "fwd30", "next"), NowPlaying.current(app)!!.can)

        assertEquals(CommandResult.Done(false), NowPlaying.command(app, PlayerCommand.NEXT))
        waitFor("on the next episode, playing", p) { p.currentMediaItemIndex == 1 && p.isPlaying }
        p.pause()
        assertFalse(c.get().isFinishing)
        // As with the TV's own Next Episode: the one left counts as watched, and leaves Continue watching.
        assertTrue(Watched.isWatched(app, first.path))
        assertTrue(RecentStore(app).all().none { it.key == first.path })
        // Nothing after the last one: the phone isn't offered Next any more, and a late one is turned down.
        waitFor("the phone told") { NowPlaying.current(app)!!.let { it.title.endsWith("S01E02") && it.can == listOf("playpause", "back10", "fwd30") } }
        assertEquals(CommandResult.CantDo, NowPlaying.command(app, PlayerCommand.NEXT))
        idle(100)
        assertEquals(1, p.currentMediaItemIndex)
    }

    @Test fun onlyThePlayerInFrontAnswersAndTheOneUnderItNeverSpeaksForIt() {
        val under = open("Ironwood (2025).wav")
        val pUnder = ready(under, 20_000)
        // A film started over it (from the phone, say): the new player starts before the first one stops.
        under.pause()
        val over = Robolectric.buildActivity(PlayerActivity::class.java, intentFor(listOf("Orbit (2021).wav"), 20_000)).create().start().also(opened::add)
        val pOver = ready(over, 20_000)
        assertEquals("Orbit (2021)", NowPlaying.ownPlayer?.title)

        NowPlaying.command(app, PlayerCommand.BACK_10)
        settleAt(pOver, 10_000)
        assertEquals("the film underneath stays where it was", 20_000L, pUnder.currentPosition)

        // The first one stops under it: the phone still hears the new film is playing.
        over.resume()
        under.stop()
        idle(50)
        assertEquals("Orbit (2021)", NowPlaying.ownPlayer?.title)
        assertTrue(NowPlaying.playerListens)

        // Back from the new film: the first one is in front again and answers; the new one stopping says nothing over it.
        over.pause()
        under.restart().resume()
        val again = ready(under, 20_000)
        over.stop()
        idle(50)
        assertEquals("Ironwood (2025)", NowPlaying.ownPlayer?.title)
        NowPlaying.command(app, PlayerCommand.FORWARD_30)
        settleAt(again, 50_000)
    }

    // ---- A player on screen ----------------------------------------------------------------------

    /**
     * Woodruff's intent for [names] (sound files), from the first, opening paused at [at] as a film
     * put back after an update does: nothing keeps time on the JVM, and sound would race to its end.
     */
    private fun intentFor(names: List<String>, at: Long, together: Boolean = false) =
        names.map { wav(File(tmp.root, it)).path }.let { paths ->
            PlayerActivity.intent(app, paths, names.map { it.substringBeforeLast('.') }, paths.first(), positionMs = at, together = together)
                .putExtra(com.woodruff.tvexplorer.update.Restore.EXTRA_PAUSED, true)
        }

    /** The player on screen, opened on [names] (sound files) paused at [at]. */
    private fun open(vararg names: String, at: Long = 20_000, together: Boolean = false): ActivityController<PlayerActivity> =
        Robolectric.buildActivity(PlayerActivity::class.java, intentFor(names.toList(), at, together)).setup().also(opened::add)

    private fun playerView(c: ActivityController<PlayerActivity>): PlayerView {
        fun find(v: View): PlayerView? = v as? PlayerView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(c.get().window.decorView)!!
    }

    /** [c]'s player once it can jump (opened, its length and index known), paused at [atMs]. */
    private fun ready(c: ActivityController<PlayerActivity>, atMs: Long): ExoPlayer {
        val p = playerView(c).player as ExoPlayer
        waitFor("ready to jump", p) { p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
        settleAt(p, atMs)
        assertFalse(p.playWhenReady)
        return p
    }

    /** Waits for [p] to land at [ms] and be ready there. */
    private fun settleAt(p: ExoPlayer, ms: Long) {
        waitFor("at $ms ms", p) { p.playbackState == Player.STATE_READY && kotlin.math.abs(p.currentPosition - ms) <= 20 }
        idle(50)
    }

    private fun waitFor(what: String, p: ExoPlayer? = null, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) {
                "Never $what" + (p?.let {
                    " (state ${it.playbackState}, error ${it.playerError?.errorCodeName}, at ${it.currentPosition} of ${it.duration}, " +
                        "seekable ${it.isCurrentMediaItemSeekable}, item ${it.currentMediaItem?.mediaId})"
                } ?: "")
            }
            idle(20)
        }
    }

    private fun idle(ms: Long) {
        val until = System.currentTimeMillis() + ms
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        } while (System.currentTimeMillis() < until)
    }

    /** A minute of silence as a WAV file: something Media3 opens and jumps in with no decoder at all. */
    private fun wav(file: File): File {
        if (file.exists()) return file
        val rate = 8_000
        val data = ByteArray(rate * 2 * 60)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        file.writeBytes(header + data)
        return file
    }
}
