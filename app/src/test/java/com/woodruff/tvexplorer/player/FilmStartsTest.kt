package com.woodruff.tvexplorer.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveApi
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * The start and end of each Drive film kept on the TV (FilmStarts): a resume asks Drive for one
 * thing instead of three, only those bytes are kept, a film replaced in Drive is never started from
 * its old index, and nothing that goes wrong with the TV's copy stops a film. Drive is a stand-in
 * serving a made-up film the way Drive answers range requests; the cache is Media3's own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FilmStartsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var folder: File
    private lateinit var cache: SimpleCache
    /** Small windows for small made-up films: the first and last 64 KB of films of 1 MB or more. */
    private val windows = StartWindows(headBytes = 64 * KB, tailBytes = 64 * KB, minFileBytes = 1 * MB)
    private val uri = Uri.parse(DriveApi.mediaUrl("abc123"))
    private val key = "drive:abc123"

    @Before fun openCache() {
        folder = tmp.newFolder("starts")
        cache = SimpleCache(folder, LeastRecentlyUsedCacheEvictor(8 * MB), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))
    }

    @After fun releaseCache() {
        cache.release()
    }

    @Test fun aResumeAsksDriveOnlyForTheSpotOnceTheFilmsStartIsKept() {
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film)
        // The first time: the header, the index near the end, then the spot, one request each.
        assertEquals(slices(film), resume(FilmStarts.cachedSource(cache, drive, windows), film))
        assertEquals(listOf(0L, film.size - CUES, RESUME), drive.opens.map { it.first })

        // The next time (a new player): the same bytes, and only the spot comes from Drive.
        drive.opens.clear()
        assertEquals(slices(film), resume(FilmStarts.cachedSource(cache, drive, windows), film))
        assertEquals(listOf(RESUME), drive.opens.map { it.first })
    }

    @Test fun onlyTheFirstAndLastBytesAreKept() {
        val film = film(2 * MB.toInt())
        val source = FilmStarts.cachedSource(cache, FakeDrive(film), windows)
        // The whole film watched from the start.
        assertArrayEquals(film, read(source, 0, -1))
        assertEquals(128 * KB, cache.cacheSpace)
        val spans = cache.getCachedSpans(key).map { it.position until it.position + it.length }
        assertEquals(listOf(0L until 64 * KB, (film.size - 64 * KB) until film.size.toLong()), mergeRanges(spans))
    }

    @Test fun aFilmReplacedInDriveIsNeverStartedFromItsOldIndex() {
        val old = film(2 * MB.toInt(), seed = 1)
        val drive = FakeDrive(old)
        resume(FilmStarts.cachedSource(cache, drive, windows), old)

        // A new version uploaded under the same file: another size, other bytes.
        val new = film(2 * MB.toInt() + 4096, seed = 2)
        drive.film = new
        // The kept header and index are read, then Drive's first answer gives the new size away.
        val again = FilmStarts.cachedSource(cache, drive, windows)
        read(again, 0, HEADER)
        read(again, old.size - CUES, -1)
        assertThrows(StaleStartException::class.java) { read(again, RESUME, 200 * KB.toInt()) }
        // Thrown away, and its old size with it (the record goes once nothing of the film is kept).
        assertTrue(cache.getCachedSpans(key).isEmpty())
        assertTrue(ContentMetadata.getContentLength(cache.getContentMetadata(key)) in setOf(C.LENGTH_UNSET.toLong(), new.size.toLong()))

        // Started again: all of it from Drive, and all of it the new film.
        drive.opens.clear()
        assertEquals(slices(new), resume(FilmStarts.cachedSource(cache, drive, windows), new))
        assertEquals(3, drive.opens.size)
    }

    @Test fun aStaleStartStopsTheFilmAtOnceInsteadOfBeingTriedAgain() {
        val policy = FilmStartsErrorPolicy()
        fun info(e: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(1, DataSpec(uri), SystemClock.elapsedRealtime()), MediaLoadData(C.DATA_TYPE_MEDIA), e, 1,
        )
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(StaleStartException())))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(IOException("wrapped", StaleStartException()))))
        // Anything else as Media3 has it: the first retry at once.
        assertEquals(0L, policy.getRetryDelayMsFor(info(IOException("connection reset"))))
        assertTrue(FilmStarts.isStale(RuntimeException(StaleStartException())))
        assertFalse(FilmStarts.isStale(IOException("connection reset")))
    }

    @Test fun aFilmSmallEnoughToBeKeptWholeIsNotKeptAtAll() {
        val clip = film(512 * KB.toInt())
        val drive = FakeDrive(clip)
        resume(FilmStarts.cachedSource(cache, drive, windows), clip, resumeAt = 300 * KB)
        assertEquals(0L, cache.cacheSpace)
    }

    @Test fun whenDriveDoesntSayTheSizeNothingIsKept() {
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film, saysSize = false)
        assertEquals(slices(film), resume(FilmStarts.cachedSource(cache, drive, windows), film))
        assertEquals(0L, cache.cacheSpace)
        drive.opens.clear()
        assertEquals(slices(film), resume(FilmStarts.cachedSource(cache, drive, windows), film))
        assertEquals(3, drive.opens.size)
    }

    @Test fun whenDriveStopsSayingTheSizeKeptStartsAreThrownAwayAndNoLongerUsed() {
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film)
        resume(FilmStarts.cachedSource(cache, drive, windows), film)
        drive.saysSize = false
        var uncheckable = 0
        val again = FilmStarts.cachedSource(cache, drive, windows, onUncheckable = { uncheckable++ })
        read(again, 0, HEADER)
        read(again, film.size - CUES, -1)
        assertThrows(StaleStartException::class.java) { read(again, RESUME, 200 * KB.toInt()) }
        assertEquals(1, uncheckable)
        assertTrue(cache.getCachedSpans(key).isEmpty())
    }

    @Test fun aCopyThatCantBeWrittenNeverStopsTheFilm() {
        // Opened, as Woodruff's is long before a film starts: SimpleCache reads its folder on a thread of
        // its own, and any question waits for that (taking the folder away mid-read made this test flaky).
        cache.cacheSpace
        // The cache's folder taken away and a file put in its place: nothing can be written there.
        folder.deleteRecursively()
        folder.writeText("not a folder")
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film)
        assertEquals(slices(film), resume(FilmStarts.cachedSource(cache, drive, windows), film))
        assertEquals(0L, cache.cacheSpace)
    }

    @Test fun aSinkThatFailsPartWayIsLetGoQuietly() {
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film)
        // Prime the size record, as Drive's answer does before anything is written.
        resume(FilmStarts.cachedSource(cache, drive, windows), film)
        val failing = object : DataSink {
            var opened = 0
            override fun open(dataSpec: DataSpec) { opened++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) = throw IOException("No space left on device")
            override fun close() = Unit
        }
        val sink = WindowSink(cache, windows, failing)
        sink.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(0).setLength(film.size.toLong()).build())
        sink.write(film, 0, 4096) // no throw
        sink.write(film, 4096, 4096) // stopped keeping it: not opened again
        sink.close()
        assertEquals(1, failing.opened)
    }

    @Test fun linksFromThePhoneNeverTouchTheCopy() {
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film)
        val source = DriveStartsDataSource(DataSource.Factory { drive }, windows) { cache }
        val link = Uri.parse("https://example.com/film.mp4")
        assertArrayEquals(film.copyOfRange(0, HEADER), read(source, 0, HEADER, link))
        assertTrue(cache.keys.isEmpty())
        // A Drive film through the same source is kept.
        read(source, 0, HEADER)
        assertEquals(setOf(key), cache.keys)
    }

    @Test fun aStartReadFromTheCopyIsNoted() {
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film)
        val since = SystemClock.elapsedRealtime()
        resume(DriveStartsDataSource(DataSource.Factory { drive }, windows) { cache }, film)
        assertFalse(FilmStarts.readFromCopy(key, since))
        resume(DriveStartsDataSource(DataSource.Factory { drive }, windows) { cache }, film)
        assertTrue(FilmStarts.readFromCopy(key, since))
        assertFalse(FilmStarts.readFromCopy(key, SystemClock.elapsedRealtime() + 1))
        FilmStarts.forget(key)
        assertFalse(FilmStarts.readFromCopy(key))
    }

    @Test fun withNoCacheTheFilmComesStraightFromDrive() {
        val film = film(2 * MB.toInt())
        val drive = FakeDrive(film)
        assertEquals(slices(film), resume(DriveStartsDataSource(DataSource.Factory { drive }, windows) { null }, film))
        assertEquals(3, drive.opens.size)
    }

    @Test fun onlyDriveFilmAddressesHaveAKey() {
        assertEquals("drive:abc123", driveFileKey(Uri.parse(DriveApi.mediaUrl("abc123"))))
        assertEquals("drive:1-_aZ", driveFileKey(Uri.parse("https://www.googleapis.com/drive/v3/files/1-_aZ?alt=media")))
        assertNull(driveFileKey(Uri.parse("https://www.googleapis.com/drive/v3/files/abc123?fields=parents")))
        assertNull(driveFileKey(Uri.parse("https://www.googleapis.com/drive/v3/files?q=x&alt=media")))
        assertNull(driveFileKey(Uri.parse("http://www.googleapis.com/drive/v3/files/abc123?alt=media")))
        assertNull(driveFileKey(Uri.parse("https://example.com/drive/v3/files/abc123?alt=media")))
        assertNull(driveFileKey(Uri.parse("https://www.googleapis.com/youtube/v3/videos/abc?alt=media")))
        assertNull(driveFileKey(Uri.parse("file:///storage/emulated/0/Movies/film.mkv")))
    }

    @Test fun theWholeSizeComesFromDrivesAnswer() {
        val unset = C.LENGTH_UNSET.toLong()
        assertEquals(123_456L, wholeSize(mapOf("content-range" to listOf("bytes 1000-1999/123456")), 1000, 1000, 1000))
        assertEquals(123_456L, wholeSize(mapOf("Content-Range" to listOf("bytes 1000-123455/123456")), 1000, unset, 122_456))
        // A total Drive won't say.
        assertNull(wholeSize(mapOf("content-range" to listOf("bytes 0-99/*")), 0, 100, 100))
        // No range: the answer is the whole file.
        assertEquals(5_000L, wholeSize(mapOf("content-length" to listOf("5000")), 0, unset, 5_000))
        assertEquals(5_000L, wholeSize(emptyMap(), 0, unset, 5_000))
        assertEquals(6_000L, wholeSize(emptyMap(), 1_000, unset, 5_000))
        // Nothing to go on.
        assertNull(wholeSize(emptyMap(), 1_000, 4_000, 4_000))
        assertNull(wholeSize(emptyMap(), 0, unset, unset))
    }

    @Test fun theKeptWindowsAreTheFirstAndLastBytes() {
        val w = StartWindows()
        assertEquals(4L * 1024 * 1024, w.headBytes)
        assertEquals(listOf(0L until 4L * 1024 * 1024, (1_000_000_000L - 4L * 1024 * 1024) until 1_000_000_000L), w.of(1_000_000_000L))
        assertTrue(w.of(32L * 1024 * 1024 - 1).isEmpty())
        assertEquals(2, w.of(32L * 1024 * 1024).size)
        // Within the budget: the most a film can take is a small part of it.
        assertTrue(FilmStarts.MAX_BYTES >= 8 * (FilmStarts.HEAD_BYTES + FilmStarts.TAIL_BYTES))
    }

    // ---- The made-up film and how it's read ---------------------------------------------------

    /** Reads as a resume reads an MKV: its header, its index near the end, then the spot to carry on from. */
    private fun resume(source: DataSource, film: ByteArray, resumeAt: Long = RESUME): List<ByteArray> = listOf(
        read(source, 0, HEADER),
        read(source, film.size - CUES, -1),
        read(source, resumeAt, 200 * KB.toInt()),
    )

    private fun slices(film: ByteArray, resumeAt: Long = RESUME) = listOf(
        film.copyOfRange(0, HEADER),
        film.copyOfRange((film.size - CUES).toInt(), film.size),
        film.copyOfRange(resumeAt.toInt(), resumeAt.toInt() + 200 * KB.toInt()),
    ).map { it.toList() }

    /** [length] bytes from [position] (-1: to the end), opened the way the player's loader opens films. */
    private fun read(source: DataSource, position: Long, length: Int, at: Uri = uri): ByteArray {
        val spec = DataSpec.Builder().setUri(at).setPosition(position)
            .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
        val out = java.io.ByteArrayOutputStream()
        try {
            source.open(spec)
            val buffer = ByteArray(16 * 1024)
            while (length < 0 || out.size() < length) {
                val want = if (length < 0) buffer.size else minOf(buffer.size, length - out.size())
                val n = source.read(buffer, 0, want)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    private fun List<ByteArray>.equalsTo() = map { it.toList() }

    private fun assertEquals(expected: List<List<Byte>>, actual: List<ByteArray>) = assertEquals(expected, actual.equalsTo() as Any)

    private fun film(size: Int, seed: Int = 7): ByteArray = Random(seed).nextBytes(size)

    private fun mergeRanges(ranges: List<LongRange>): List<LongRange> = ranges.sortedBy { it.first }.fold(mutableListOf()) { acc, r ->
        val last = acc.lastOrNull()
        if (last != null && last.last + 1 >= r.first) acc[acc.lastIndex] = last.first..maxOf(last.last, r.last) else acc += r
        acc
    }

    /**
     * Google Drive serving [film] the way it answers the player: from the first byte with its length,
     * from anywhere else as a range, saying the whole size in Content-Range ([saysSize] false: an
     * answer that says nothing of it). Records each request's position and length.
     */
    private class FakeDrive(var film: ByteArray, var saysSize: Boolean = true) : DataSource {
        val opens = ArrayList<Pair<Long, Long>>()
        private var spec: DataSpec? = null
        private var position = 0L
        private var end = 0L

        override fun open(dataSpec: DataSpec): Long {
            opens += dataSpec.position to dataSpec.length
            spec = dataSpec
            position = dataSpec.position
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) film.size.toLong() else minOf(film.size.toLong(), dataSpec.position + dataSpec.length)
            return if (!saysSize && dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - position
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), end - position).toInt()
            System.arraycopy(film, position.toInt(), buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = spec?.uri

        override fun getResponseHeaders(): Map<String, List<String>> {
            val s = spec ?: return emptyMap()
            if (!saysSize) return emptyMap()
            return if (s.position == 0L && s.length == C.LENGTH_UNSET.toLong()) mapOf("content-length" to listOf("${film.size}"))
            else mapOf("content-range" to listOf("bytes ${s.position}-${end - 1}/${film.size}"))
        }

        override fun close() {
            spec = null
        }

        override fun addTransferListener(transferListener: TransferListener) = Unit
    }

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024
        const val HEADER = 20 * 1024
        const val CUES = 30 * 1024L
        const val RESUME = 600 * 1024L
    }
}
