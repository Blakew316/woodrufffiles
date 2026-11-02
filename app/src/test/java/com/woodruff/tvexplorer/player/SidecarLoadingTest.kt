package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
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
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Subtitle files read safely: one that can't be read or doesn't parse never stops the film, one that
 * fails for a moment is tried again (from where it got to, without the film waiting), and one saved
 * in Windows' Western encoding keeps its accents.
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

    /** The pauses between tries, as asked for (none is really waited). */
    private val pauses = ArrayList<Long>()
    private fun safe(inner: () -> DataSource) = SidecarLoading.dataSourceFactory({ inner() }) { pauses += it; true }.createDataSource()
    private val driveSrt = DataSpec(SidecarLoading.mark(Uri.parse("https://www.googleapis.com/drive/v3/files/srt?alt=media")))

    @Test fun aSidecarThatsGoneReadsAsEmptyAtOnce() {
        for (gone in listOf(refused(404), refused(403), refused(410), FileDataSource.FileDataSourceException(FileNotFoundException(), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))) {
            pauses.clear()
            val source = safe { Failing(onOpen = gone) }
            assertEquals(0L, source.open(driveSrt))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
            source.close()
            assertEquals("not tried again: $gone", emptyList<Long>(), pauses)
        }
    }

    @Test fun aSidecarThatKeepsFailingIsTriedThreeTimesMoreThenReadsAsEmpty() {
        val failing = Failing(onOpen = IOException("connection reset"))
        val source = safe { failing }
        // Opens at once (its length unknown) so the film doesn't wait: the tries come as it's read.
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(driveSrt))
        assertEquals(emptyList<Long>(), pauses)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        assertEquals(listOf(1_000L, 2_000L, 4_000L), pauses)
        assertEquals(4, failing.opens)
        // Given up: nothing more is asked.
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        assertEquals(4, failing.opens)
        source.close()
    }

    @Test fun aSidecarWhoseFirstTryFailsIsReadInFullOnTheNext() {
        for (blip in listOf<IOException>(
            HttpDataSource.HttpDataSourceException(SocketTimeoutException(), driveSrt, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, HttpDataSource.HttpDataSourceException.TYPE_OPEN),
            HttpDataSource.HttpDataSourceException(UnknownHostException("www.googleapis.com"), driveSrt, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN),
            refused(429), refused(503), refused(408),
        )) {
            pauses.clear()
            val flaky = Flaky(srt.toByteArray(), failOpens = 1, failure = blip)
            val source = safe { flaky }
            source.open(driveSrt)
            assertEquals("tried again after $blip", srt, String(readAll(source)))
            assertEquals(listOf(1_000L), pauses)
            source.close()
        }
    }

    @Test fun aSidecarCutShortCarriesOnFromWhereItGotTo() {
        val flaky = Flaky(srt.toByteArray(), failOpens = 0, cutAfter = 7)
        val source = safe { flaky }
        source.open(DataSpec(SidecarLoading.mark(Uri.parse("file:///storage/USB/a.srt"))))
        assertEquals(srt, String(readAll(source)))
        assertEquals(listOf(0L, 7L), flaky.positions)
        source.close()
    }

    @Test fun aSidecarCutShortThatCantBeReadAgainEndsWhereItWasCut() {
        val source = safe { Failing(onOpen = null) }
        source.open(DataSpec(SidecarLoading.mark(Uri.parse("file:///storage/USB/a.srt"))))
        val buffer = ByteArray(10)
        assertEquals(3, source.read(buffer, 0, 10))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 10))
        source.close()
    }

    @Test fun theFilmClosingStopsTheTries() {
        val failing = Failing(onOpen = IOException("connection reset"))
        // The loading thread interrupted while it waits: the film's loads were let go.
        val source = SidecarLoading.dataSourceFactory({ failing }) { false }.createDataSource()
        source.open(driveSrt)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        assertEquals(1, failing.opens)
        source.close()
    }

    @Test fun whatsWorthTryingAgain() {
        assertTrue(SidecarLoading.worthTryingAgain(IOException("reset")))
        assertTrue(SidecarLoading.worthTryingAgain(SocketTimeoutException()))
        assertTrue(SidecarLoading.worthTryingAgain(refused(500)))
        assertFalse(SidecarLoading.worthTryingAgain(refused(401)))
        assertFalse(SidecarLoading.worthTryingAgain(refused(404)))
        assertFalse(SidecarLoading.worthTryingAgain(FileNotFoundException()))
        // The film closing (the loading thread interrupted), not a timeout.
        assertFalse(SidecarLoading.worthTryingAgain(InterruptedIOException("interrupted")))
    }

    private fun refused(code: Int) = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), driveSrt, ByteArray(0))

    private fun readAll(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(5)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buffer, 0, n)
        }
    }

    /** [bytes] as a file, whose first [failOpens] opens fail with [failure], and whose reads are cut after [cutAfter] bytes once. */
    private class Flaky(private val bytes: ByteArray, private val failOpens: Int, private val failure: IOException = IOException("reset"), private val cutAfter: Int = -1) : DataSource {
        val positions = ArrayList<Long>()
        private var opens = 0
        private var at = 0
        private var cut = false
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            if (opens++ < failOpens) throw failure
            positions += dataSpec.position
            at = dataSpec.position.toInt()
            return (bytes.size - at).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!cut && cutAfter in 0..at) { cut = true; throw IOException("connection reset") }
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - at, if (!cut && cutAfter > at) cutAfter - at else Int.MAX_VALUE)
            System.arraycopy(bytes, at, buffer, offset, n)
            at += n
            return n
        }
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    @Test fun theFilmItselfStillFailsAsBefore() {
        val source = safe { Failing(onOpen = IOException("reset")) }
        val failed = runCatching { source.open(DataSpec(Uri.parse("https://www.googleapis.com/drive/v3/files/film?alt=media"))) }
        assertTrue(failed.exceptionOrNull() is IOException)
        source.close()
    }

    /** A source that fails as it opens ([onOpen]), or with none, after three bytes (and on every read after). */
    private class Failing(private val onOpen: IOException?) : DataSource {
        var opens = 0
        private var reads = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { opens++; if (onOpen != null) throw onOpen; return C.LENGTH_UNSET.toLong() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (reads++ == 0) 3 else throw IOException("reset")
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }
}
