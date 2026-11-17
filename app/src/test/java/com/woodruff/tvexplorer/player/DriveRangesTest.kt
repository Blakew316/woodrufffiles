package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.random.Random

/**
 * Google Drive is asked for its films in bounded pieces (BoundedDriveSource), never "from here to
 * the end", and the player can't tell: the same bytes, the same lengths, the same errors. Drive is
 * a stand-in that answers ranges the way Drive and OkHttpDataSource do, and records every request.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DriveRangesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Small pieces for a small made-up film: 8 KB at a new spot, doubling to 64 KB. */
    private val pieces = DrivePieces(first = 8 * KB, most = 64 * KB)
    private val uri = Uri.parse(DriveApi.mediaUrl("abc123"))
    private val film = Random(7).nextBytes(MB.toInt())

    @Test fun everyRequestToDriveIsBoundedAndPiecesDoubleUpToTheLargest() {
        val drive = FakeDrive(film)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        assertArrayEquals(film, read(source, 0, -1))
        assertTrue(drive.specs.all { it.length != UNSET })
        assertEquals(listOf(8 * KB, 16 * KB, 32 * KB, 64 * KB, 64 * KB), drive.specs.take(5).map { it.length })
        assertTrue(drive.specs.all { it.length <= 64 * KB })
        // One after another, with nothing asked twice and nothing past the file's end.
        assertEquals(drive.specs.map { it.position }, drive.specs.runningFold(0L) { at, s -> at + s.length }.dropLast(1))
        assertEquals(film.size.toLong(), drive.specs.last().position + drive.specs.last().length)
    }

    @Test fun theBytesAreWholeAndInOrderAcrossPieceEdges() {
        // Reads of an odd size, so they straddle every edge between pieces.
        for (readSize in listOf(1, 5_000, 8 * 1024, 70_000)) {
            val source = BoundedDriveSource(FakeDrive(film), pieces, NoCounts)
            assertArrayEquals("reads of $readSize", film.copyOfRange(123_457, film.size), read(source, 123_457, -1, readSize))
        }
    }

    @Test fun openSaysWhatDriveAloneWouldHave() {
        val drive = FakeDrive(film)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        // No length asked: the rest of the file, from the total in Drive's first answer.
        assertEquals(film.size - 300_000L, source.open(spec(300_000)))
        source.close()
        // A length asked: that length, as OkHttpDataSource says.
        assertEquals(200_000L, source.open(spec(10_000, 200_000)))
        source.close()
        // From the first byte, as the player opens a film: all of it.
        assertEquals(film.size.toLong(), source.open(spec(0)))
        source.close()
        // An answer that doesn't say the file's size: not known.
        drive.saysSize = false
        assertEquals(UNSET, source.open(spec(300_000)))
        source.close()
    }

    @Test fun aLengthAskedIsReadToItsEndAndNoFurther() {
        val drive = FakeDrive(film)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        assertArrayEquals(film.copyOfRange(50_000, 150_000), read(source, 50_000, 100_000, asked = 100_000))
        assertEquals(listOf(8 * KB, 16 * KB, 32 * KB, 100_000 - 56 * KB), drive.specs.map { it.length })
        assertEquals(150_000L, drive.specs.last().position + drive.specs.last().length)
    }

    @Test fun aSeekStartsSmallAgain() {
        val drive = FakeDrive(film)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        // Played for a while: the pieces have grown.
        read(source, 0, 200_000)
        assertEquals(64 * KB, drive.specs.last().length)
        drive.specs.clear()
        // A seek to the middle: a new read, starting with the smallest piece.
        assertArrayEquals(film.copyOfRange(600_000, 640_000), read(source, 600_000, 40_000))
        assertEquals(listOf(600_000L to 8 * KB, 608_192L to 16 * KB, 624_576L to 32 * KB), drive.specs.map { it.position to it.length })
    }

    @Test fun smallBoundedRequestsPassStraightThrough() {
        val drive = FakeDrive(film)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        val asked = spec(40_000, 8 * KB)
        assertEquals(8 * KB, source.open(asked))
        val out = ByteArray(8 * KB.toInt())
        var n = 0
        while (n < out.size) { val r = source.read(out, n, out.size - n); if (r == C.RESULT_END_OF_INPUT) break; n += r }
        source.close()
        assertArrayEquals(film.copyOfRange(40_000, 40_000 + 8 * KB.toInt()), out)
        assertEquals(1, drive.specs.size)
        assertSame(asked, drive.specs.single())
    }

    @Test fun anythingNotOnDrivePassesStraightThrough() {
        val drive = FakeDrive(film)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        val link = DataSpec.Builder().setUri(Uri.parse("https://example.com/film.mp4")).setPosition(1_000).build()
        source.open(link)
        source.close()
        assertSame(link, drive.specs.single())
        // Nor a sidecar of another host, nor a file on the TV.
        val file = DataSpec(Uri.parse("file:///storage/emulated/0/Movies/film.mkv"))
        source.open(file)
        source.close()
        assertSame(file, drive.specs.last())
    }

    @Test fun theFilesEndEndsTheRead() {
        val drive = FakeDrive(film)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        // Opened at its very end (Drive answers 416 with the size, which OkHttp takes as nothing to read).
        assertEquals(0L, source.open(spec(film.size.toLong())))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        source.close()
        // The last few bytes: one piece, cut to the file's end.
        assertArrayEquals(film.copyOfRange(film.size - 100, film.size), read(source, film.size - 100L, -1))
        assertEquals(film.size.toLong(), drive.specs.last().let { it.position + minOf(it.length, film.size - it.position) })
    }

    @Test fun whenDriveDoesntSayItsSizeTheReadEndsWhereItsAnswersDo() {
        val drive = FakeDrive(film, saysSize = false)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        assertArrayEquals(film.copyOfRange(700_000, film.size), read(source, 700_000, -1))
        assertTrue(drive.specs.all { it.length != UNSET })
    }

    @Test fun driveIgnoringTheRangeIsTakenAsItSendsIt() {
        val drive = FakeDrive(film, honoursRanges = false)
        val source = BoundedDriveSource(drive, pieces, NoCounts)
        assertArrayEquals(film.copyOfRange(0, 100_000), read(source, 0, 100_000))
        // The first piece, then the request as the player made it.
        assertEquals(listOf(8 * KB, UNSET), drive.specs.map { it.length })
    }

    @Test fun errorsComeUpAsTheyAre() {
        val drive = FakeDrive(film)
        val refusal = HttpDataSource.InvalidResponseCodeException(
            403, "Forbidden", null, emptyMap(), spec(0),
            """{"error":{"errors":[{"domain":"usageLimits","reason":"downloadQuotaExceeded","message":"The download quota for this file has been exceeded."}],"code":403}}""".toByteArray(),
        )
        drive.failAt = 3 to refusal
        val counts = Counts()
        val source = BoundedDriveSource(drive, pieces, counts)
        source.open(spec(0))
        val buffer = ByteArray(16 * 1024)
        val thrown = assertThrows(IOException::class.java) { while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit }
        source.close()
        // The very exception Drive's answer made: the player's words for Google's refusals read it as before.
        assertSame(refusal, thrown)
        assertEquals(PlaybackErrors.reasonOf(thrown), "downloadQuotaExceeded")
        assertEquals(listOf(Triple("drive:abc123", 403, "downloadQuotaExceeded")), counts.refusals)
    }

    @Test fun whatIsAskedAndReceivedIsCounted() {
        val drive = FakeDrive(film)
        val counts = Counts()
        val source = BoundedDriveSource(drive, pieces, counts)
        read(source, 1_000_000, -1) // the last 48,576 bytes: one piece, cut to the file's end
        read(source, 0, 30_000) // 8 + 16 + 32 KB asked, 30,000 read
        assertEquals(drive.specs.size, counts.requests)
        assertEquals(film.size - 1_000_000L + 56 * KB, counts.asked)
        assertEquals(film.size - 1_000_000L + 30_000, counts.received)
        assertTrue(counts.keys.all { it == "drive:abc123" })
        // A subtitle file beside the film counts for the day, not for a film.
        read(BoundedDriveSource(FakeDrive(film), pieces, counts), 0, 100, at = SidecarLoading.mark(uri))
        assertEquals(null, counts.keys.last())
    }

    @Test fun throughTheTvsCopyOfTheFilmsStartEveryRequestIsBoundedToo() {
        val cache = SimpleCache(tmp.newFolder("starts"), LeastRecentlyUsedCacheEvictor(8 * MB), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))
        try {
            val windows = StartWindows(headBytes = 64 * KB, tailBytes = 64 * KB, minFileBytes = 512 * KB)
            val drive = FakeDrive(film)
            fun resume(): List<ByteArray> {
                val source = FilmStarts.cachedSource(cache, BoundedDriveSource(drive, pieces, NoCounts), windows)
                return listOf(read(source, 0, 20_000, flags = PLAYER), read(source, film.size - 30_000L, -1, flags = PLAYER), read(source, 600_000, 200_000, flags = PLAYER))
            }
            val expected = listOf(film.copyOfRange(0, 20_000), film.copyOfRange(film.size - 30_000, film.size), film.copyOfRange(600_000, 800_000)).map { it.toList() }
            assertEquals(expected, resume().map { it.toList() })
            assertTrue(drive.specs.all { it.length != UNSET && it.length <= 64 * KB })
            // The next start: the header and the index from the TV, only the spot from Drive (small first).
            drive.specs.clear()
            assertEquals(expected, resume().map { it.toList() })
            assertEquals(600_000L to 8 * KB, drive.specs.first().let { it.position to it.length })
            assertTrue(drive.specs.all { it.position >= 600_000 && it.length != UNSET })
        } finally {
            cache.release()
        }
    }

    // ---- How it's read --------------------------------------------------------------------------

    private fun spec(position: Long, length: Long = UNSET, at: Uri = uri, flags: Int = 0) =
        DataSpec.Builder().setUri(at).setPosition(position).setLength(length).setFlags(flags).build()

    /** [length] bytes from [position] (-1: to the end), in reads of [readSize]; [asked]: the length the request names. */
    private fun read(source: DataSource, position: Long, length: Int, readSize: Int = 16 * 1024, asked: Long = UNSET, at: Uri = uri, flags: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        try {
            source.open(spec(position, asked, at, flags))
            val buffer = ByteArray(readSize)
            while (length < 0 || out.size() < length) {
                val want = if (length < 0) buffer.size else minOf(buffer.size, length - out.size())
                val n = source.read(buffer, 0, want)
                if (n == C.RESULT_END_OF_INPUT) break
                assertTrue("a read returned $n", n > 0)
                out.write(buffer, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    private object NoCounts : DriveAskSink {
        override fun requested(key: String?, bytes: Long) = Unit
        override fun received(key: String?, bytes: Long) = Unit
        override fun refused(key: String?, http: Int, reason: String?) = Unit
    }

    private class Counts : DriveAskSink {
        var requests = 0
        var asked = 0L
        var received = 0L
        val keys = ArrayList<String?>()
        val refusals = ArrayList<Triple<String?, Int, String?>>()
        override fun requested(key: String?, bytes: Long) { requests++; asked += bytes; keys += key }
        override fun received(key: String?, bytes: Long) { received += bytes }
        override fun refused(key: String?, http: Int, reason: String?) { refusals += Triple(key, http, reason) }
    }

    /**
     * Google Drive serving [film] as OkHttpDataSource sees it: a request with no length from the first
     * byte is the whole file (Content-Length); any other is a range (Content-Range with the file's
     * size, unless [saysSize] is false), whose length OkHttp reports as asked even past the file's end;
     * from the very end, nothing (Drive's 416 with the size). [honoursRanges] false: every answer is
     * the whole file, as a server that ignores ranges sends it. Records every request.
     */
    private class FakeDrive(val film: ByteArray, var saysSize: Boolean = true, val honoursRanges: Boolean = true) : DataSource {
        val specs = ArrayList<DataSpec>()
        /** The request (counted from 1) that fails, and how. */
        var failAt: Pair<Int, IOException>? = null
        private var spec: DataSpec? = null
        private var position = 0L
        private var end = 0L
        private var headers: Map<String, List<String>> = emptyMap()

        override fun open(dataSpec: DataSpec): Long {
            specs += dataSpec
            spec = dataSpec
            failAt?.let { (n, e) -> if (specs.size == n) throw e }
            val size = film.size.toLong()
            if (!honoursRanges) {
                position = dataSpec.position
                end = if (dataSpec.length == UNSET) size else minOf(size, dataSpec.position + dataSpec.length)
                headers = mapOf("Content-Length" to listOf("$size"))
                return if (dataSpec.length != UNSET) dataSpec.length else size - dataSpec.position
            }
            if (dataSpec.position == size) {
                position = size; end = size
                headers = mapOf("Content-Range" to listOf("bytes */$size"))
                return if (dataSpec.length != UNSET) dataSpec.length else 0
            }
            if (dataSpec.position > size) throw HttpDataSource.InvalidResponseCodeException(416, "Range Not Satisfiable", null, emptyMap(), dataSpec, ByteArray(0))
            position = dataSpec.position
            end = if (dataSpec.length == UNSET) size else minOf(size, dataSpec.position + dataSpec.length)
            headers = if (dataSpec.position == 0L && dataSpec.length == UNSET) mapOf("Content-Length" to listOf("$size"))
            else mapOf("Content-Range" to listOf("bytes ${dataSpec.position}-${end - 1}/${if (saysSize) "$size" else "*"}"), "Content-Length" to listOf("${end - dataSpec.position}"))
            return when {
                dataSpec.length != UNSET -> dataSpec.length
                saysSize || dataSpec.position == 0L -> end - position
                else -> UNSET
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), end - position).toInt()
            System.arraycopy(film, position.toInt(), buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = spec?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = if (spec == null) emptyMap() else headers
        override fun close() { spec = null }
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024
        const val UNSET = C.LENGTH_UNSET.toLong()
        /** As the player's loads are flagged. */
        const val PLAYER = DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION
    }
}
