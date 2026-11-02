package com.woodruff.tvexplorer.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
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
 * A subtitle file beside a film, attached as the player attaches it (marked, through the safe
 * sources and parsers), reaches Media3 as a subtitle track with its language and flags, after the
 * film's own; and one that's gone never stops the film. A real ExoPlayer over a small sound file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SidecarPlaybackTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var player: ExoPlayer? = null

    @After fun release() {
        player?.release()
    }

    @Test fun aSidecarBecomesASubtitleTrackWithItsLanguage() {
        val dir = tmp.newFolder("Films")
        val film = wav(File(dir, "Heat.wav"))
        val srt = File(dir, "Heat.es.sdh.srt").apply { writeBytes("1\n00:00:00,100 --> 00:00:00,900\nSeñor\n\n".toByteArray(charset("windows-1252"))) }
        // As its name reads beside a film (a sound file stands in for the film: nothing to decode).
        val name = Sidecars.pairNames(listOf("Heat.mkv"), listOf(srt.name), ownFolder = false).single().single().name
        val sidecars = listOf(Sidecar(Uri.fromFile(srt).toString(), "application/x-subrip", name.language, name.forced, name.sdh))

        val p = prepared(film, sidecars)

        assertNull(p.playerError)
        val text = SubtitleTracks.of(p.currentTracks)
        assertEquals(1, text.size)
        assertEquals("es", text.single().track.language)
        assertTrue(text.single().track.sdh)
        assertEquals(listOf("Spanish (SDH)"), SubtitleTracks.labels(text.map { it.track }, java.util.Locale.US))
    }

    @Test fun aWindowsEncodedSidecarShowsItsAccents() {
        val dir = tmp.newFolder("Films")
        val film = wav(File(dir, "Heat.wav"))
        val srt = File(dir, "Heat.es.srt").apply { writeBytes("1\n00:00:00,100 --> 00:00:00,900\nSeñor, ¿qué tal?\n\n".toByteArray(charset("windows-1252"))) }
        val p = prepared(film, listOf(Sidecar(Uri.fromFile(srt).toString(), "application/x-subrip", "es")))
        val shown = ArrayList<String>()
        p.addListener(object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { cueGroup.cues.forEach { shown += it.text.toString() } }
        })
        // Its subtitles on, and the film's own clock (no sound to keep time by on the JVM).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setPreferredTextLanguage("es")
            .build()
        p.play()
        val deadline = System.currentTimeMillis() + 10_000
        while (shown.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertEquals("Señor, ¿qué tal?", shown.firstOrNull())
    }

    @Test fun aSidecarThatsGoneLeavesTheFilmPlaying() {
        val dir = tmp.newFolder("Films")
        val film = wav(File(dir, "Heat.wav"))
        val gone = Sidecar(Uri.fromFile(File(dir, "Heat.en.srt")).toString(), "application/x-subrip", "en")

        val p = prepared(film, listOf(gone))

        // No "Can't play": the film is ready, with an empty English track where the file was.
        assertNull(p.playerError)
        assertEquals(Player.STATE_READY, p.playbackState)
    }

    @Test fun aSidecarWhoseFirstRequestFailsStillShowsItsSubtitles() {
        val dir = tmp.newFolder("Films")
        // The cue lasts most of the film: it shows however late its file is read in.
        val film = wav(File(dir, "Heat.wav"), seconds = 10)
        val srt = File(dir, "Heat.es.srt").apply { writeBytes("1\n00:00:00,100 --> 00:00:09,500\nSeñor\n\n".toByteArray()) }
        // A moment's network trouble as the film starts: the subtitle file's first request fails.
        val sources = SidecarLoading.dataSourceFactory(FirstSubtitleRequestFails(DefaultDataSource.Factory(context))) { pauseFor(it / 100) }
        // Its subtitles on from the start: switched on only once it's ready, Media3 can trip over its own
        // cancelled first load of them (a race of its own, not this one's).
        val p = prepared(film, listOf(Sidecar(Uri.fromFile(srt).toString(), "application/x-subrip", "es")), sources, subtitles = "es")
        val shown = ArrayList<String>()
        p.addListener(object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { cueGroup.cues.forEach { shown += it.text.toString() } }
        })
        // The film's own clock (no sound to keep time by on the JVM).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        p.play()
        val deadline = System.currentTimeMillis() + 10_000
        while (shown.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        // Tried again, and read in full: not an empty track for the whole film.
        assertEquals("Señor", shown.firstOrNull())
    }

    /** A pause between tries, given up when the loading thread is interrupted (the film's loads let go), as the player's own is. */
    private fun pauseFor(ms: Long): Boolean = try { Thread.sleep(ms); true } catch (e: InterruptedException) { Thread.currentThread().interrupt(); false }

    /** [upstream], except that the first request for each subtitle file fails as a dropped connection does. */
    private class FirstSubtitleRequestFails(private val upstream: DataSource.Factory) : DataSource.Factory {
        private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
        override fun createDataSource(): DataSource = object : DataSource {
            private val inner = upstream.createDataSource()
            override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)
            override fun open(dataSpec: DataSpec): Long {
                val path = dataSpec.uri.path.orEmpty()
                if (path.endsWith(".srt") && failed.add(path)) throw java.io.IOException("connection reset")
                return inner.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
            override fun getUri(): Uri? = inner.uri
            override fun close() = inner.close()
        }
    }

    /**
     * [film] in a player built as PlayerActivity builds its sources ([sources]), with [sidecars] attached
     * and the [subtitles] language chosen from the start (none: Media3's own choice), once it's ready.
     */
    private fun prepared(
        film: File, sidecars: List<Sidecar>,
        sources: DataSource.Factory = SidecarLoading.dataSourceFactory(DefaultDataSource.Factory(context)), subtitles: String? = null,
    ): ExoPlayer {
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources).setSubtitleParserFactory(SidecarLoading.parserFactory()))
            .build()
        player = p
        var failed: PlaybackException? = null
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = error }
        })
        p.setMediaItem(
            MediaItem.Builder().setUri(Uri.fromFile(film)).setSubtitleConfigurations(sidecars.map(Sidecars::configuration)).build(),
        )
        if (subtitles != null) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredTextLanguage(subtitles).build()
        p.prepare()
        val deadline = System.currentTimeMillis() + 10_000
        while (p.playbackState != Player.STATE_READY && failed == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertNull(failed?.let { "${it.errorCodeName}: ${it.cause}" })
        assertEquals(Player.STATE_READY, p.playbackState)
        return p
    }

    /** [seconds] of silence as a WAV file: something Media3 opens with no decoder at all. */
    private fun wav(file: File, seconds: Int = 1): File {
        val rate = 8_000
        val data = ByteArray(rate * 2 * seconds)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        file.writeBytes(header + data)
        return file
    }
}
