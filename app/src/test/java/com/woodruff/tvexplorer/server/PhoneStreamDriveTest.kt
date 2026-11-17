package com.woodruff.tvexplorer.server

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * A Drive film played on the phone through this TV (PhoneStream.drive): the phone gets exactly the
 * part it asked for, as Drive would have sent it, while Google is asked in bounded pieces, each with
 * the TV's token, and nothing more once the phone hangs up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneStreamDriveTest {
    private val film = Random(5).nextBytes(20 * 1024 * 1024)
    private val drive = Drive(film)

    private fun ask(range: String?): Response = PhoneStream.drive("abc123", range, { "token" }, DataSource.Factory { drive })

    @Test fun fromASpotToTheEndIsSentWholeAndAskedOfGoogleInPieces() {
        val r = ask("bytes=1000-")
        assertEquals(Response.Status.PARTIAL_CONTENT, r.status)
        assertEquals("bytes 1000-${film.size - 1}/${film.size}", r.getHeader("Content-Range"))
        assertEquals("video/x-matroska", r.mimeType)
        assertArrayEquals(film.copyOfRange(1000, film.size), r.data.readBytes())
        assertTrue(drive.specs.all { it.length != C.LENGTH_UNSET.toLong() && it.length <= 64L * 1024 * 1024 })
        assertEquals(8L * 1024 * 1024, drive.specs.first().length)
        assertTrue(drive.specs.all { it.httpRequestHeaders["Authorization"] == "Bearer token" })
    }

    @Test fun aWholeFileAskedForIsSentWhole() {
        val r = ask(null)
        assertEquals(Response.Status.OK, r.status)
        assertNull(r.getHeader("Content-Range"))
        assertArrayEquals(film, r.data.readBytes())
        assertTrue(drive.specs.all { it.length != C.LENGTH_UNSET.toLong() })
    }

    @Test fun aBoundedRangeEndsWhereAsked() {
        val r = ask("bytes=0-1")
        assertEquals("bytes 0-1/${film.size}", r.getHeader("Content-Range"))
        assertArrayEquals(film.copyOfRange(0, 2), r.data.readBytes())
        assertEquals(listOf(0L to 2L), drive.specs.map { it.position to it.length })
    }

    @Test fun aPhoneThatHangsUpAsksForNothingMore() {
        val r = ask("bytes=0-")
        val body = r.data
        body.read(ByteArray(1000))
        body.close()
        assertEquals(1, drive.specs.size)
        assertEquals(8L * 1024 * 1024, drive.specs.single().length)
    }

    @Test fun googlesRefusalsReachThePhoneAsTheyCame() {
        // Its daily limit (403) and too many requests (429): the phone's player heard "the TV failed" (500),
        // which it takes as worth asking again at once, and again, against the same limit.
        for ((code, reason) in listOf(403 to "downloadQuotaExceeded", 429 to null, 403 to "userRateLimitExceeded")) {
            val refusing = DataSource.Factory { Refusing(code, reason) }
            val e = runCatching { PhoneStream.drive("abc123", "bytes=0-", { "token" }, refusing) }.exceptionOrNull()
            assertEquals("$code $reason", code, (e as? PhoneStream.DriveRefused)?.status)
        }
        // Through the TV's server: the same answer.
        val token = "k3y"
        for ((code, status) in listOf(403 to Response.Status.FORBIDDEN, 429 to Response.Status.TOO_MANY_REQUESTS)) {
            val server = TransferServer(0, token, "<html></html>", { emptyList() }, {}, extra = { throw PhoneStream.answered(code) })
            assertEquals(status, server.serve(phoneRequest("/api/elsewhere", params = mapOf("k" to token))).status)
        }
        // Anything else is still the TV's own trouble, as before.
        assertTrue(PhoneStream.answered(500) !is PhoneStream.DriveRefused)
        assertTrue(PhoneStream.answered(404) !is PhoneStream.DriveRefused)
        assertEquals("Past the end of the video", PhoneStream.answered(416).message)
    }

    /** Google Drive turning every request down with [code] and its [reason]. */
    private class Refusing(private val code: Int, private val reason: String?) : DataSource {
        override fun open(dataSpec: DataSpec): Long {
            val body = reason?.let { """{"error":{"errors":[{"reason":"$it"}],"code":$code}}""".toByteArray() } ?: ByteArray(0)
            throw androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), dataSpec, body)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = null
        override fun close() = Unit
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }

    @Test fun theRangesTakenAreSingleOnes() {
        assertEquals(0L to null, PhoneStream.askedRange("bytes=0-"))
        assertEquals(5L to 9L, PhoneStream.askedRange("bytes=5-9"))
        assertNull(PhoneStream.askedRange("bytes=-500"))
        assertNull(PhoneStream.askedRange("bytes=0-1,5-9"))
        assertNull(PhoneStream.askedRange("bytes=9-5"))
        assertNull(PhoneStream.askedRange("items=0-1"))
    }

    /** Drive as OkHttpDataSource answers ranges: Content-Range with the size, the length as asked. */
    private class Drive(val film: ByteArray) : DataSource {
        val specs = ArrayList<DataSpec>()
        private var open: DataSpec? = null
        private var position = 0L
        private var end = 0L

        override fun open(dataSpec: DataSpec): Long {
            specs += dataSpec
            open = dataSpec
            position = dataSpec.position
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) film.size.toLong() else minOf(film.size.toLong(), dataSpec.position + dataSpec.length)
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else end - position
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), end - position).toInt()
            System.arraycopy(film, position.toInt(), buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = open?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = open?.let {
            mapOf("Content-Range" to listOf("bytes ${it.position}-${end - 1}/${film.size}"), "Content-Type" to listOf("video/x-matroska"))
        } ?: emptyMap()
        override fun close() { open = null }
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }
}
