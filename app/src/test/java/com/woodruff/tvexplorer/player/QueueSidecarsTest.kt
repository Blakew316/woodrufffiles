package com.woodruff.tvexplorer.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * The subtitle files found for a queue after it has started ([giveWaitingItems]): the films waiting
 * get them, and the one playing plays on, untouched, from where it is (no restart, no jump, no new
 * item). A real ExoPlayer over small sound files.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueSidecarsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var player: ExoPlayer? = null

    @After fun release() {
        player?.release()
    }

    @Test fun theFilmsWaitingGetTheirSubtitlesAndTheOnePlayingPlaysOn() {
        val p = playing(listOf("a", "b", "c"), start = 1)
        val events = Events().also(p::addListener)
        val before = p.currentPosition

        giveWaitingItems(p, ::withSubtitles)
        idle(200)

        // The one playing: the same item, still playing, from where it was.
        assertEquals(1, p.currentMediaItemIndex)
        assertEquals("b", p.currentMediaItem?.mediaId)
        assertTrue(p.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty().isEmpty())
        assertNull(p.playerError)
        assertTrue(p.isPlaying)
        assertTrue("it went back to ${p.currentPosition} from $before", p.currentPosition >= before)
        assertEquals(emptyList<String>(), events.transitions)
        assertEquals(emptyList<Int>(), events.jumps)
        // The ones waiting, before and after it: their subtitle files are on them now.
        assertEquals(listOf(1, 0, 1), (0 until p.mediaItemCount).map { p.getMediaItemAt(it).localConfiguration!!.subtitleConfigurations.size })
        assertEquals(listOf("a", "b", "c"), (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId })
    }

    @Test fun nothingNewLeavesTheQueueAlone() {
        val p = playing(listOf("a", "b", "c"), start = 0)
        giveWaitingItems(p, ::withSubtitles)
        idle(100)
        val events = Events().also(p::addListener)

        // Asked again with what they already have: nothing is taken out or put back.
        giveWaitingItems(p) { item -> item }
        giveWaitingItems(p, ::withSubtitlesOnce)
        idle(100)

        assertEquals(0, events.playlistChanges)
        assertEquals(listOf(0, 1, 1), (0 until p.mediaItemCount).map { p.getMediaItemAt(it).localConfiguration!!.subtitleConfigurations.size })
    }

    @Test fun theLastFilmPlayingOnlyGivesThoseBeforeIt() {
        val p = playing(listOf("a", "b"), start = 1)
        giveWaitingItems(p, ::withSubtitles)
        idle(100)
        assertEquals(1, p.currentMediaItemIndex)
        assertTrue(p.isPlaying)
        assertEquals(listOf(1, 0), (0 until p.mediaItemCount).map { p.getMediaItemAt(it).localConfiguration!!.subtitleConfigurations.size })
    }

    // ---- The queue and what happens to it ----------------------------------------------------

    /** Every item but the one playing ("b" in these queues, or the first) with a subtitle file. */
    private fun withSubtitles(item: MediaItem): MediaItem =
        if (item.mediaId == player?.currentMediaItem?.mediaId) item
        else item.buildUpon().setSubtitleConfigurations(listOf(subtitle(item.mediaId))).build()

    /** As [withSubtitles], but an item that has its subtitle file already is given back as it is. */
    private fun withSubtitlesOnce(item: MediaItem): MediaItem =
        if (item.localConfiguration?.subtitleConfigurations.orEmpty().isNotEmpty()) item else withSubtitles(item)

    private fun subtitle(id: String) =
        Sidecars.configuration(Sidecar(Uri.fromFile(File(tmp.root, "$id.en.srt")).toString(), "application/x-subrip", "en"))

    /** A player built as PlayerActivity builds its sources, playing [ids] (sound files) from [start]. */
    private fun playing(ids: List<String>, start: Int): ExoPlayer {
        val sources = SidecarLoading.dataSourceFactory(DefaultDataSource.Factory(context))
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources).setSubtitleParserFactory(SidecarLoading.parserFactory()))
            .build()
        player = p
        var failed: PlaybackException? = null
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = error }
        })
        // The film's own clock: no sound to keep time by on the JVM.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        p.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).setUri(Uri.fromFile(wav(File(tmp.root, "$it.wav")))).build() }, start, 0)
        p.prepare()
        p.play()
        val deadline = System.currentTimeMillis() + 10_000
        while (!p.isPlaying && failed == null && System.currentTimeMillis() < deadline) idle(20)
        assertNull(failed?.let { "${it.errorCodeName}: ${it.cause}" })
        assertTrue(p.isPlaying)
        idle(300)
        return p
    }

    private fun idle(ms: Long) {
        val until = System.currentTimeMillis() + ms
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        } while (System.currentTimeMillis() < until)
    }

    /** What the player told its listeners: items it moved to, position jumps, and queue changes. */
    private class Events : Player.Listener {
        val transitions = ArrayList<String>()
        val jumps = ArrayList<Int>()
        var playlistChanges = 0

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitions += "${mediaItem?.mediaId}:$reason" }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { jumps += reason }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) playlistChanges++
        }
    }

    /** Twenty seconds of silence as a WAV file: something Media3 opens with no decoder at all. */
    private fun wav(file: File): File {
        val rate = 8_000
        val data = ByteArray(rate * 2 * 20)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        file.writeBytes(header + data)
        return file
    }
}
