package com.woodruff.tvexplorer.player

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import org.junit.After
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

/**
 * "Play next" in the player's options (the … button, or the remote's menu key): part-way through an
 * episode it's the remote's Next, and the episode isn't marked watched (at minute 5 it put a ✓ on it
 * on every TV and took it out of Continue watching); in its last 40 s or 5 %, the TV's own Next
 * Episode, which counts it as watched. A real PlayerActivity over sound files standing in for episodes,
 * opened paused as an update puts a film back, so positions hold still on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayNextMenuTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val opened = ArrayList<ActivityController<PlayerActivity>>()

    @Before fun nothingPlaysYet() { NowPlaying.ownPlayer = null }

    @After fun closeThePlayers() {
        opened.asReversed().forEach { it.close() }
        idle(50)
        NowPlaying.ownPlayer = null
    }

    @Test fun partWayItIsTheRemotesNextAndNotWatched() {
        val first = wav(File(tmp.root, "Backyard Science S01E01.wav"))
        val c = open(first, wav(File(tmp.root, "Backyard Science S01E02.wav")), at = 60_000)
        val p = ready(c, 60_000)
        playNextFromTheMenu(c, p)
        waitFor("on the next episode", p) { p.currentMediaItemIndex == 1 }
        p.pause()
        assertFalse("not marked watched at a minute in", Watched.isWatched(app, first.path))
    }

    @Test fun inItsLastFortySecondsItIsNextEpisodeAndWatched() {
        val first = wav(File(tmp.root, "Backyard Science S01E03.wav"))
        val c = open(first, wav(File(tmp.root, "Backyard Science S01E04.wav")), at = 150_000)
        val p = ready(c, 150_000)
        playNextFromTheMenu(c, p)
        waitFor("on the next episode", p) { p.currentMediaItemIndex == 1 }
        p.pause()
        assertTrue("watched, in its credits", Watched.isWatched(app, first.path))
        assertTrue(RecentStore(app).all().none { it.key == first.path })
    }

    /**
     * The remote's menu key, Down to "Play next" (under Speed), and OK; the next one then plays by the
     * player's own clock (sound would race to its end on the JVM).
     */
    private fun playNextFromTheMenu(c: ActivityController<PlayerActivity>, p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
        for (code in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)) {
            c.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            c.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            idle(100)
        }
    }

    /** The player on screen, opened on [files] (sound files), paused at [at] as a film put back after an update. */
    private fun open(vararg files: File, at: Long): ActivityController<PlayerActivity> {
        val paths = files.map { it.path }
        val intent = PlayerActivity.intent(app, paths, files.map { it.nameWithoutExtension }, paths.first(), positionMs = at)
            .putExtra(com.woodruff.tvexplorer.update.Restore.EXTRA_PAUSED, true)
        return Robolectric.buildActivity(PlayerActivity::class.java, intent).setup().also(opened::add)
    }

    private fun ready(c: ActivityController<PlayerActivity>, atMs: Long): ExoPlayer {
        fun find(v: View): PlayerView? = v as? PlayerView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val p = find(c.get().window.decorView)!!.player as ExoPlayer
        waitFor("ready at $atMs", p) {
            p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && p.playbackState == Player.STATE_READY && kotlin.math.abs(p.currentPosition - atMs) <= 20
        }
        return p
    }

    private fun waitFor(what: String, p: ExoPlayer, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Never $what (state ${p.playbackState}, at ${p.currentPosition} of ${p.duration}, item ${p.currentMediaItemIndex})" }
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

    /** Three minutes of silence as a WAV file: something Media3 opens and jumps in with no decoder at all. */
    private fun wav(file: File): File {
        if (file.exists()) return file
        val rate = 8_000
        val data = ByteArray(rate * 2 * 180)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        file.writeBytes(header + data)
        return file
    }
}
