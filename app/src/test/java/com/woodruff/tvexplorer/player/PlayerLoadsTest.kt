package com.woodruff.tvexplorer.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the player loads while nothing needs it: a paused film a few seconds ahead, not 50 (it came
 * back from Drive after every look at the doorbell, Home and back, the screensaver, each update), and
 * nothing of the next episode until it's the one playing (Media3 loaded it during the last 50 s of
 * each episode, and on while Up next counted down). Songs still load the next one ahead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerLoadsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var player: ExoPlayer? = null

    @After fun release() {
        player?.release()
    }

    @Test fun aPausedFilmLoadsAFewSecondsAheadAndPlayingTheUsualFifty() {
        val loads = PlayerLoads().apply { playing = "drive:e1" }
        val control = prepared(GatedLoadControl(playerLoadControl(512), loads))
        val queue = Queue("drive:e1")
        // Paused (back from the doorbell, an update, the screensaver): enough to show it and start at once.
        assertTrue(control.shouldContinueLoading(asked(queue, 0, bufferedMs = 0, playing = false)))
        assertTrue(control.shouldContinueLoading(asked(queue, 0, bufferedMs = 2_900, playing = false)))
        assertFalse(control.shouldContinueLoading(asked(queue, 0, bufferedMs = 3_000, playing = false)))
        assertFalse(control.shouldContinueLoading(asked(queue, 0, bufferedMs = 20_000, playing = false)))
        // Play pressed: on to the usual 50 s.
        assertTrue(control.shouldContinueLoading(asked(queue, 0, bufferedMs = 3_000, playing = true)))
        assertTrue(control.shouldContinueLoading(asked(queue, 0, bufferedMs = 49_000, playing = true)))
        assertFalse(control.shouldContinueLoading(asked(queue, 0, bufferedMs = 50_000, playing = true)))
        // More than Media3 needs to start again after a stall: Play starts at once.
        assertTrue(PAUSED_AHEAD_MS > RESTART_BUFFER_MS)
    }

    @Test fun theNextEpisodeLoadsNothingUntilItsTheOnePlaying() {
        val loads = PlayerLoads().apply { playing = "drive:e1" }
        val control = prepared(GatedLoadControl(playerLoadControl(512), loads))
        val queue = Queue("drive:e1", "drive:e2")
        // The last 50 s of e1 (loaded to its end), and Up next counting down.
        assertFalse(control.shouldContinueLoading(asked(queue, 1, bufferedMs = 30_000, playing = true)))
        assertFalse(control.shouldContinueLoading(asked(queue, 1, bufferedMs = 0, playing = false)))
        assertTrue(loads.holds("drive:e2"))
        // Chosen (Up next, Next Episode, the remote's Next): it's the one playing, and loads.
        loads.playing = "drive:e2"
        assertTrue(control.shouldContinueLoading(asked(queue, 1, bufferedMs = 0, playing = true)))
        assertFalse(loads.holds("drive:e2"))
    }

    @Test fun songsLoadTheNextOneAheadSoThereIsNoGap() {
        val loads = PlayerLoads().apply { playing = "/storage/USB/Music/01.flac"; holdNext = false }
        val control = prepared(GatedLoadControl(playerLoadControl(512), loads))
        val queue = Queue("/storage/USB/Music/01.flac", "/storage/USB/Music/02.flac")
        assertTrue(control.shouldContinueLoading(asked(queue, 1, bufferedMs = 10_000, playing = true)))
        assertFalse(loads.holds("/storage/USB/Music/02.flac"))
    }

    @Test fun nothingIsHeldBackBeforeThePlayerSaysWhatPlays() {
        val loads = PlayerLoads()
        assertFalse(loads.holds("drive:e2"))
        assertFalse(loads.holds(null))
        loads.playing = "drive:e1"
        assertFalse("an item it can't tell", loads.holds(null))
        // And a load control that can't tell which item a load is for holds nothing back.
        val control = prepared(GatedLoadControl(playerLoadControl(512), loads))
        val stranger = LoadControl.Parameters(
            PlayerId.UNSET, Queue("drive:e1"), MediaSource.MediaPeriodId(Any()), 0, 1_000_000, 1f, true, false, C.TIME_UNSET,
        )
        assertTrue(control.shouldContinueLoading(stranger))
    }

    @Test fun aHeldRequestWaitsUntilItsFilmPlaysAndOneLetGoAsksNothing() {
        val loads = PlayerLoads().apply { playing = "drive:e1" }
        val opened = CopyOnWriteArrayList<String>()
        val upstream = DataSource.Factory { Counting(opened) }
        var looks = 0
        // Up next counts down for five looks, then plays it.
        val held = HeldDriveSource(upstream.createDataSource(), loads) { looks++; if (looks == 5) loads.playing = "drive:e2"; true }
        held.open(DataSpec(Uri.parse("https://www.googleapis.com/drive/v3/files/e2?alt=media")))
        assertEquals(5, looks)
        assertEquals(listOf("e2"), opened)
        held.close()
        // The one playing, and anything that isn't a Drive film, are never held.
        val now = HeldDriveSource(upstream.createDataSource(), loads) { fail("held"); true }
        now.open(DataSpec(Uri.parse("https://www.googleapis.com/drive/v3/files/e2?alt=media")))
        now.close()
        now.open(DataSpec(Uri.parse("file:///storage/USB/Films/Other.mkv")))
        now.close()
        // Done on Up next: the player lets go of the load, and Google is asked for nothing.
        val letGo = HeldDriveSource(upstream.createDataSource(), loads) { false }
        loads.playing = "drive:e1"
        try {
            letGo.open(DataSpec(Uri.parse("https://www.googleapis.com/drive/v3/files/e2?alt=media")))
            fail("opened")
        } catch (e: InterruptedIOException) {
            // as the player's own loads end when it lets go of them
        }
        assertEquals(listOf("e2", "e2", "Other.mkv"), opened)
    }

    @Test fun aRealPlayerAsksDriveNothingOfTheNextEpisodeUntilItsChosen() {
        val loads = PlayerLoads()
        val asked = CopyOnWriteArrayList<String>()
        val drive = DataSource.Factory { Wav(asked) }
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(HeldDriveSource.factory(drive, loads)))
            .setLoadControl(GatedLoadControl(playerLoadControl(512), loads))
            .build()
        player = p
        var failed: PlaybackException? = null
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = error }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { loads.playing = mediaItem?.mediaId }
        })
        val episodes = listOf("e1", "e2").map { MediaItem.Builder().setMediaId("drive:$it").setUri(Uri.parse("https://www.googleapis.com/drive/v3/files/$it?alt=media")).build() }
        p.pauseAtEndOfMediaItems = true
        p.setMediaItems(episodes)
        loads.playing = p.currentMediaItem?.mediaId
        p.prepare()
        // The first one is loaded to its end (a second of sound): Media3 would open the next one now.
        until { p.playbackState == Player.STATE_READY && p.bufferedPosition >= p.duration }
        idle(Duration.ofMillis(800))
        assertNull(failed)
        assertEquals(listOf("e1"), asked.distinct())
        // Chosen: asked for at once, and it plays.
        p.seekToNextMediaItem()
        until { p.currentMediaItemIndex == 1 && p.playbackState == Player.STATE_READY }
        assertEquals(listOf("e1", "e2"), asked.distinct())
    }

    private fun prepared(control: LoadControl) = control.apply { onPrepared(PlayerId.UNSET) }

    /** What the player asks its load control about item [index] of [queue], [bufferedMs] loaded ahead, meant to play or not. */
    private fun asked(queue: Queue, index: Int, bufferedMs: Long, playing: Boolean) = LoadControl.Parameters(
        PlayerId.UNSET, queue, MediaSource.MediaPeriodId(queue.getUidOfPeriod(index)), /* playbackPositionUs = */ 0,
        bufferedMs * 1_000, /* playbackSpeed = */ 1f, playing, /* rebuffering = */ false, C.TIME_UNSET,
    )

    /** A queue of one-period items with these media ids, as the player's timeline has them. */
    private class Queue(vararg ids: String) : Timeline() {
        private val items = ids.map { MediaItem.Builder().setMediaId(it).build() }
        override fun getWindowCount() = items.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(windowIndex, items[windowIndex], null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, 60_000_000, windowIndex, windowIndex, 0)
        override fun getPeriodCount() = items.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = period.set(periodIndex, periodIndex, periodIndex, 60_000_000, 0)
        override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int)?.takeIf { it in items.indices } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    /** Notes which Drive file (or file) each request was for; reads nothing. */
    private class Counting(private val opened: MutableList<String>) : DataSource {
        override fun open(dataSpec: DataSpec): Long { opened += dataSpec.uri.lastPathSegment.orEmpty(); return 0 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    /** Google Drive as the player meets it, every film a second of silence; notes the film each request is for. */
    private class Wav(private val asked: MutableList<String>) : DataSource {
        private var position = 0L
        private var end = 0L
        private var uri: Uri? = null
        override fun open(dataSpec: DataSpec): Long {
            asked += dataSpec.uri.lastPathSegment.orEmpty()
            uri = dataSpec.uri
            position = dataSpec.position
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) WAV.size.toLong() else minOf(WAV.size.toLong(), dataSpec.position + dataSpec.length)
            return end - position
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), end - position).toInt()
            System.arraycopy(WAV, position.toInt(), buffer, offset, n)
            position += n
            return n
        }
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = uri
        override fun close() { uri = null }
    }

    private fun until(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        if (!done()) fail("timed out: player ${player?.playbackState} ${player?.playerError?.errorCodeName}")
    }

    private fun idle(time: Duration) {
        repeat((time.toMillis() / 20).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
        }
    }

    private companion object {
        /** A second of silence as a WAV file: something Media3 opens with no decoder at all. */
        val WAV: ByteArray = run {
            val rate = 8_000
            val data = ByteArray(rate * 2)
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data.size)
            }.array()
            header + data
        }
    }
}
