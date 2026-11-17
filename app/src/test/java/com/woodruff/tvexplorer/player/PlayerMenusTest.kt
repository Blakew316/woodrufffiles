package com.woodruff.tvexplorer.player

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.ui.motion.AppMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.UUID

/**
 * The player's controls, menus and cards with the remote's keys: the controls come back with the scrub
 * bar focused however soon after they went, the sleep timer survives the intro's rows and a look at the
 * door, the doorbell's card takes OK from a menu left open, a menu goes with its episode, and the
 * Subtitles button says the film is still opening. A real PlayerActivity over sound files standing in
 * for films.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerMenusTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val opened = ArrayList<ActivityController<PlayerActivity>>()
    private val screen = Notices.screenOn

    @Before fun nothingPlaysYet() { NowPlaying.ownPlayer = null }

    @After fun closeThePlayers() {
        opened.asReversed().forEach { if (!it.get().isDestroyed) it.close() }
        idle(50)
        NowPlaying.ownPlayer = null
        Notices.screenOn = screen
        AppVisibility.foreground = false
        Notices.dismissLatest()
    }

    @Test fun theControlsComeBackWithTheScrubBarFocusedHoweverSoonAfterTheyWent() {
        val c = open(wav("Midnight Harbor (2024).wav"))
        val p = ready(c)
        // Playing by the player's own clock (sound would race to its end on the JVM).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        idle(100)
        p.play()
        waitFor("playing", p) { p.isPlaying }
        val view = playerView(c)
        val bar = c.get().findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        // Up while it was paused: they go by themselves once it plays.
        idleFor(Duration.ofMillis(5_400))
        assertFalse(view.isControllerFullyVisible)
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(view.isControllerFullyVisible)
        assertTrue(bar.isFocused)
        // They go after 5 s, in one step: nothing left up for 2 s more with the scrub bar unfocused.
        idleFor(Duration.ofMillis(5_400))
        assertFalse(view.isControllerFullyVisible)
        assertEquals(View.GONE, c.get().findViewById<View>(androidx.media3.ui.R.id.exo_controller).visibility)
        // A press a moment later: they're back with the scrub bar focused (Left / Right scrub, OK pauses).
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(view.isControllerFullyVisible)
        assertTrue("the scrub bar has focus", bar.isFocused)
    }

    @Test fun theIntrosRowsLeaveTheSleepTimerAlone() {
        val c = open(wav("Midnight Harbor (2024).wav"))
        ready(c)
        // Options: Speed, the five sleep rows, then the intro's.
        options(c, row = 1) // Sleep in 30 minutes
        options(c, row = 6) // Intro ends here (…): use for this show
        idleFor(Duration.ofMinutes(31))
        assertTrue("asleep after 30 minutes", c.get().isFinishing)
    }

    @Test fun theSleepTimerOutlastsALookAtTheDoorOrHomeAndBack() {
        val c = open(wav("Midnight Harbor (2024).wav"))
        ready(c)
        options(c, row = 1) // Sleep in 30 minutes
        idleFor(Duration.ofMinutes(5))
        // OK on the doorbell's card, then Back to the film (or Home and back): the player stops and starts.
        c.userLeaving()
        c.pause().stop()
        idleFor(Duration.ofMinutes(2))
        c.restart().resume()
        idleFor(Duration.ofMinutes(22))
        assertFalse(c.get().isFinishing)
        idleFor(Duration.ofMinutes(2))
        assertTrue("asleep 30 minutes after it was set", c.get().isFinishing)
    }

    @Test fun aSleepTimerThatRanOutWhileAwaySleepsAMinuteAfterComingBack() {
        val c = open(wav("Midnight Harbor (2024).wav"))
        ready(c)
        options(c, row = 1)
        c.userLeaving()
        c.pause().stop()
        idleFor(Duration.ofMinutes(35))
        c.restart().resume()
        idle(100)
        assertFalse(c.get().isFinishing)
        assertTrue(shows(c, "Sleeping in a minute"))
        idleFor(Duration.ofSeconds(61))
        assertTrue(c.get().isFinishing)
    }

    @Test fun theDoorbellsCardTakesOkFromAMenuLeftOpen() {
        val c = open(wav("Midnight Harbor (2024).wav"))
        ready(c)
        press(c, KeyEvent.KEYCODE_MENU)
        assertTrue(menuShown(c, "Options"))
        // The bell rings with the options open.
        Notices.screenOn = { true }
        AppVisibility.foreground = true
        com.woodruff.tvexplorer.nest.Doorbell.ring(app, "enterprises/p/devices/${UUID.randomUUID()}", "Front Door", relay = false, session = UUID.randomUUID().toString())
        waitFor("the doorbell's card") { shows(c, "Someone’s at the door") }
        idle(600)
        assertFalse("the menu went", menuShown(c, "Options"))
        // And the menu key doesn't open the options over the card (its OK is the card's).
        press(c, KeyEvent.KEYCODE_MENU)
        idle(300)
        assertFalse(menuShown(c, "Options"))
    }

    @Test fun aMenuGoesWithItsEpisode() {
        val c = open(wav("Backyard Science S01E01.wav"), wav("Backyard Science S01E02.wav"))
        val p = ready(c)
        press(c, KeyEvent.KEYCODE_MENU)
        assertTrue(menuShown(c, "Options"))
        // The remote's ⏭: the next episode, and the menu about the last one goes.
        press(c, KeyEvent.KEYCODE_MEDIA_NEXT)
        waitFor("on the next episode", p) { p.currentMediaItemIndex == 1 }
        idle(600)
        assertFalse(menuShown(c, "Options"))
    }

    @Test fun theSubtitlesButtonSaysTheFilmIsStillOpening() {
        // A film whose first bytes take a while (its server holds its answer): it's still opening.
        val answer = java.util.concurrent.CountDownLatch(1)
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                answer.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        }
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        // The loading ring stands still (Reduce motion): turning, it would keep the JVM's main thread busy for ever.
        AppMotion.reduced = true
        try {
            val url = "http://127.0.0.1:${server.listeningPort}/Slow%20Start.mkv"
            val intent = PlayerActivity.intent(app, listOf(url), listOf("Slow Start (2024)"), url)
            val c = Robolectric.buildActivity(PlayerActivity::class.java, intent).setup().also(opened::add)
            idle(300)
            val p = player(c)
            assertTrue(p.currentTracks.isEmpty)
            assertTrue(p.playerError == null)
            c.get().findViewById<View>(com.woodruff.tvexplorer.R.id.player_subtitles).performClick()
            idle(100)
            assertTrue("it says so rather than doing nothing", shows(c, "Still opening…"))
        } finally {
            answer.countDown()
            idle(200)
            opened.forEach { if (!it.get().isDestroyed) it.close() }
            opened.clear()
            server.stop()
            AppMotion.reduced = false
        }
    }

    // ---- A player on screen ----------------------------------------------------------------------

    private fun open(vararg files: File, paused: Boolean = true, at: Long = 20_000): ActivityController<PlayerActivity> {
        val paths = files.map { it.path }
        val intent = PlayerActivity.intent(app, paths, files.map { it.nameWithoutExtension }, paths.first(), positionMs = at)
            .apply { if (paused) putExtra(com.woodruff.tvexplorer.update.Restore.EXTRA_PAUSED, true) }
        return Robolectric.buildActivity(PlayerActivity::class.java, intent).setup().also(opened::add)
    }

    private fun playerView(c: ActivityController<PlayerActivity>): PlayerView {
        fun find(v: View): PlayerView? = v as? PlayerView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(c.get().window.decorView)!!
    }

    private fun player(c: ActivityController<PlayerActivity>): ExoPlayer = playerView(c).player as ExoPlayer

    private fun ready(c: ActivityController<PlayerActivity>): ExoPlayer {
        val p = player(c)
        waitFor("ready", p) { p.playbackState == Player.STATE_READY && p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
        return p
    }

    /** The options (the remote's menu key), Down to [row], and OK. */
    private fun options(c: ActivityController<PlayerActivity>, row: Int) {
        press(c, KeyEvent.KEYCODE_MENU)
        repeat(row) { press(c, KeyEvent.KEYCODE_DPAD_DOWN) }
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        idle(300)
    }

    private fun press(c: ActivityController<PlayerActivity>, code: Int) {
        c.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        c.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        idle(100)
    }

    /** Whether a menu called [title] is up (its panel: the title's parent). */
    private fun menuShown(c: ActivityController<PlayerActivity>, title: String): Boolean =
        texts(c.get().window.decorView).any { it.text.toString() == title && (it.parent as View).visibility == View.VISIBLE }

    /** Whether words containing [words] are on screen (a message, a card). */
    private fun shows(c: ActivityController<PlayerActivity>, words: String): Boolean =
        texts(c.get().window.decorView).any { it.text.toString().contains(words) && it.isShown }

    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun wav(name: String): File {
        val file = File(tmp.root, name)
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

    private fun waitFor(what: String, p: ExoPlayer? = null, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Never $what" + (p?.let { " (state ${it.playbackState}, at ${it.currentPosition}, item ${it.currentMediaItemIndex})" } ?: "") }
            idle(20)
        }
    }

    /** Real time, a little at a time (the player's own threads run meanwhile). */
    private fun idle(ms: Long) {
        val until = System.currentTimeMillis() + ms
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        } while (System.currentTimeMillis() < until)
    }

    /** The main thread's clock on by [d] at once (timers, the controls' timeout). */
    private fun idleFor(d: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(d)
        idle(50)
    }
}
