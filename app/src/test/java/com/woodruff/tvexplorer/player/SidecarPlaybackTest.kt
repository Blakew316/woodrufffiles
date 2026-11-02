package com.woodruff.tvexplorer.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

    /** [film] in a player built as PlayerActivity builds its sources, with [sidecars] attached, once it's ready. */
    private fun prepared(film: File, sidecars: List<Sidecar>): ExoPlayer {
        val sources = SidecarLoading.dataSourceFactory(DefaultDataSource.Factory(context))
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

    /** A second of silence as a WAV file: something Media3 opens with no decoder at all. */
    private fun wav(file: File): File {
        val rate = 8_000
        val data = ByteArray(rate * 2)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        file.writeBytes(header + data)
        return file
    }
}
