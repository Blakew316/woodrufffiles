package com.woodruff.tvexplorer.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * "Download to this TV" against a stand-in Drive on this machine: a download that stops keeps what
 * came and the next one carries on from there (it used to delete it, and the next press downloaded
 * a 20 GB film all over again), a cancelled one throws it away, and Google's daily limit is said as
 * that, not "try again in a moment".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DriveDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val film = Random(3).nextBytes(3 * 1024 * 1024)
    private val file = DriveFile("1FilmId", "Heat (1995).mkv", "video/x-matroska", film.size.toLong(), 0, null)
    private val drive = Drive()
    private val url get() = "http://127.0.0.1:${drive.listeningPort}/drive/v3/files/1FilmId?alt=media"
    private val dest get() = File(tmp.root, "Heat (1995).mkv")
    private val part get() = DriveApi.partialFor(tmp.root, file)

    @Before fun start() = drive.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)

    @After fun stop() = drive.stop()

    @Test fun aDownloadThatStopsKeepsWhatCameAndTheNextCarriesOnFromThere() = runBlocking {
        // The Wi-Fi drops a megabyte in.
        drive.cutAfter = 1_000_000
        val stopped = runCatching { DriveApi.download("token", file, dest, url) {} }.exceptionOrNull()
        assertTrue("$stopped", stopped is IOException)
        assertFalse(dest.exists())
        val kept = part.length()
        assertTrue("kept $kept", kept in 1..1_000_000)

        val seen = ArrayList<Long>()
        DriveApi.download("token", file, dest, url) { seen += it.doneBytes }
        assertArrayEquals(film, dest.readBytes())
        assertFalse("named as it should be once it's all in", part.exists())
        assertEquals(listOf(null, "bytes=$kept-"), drive.ranges)
        assertTrue("Bearer token", drive.auth.all { it == "Bearer token" })
        // Its progress carries on from what was kept, to the whole film.
        assertEquals(kept, seen.first())
        assertEquals(film.size.toLong(), seen.last())
    }

    @Test fun aCancelledDownloadThrowsAwayWhatCame() = runBlocking {
        var job: Job? = null
        job = launch(Dispatchers.IO) {
            DriveApi.download("token", file, dest, url) { if (it.doneBytes > 500_000) job?.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(part.exists())
        assertFalse(dest.exists())
    }

    @Test fun whenDriveSendsTheWholeFileAnywayItIsWrittenFromItsStart() = runBlocking {
        part.writeBytes(film.copyOfRange(0, 1234))
        drive.ignoresRanges = true
        DriveApi.download("token", file, dest, url) {}
        assertArrayEquals(film, dest.readBytes())
    }

    @Test fun whatWasKeptOfAnotherVersionOfTheFileIsNotCarriedOn() = runBlocking {
        // The film replaced in Drive by one of another size: its partial file is another's.
        val older = file.copy(size = film.size + 10L)
        DriveApi.partialFor(tmp.root, older).writeBytes(ByteArray(1234))
        DriveApi.download("token", file, dest, url) {}
        assertArrayEquals(film, dest.readBytes())
        assertEquals(listOf<String?>(null), drive.ranges)
        assertTrue(DriveApi.partialFor(tmp.root, file) != DriveApi.partialFor(tmp.root, older))
    }

    @Test fun aDownloadThatHadAllComeIsNamedWithoutAskingAgain() = runBlocking {
        part.writeBytes(film)
        DriveApi.download("token", file, dest, url) {}
        assertArrayEquals(film, dest.readBytes())
        assertEquals(emptyList<String?>(), drive.ranges)
    }

    @Test fun googlesDailyLimitIsSaidAsThatAndWhatCameIsKept() = runBlocking {
        drive.cutAfter = 700_000
        runCatching { DriveApi.download("token", file, dest, url) {} }
        val kept = part.length()
        drive.refusal = 403 to "downloadQuotaExceeded"
        val refused = runCatching { DriveApi.download("token", file, dest, url) {} }.exceptionOrNull()
        assertTrue("$refused", refused is DriveHttpException)
        assertEquals(
            "Google Drive won’t send this file right now (its daily limit). It usually clears within a day.",
            refused?.message,
        )
        assertEquals(kept, part.length())
        assertEquals("Downloading it again carries on from ${kept * 100 / film.size}%.", downloadCarriesOn(kept, film.size.toLong()))
    }

    @Test fun whatNobodyCarriedOnWithForDaysIsThrownAway() {
        // Hidden, a forgotten one could hold gigabytes of a small TV's storage.
        val now = System.currentTimeMillis()
        val stale = File(tmp.root, ".1OldFilm.2000000.part").apply { writeBytes(ByteArray(10)); setLastModified(now - DriveApi.STALE_PARTIAL_MS - 60_000) }
        val recent = File(tmp.root, ".1Other.3000000.part").apply { writeBytes(ByteArray(10)); setLastModified(now - 60_000) }
        val film = File(tmp.root, "Old Film.mkv").apply { writeBytes(ByteArray(10)); setLastModified(now - DriveApi.STALE_PARTIAL_MS - 60_000) }
        val own = part.apply { writeBytes(ByteArray(10)); setLastModified(now - DriveApi.STALE_PARTIAL_MS - 60_000) }
        DriveApi.dropStalePartials(tmp.root, now, keep = own)
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue("a download that finished is never touched", film.exists())
        assertTrue("this download's own carries on", own.exists())
    }

    @Test fun whereADownloadCarriesOnFrom() {
        // The rest of the file from where it got to, at the size kept: carried on.
        assertEquals(500L, DriveApi.resumeFrom(206, "bytes 500-999/1000", have = 500, size = 1000))
        assertEquals(500L, DriveApi.resumeFrom(206, "bytes 500-999/1000", have = 500, size = 0))
        // Anything else starts from the first byte.
        assertEquals(0L, DriveApi.resumeFrom(200, null, have = 500, size = 1000))
        assertEquals(0L, DriveApi.resumeFrom(206, "bytes 0-999/1000", have = 500, size = 1000))
        assertEquals(0L, DriveApi.resumeFrom(206, "bytes 500-1009/1010", have = 500, size = 1000))
        assertEquals(0L, DriveApi.resumeFrom(206, null, have = 500, size = 1000))
    }

    @Test fun whatAStoppedDownloadSaysItKept() {
        assertNull(downloadCarriesOn(0, 1000))
        assertEquals("Downloading it again carries on from 90%.", downloadCarriesOn(900, 1000))
        assertEquals("Downloading it again carries on from 99%.", downloadCarriesOn(999, 1000))
        assertEquals("Downloading it again carries on from where it stopped.", downloadCarriesOn(900, 0))
        // Never "try again in a moment" for Google's daily limit.
        assertEquals(DriveApi.DAILY_LIMIT, DriveApi.answerWords(403, "downloadQuotaExceeded"))
        assertEquals(DriveApi.CANT_REACH, DriveApi.answerWords(403, "userRateLimitExceeded"))
    }

    /** Google Drive as a download meets it: the film, from the byte a Range asks for, as Drive answers it. */
    private inner class Drive : NanoHTTPD("127.0.0.1", 0) {
        val ranges = CopyOnWriteArrayList<String?>()
        val auth = CopyOnWriteArrayList<String?>()
        /** The next answer stops after this many bytes (the Wi-Fi dropping). */
        @Volatile var cutAfter: Int? = null
        @Volatile var ignoresRanges = false
        @Volatile var refusal: Pair<Int, String>? = null

        override fun serve(session: IHTTPSession): Response {
            val range = session.headers["range"]
            ranges += range
            auth += session.headers["authorization"]
            refusal?.let { (code, reason) ->
                return newFixedLengthResponse(Response.Status.lookup(code), "application/json", """{"error":{"errors":[{"reason":"$reason"}],"code":$code}}""")
            }
            val start = if (ignoresRanges) 0 else range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toInt() } ?: 0
            if (start >= film.size) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").apply { addHeader("Content-Range", "bytes */${film.size}") }
            }
            val body = film.copyOfRange(start, film.size)
            val cut = cutAfter.also { cutAfter = null }
            val stream: InputStream = if (cut != null) Cut(body, cut) else ByteArrayInputStream(body)
            val partial = range != null && !ignoresRanges
            return newFixedLengthResponse(if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, "video/x-matroska", stream, body.size.toLong()).apply {
                if (partial) addHeader("Content-Range", "bytes $start-${film.size - 1}/${film.size}")
            }
        }
    }

    /** [bytes], then a dropped connection after [after] of them. */
    private class Cut(private val bytes: ByteArray, private val after: Int) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at >= after) throw IOException("dropped") else bytes[at++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= after) throw IOException("dropped")
            val n = minOf(len, after - at)
            System.arraycopy(bytes, at, b, off, n)
            at += n
            return n
        }
    }
}
