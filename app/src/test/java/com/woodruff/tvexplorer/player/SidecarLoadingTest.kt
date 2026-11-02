package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Subtitle files read safely: one that can't be read or doesn't parse never stops the film, and one
 * saved in Windows' Western encoding keeps its accents.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SidecarLoadingTest {
    private val srt = "1\n00:00:01,000 --> 00:00:03,000\nEspañol, ¿qué tal? Ça va très bien.\n\n"

    private fun cues(bytes: ByteArray, mime: String = MimeTypes.APPLICATION_SUBRIP): List<String> {
        val format = Format.Builder().setSampleMimeType(mime).build()
        val parser = SidecarLoading.parserFactory().create(format)
        val out = ArrayList<String>()
        parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) { c: CuesWithTiming -> c.cues.forEach { out += it.text.toString() } }
        return out
    }

    @Test fun utf8SubtitlesAreReadAsTheyAre() {
        assertEquals(listOf("Español, ¿qué tal? Ça va très bien."), cues(srt.toByteArray(Charsets.UTF_8)))
        assertNull(SidecarLoading.readable(srt.toByteArray(Charsets.UTF_8), 0, srt.toByteArray(Charsets.UTF_8).size))
    }

    @Test fun windowsEncodedSubtitlesKeepTheirAccents() {
        val western = srt.toByteArray(charset("windows-1252"))
        // Media3 alone would read the accents as U+FFFD.
        assertEquals(listOf("Español, ¿qué tal? Ça va très bien."), cues(western))
        assertArrayEquals(srt.toByteArray(Charsets.UTF_8), SidecarLoading.readable(western, 0, western.size))
    }

    @Test fun windowsQuotesAndDashesComeThroughToo() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n“Don’t” – he said…\n\n"
        assertEquals(listOf("“Don’t” – he said…"), cues(text.toByteArray(charset("windows-1252"))))
    }

    @Test fun byteOrderMarksAreLeftToMedia3() {
        val utf16 = srt.toByteArray(Charsets.UTF_16) // Java writes a byte-order mark first
        assertNull(SidecarLoading.readable(utf16, 0, utf16.size))
        assertEquals(listOf("Español, ¿qué tal? Ça va très bien."), cues(utf16))
    }

    @Test fun onlyPartOfTheBufferIsRead() {
        val western = srt.toByteArray(charset("windows-1252"))
        val padded = ByteArray(5) + western + ByteArray(7)
        assertArrayEquals(srt.toByteArray(Charsets.UTF_8), SidecarLoading.readable(padded, 5, western.size))
    }

    @Test fun aSubtitleThatDoesntParseShowsNothingRatherThanFailing() {
        // A .vtt without its "WEBVTT" header: Media3's parser throws.
        assertEquals(emptyList<String>(), cues("00:01.000 --> 00:02.000\nHello\n".toByteArray(), MimeTypes.TEXT_VTT))
        assertEquals(emptyList<String>(), cues(ByteArray(0)))
    }

    @Test fun sidecarsAreMarkedWhereNoRequestCarriesIt() {
        val drive = SidecarLoading.mark(Uri.parse("https://www.googleapis.com/drive/v3/files/abc?alt=media&supportsAllDrives=true"))
        assertTrue(SidecarLoading.isMarked(drive))
        assertEquals("/drive/v3/files/abc", drive.path)
        assertEquals("media", drive.getQueryParameter("alt"))
        assertFalse(SidecarLoading.isMarked(Uri.parse("https://www.googleapis.com/drive/v3/files/abc?alt=media")))
        val local = SidecarLoading.mark(Uri.fromFile(java.io.File("/storage/USB/Films/My #1 Film.en.srt")))
        assertTrue(SidecarLoading.isMarked(local))
        assertEquals("/storage/USB/Films/My #1 Film.en.srt", local.path)
        // Its kept start is a film's thing: a subtitle file is never one (FilmStarts).
        assertNull(driveFileKey(drive))
        assertEquals("drive:abc", driveFileKey(Uri.parse("https://www.googleapis.com/drive/v3/files/abc?alt=media&supportsAllDrives=true")))
    }

    @Test fun aSidecarThatCantBeOpenedReadsAsEmpty() {
        val source = SidecarLoading.dataSourceFactory { Failing(onOpen = true) }.createDataSource()
        val spec = DataSpec(SidecarLoading.mark(Uri.parse("https://www.googleapis.com/drive/v3/files/gone?alt=media")))
        assertEquals(0L, source.open(spec))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        source.close()
    }

    @Test fun aSidecarCutShortEndsWhereItWasCut() {
        val source = SidecarLoading.dataSourceFactory { Failing(onOpen = false) }.createDataSource()
        source.open(DataSpec(SidecarLoading.mark(Uri.parse("file:///storage/USB/a.srt"))))
        val buffer = ByteArray(10)
        assertEquals(3, source.read(buffer, 0, 10))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 10))
        source.close()
    }

    @Test fun theFilmItselfStillFailsAsBefore() {
        val source = SidecarLoading.dataSourceFactory { Failing(onOpen = true) }.createDataSource()
        val failed = runCatching { source.open(DataSpec(Uri.parse("https://www.googleapis.com/drive/v3/files/film?alt=media"))) }
        assertTrue(failed.exceptionOrNull() is IOException)
        source.close()
    }

    /** A source that fails as it opens, or after three bytes. */
    private class Failing(private val onOpen: Boolean) : DataSource {
        private var reads = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = if (onOpen) throw IOException("404") else C.LENGTH_UNSET.toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (reads++ == 0) 3 else throw IOException("reset")
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }
}
