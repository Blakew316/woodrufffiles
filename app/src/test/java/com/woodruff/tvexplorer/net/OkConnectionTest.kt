package com.woodruff.tvexplorer.net

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket

/**
 * [Http.open] against a server on this machine: the calls that moved to it from Android's own
 * connection must find it behaving the same (what's sent, what's read, what's thrown).
 */
class OkConnectionTest {
    /** One request as the server got it. */
    private class Got(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private class Server : NanoHTTPD("127.0.0.1", 0) {
        val got = mutableListOf<Got>()

        override fun serve(session: IHTTPSession): Response {
            val length = session.headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length).also { DataInputStream(session.inputStream).readFully(it) }
            synchronized(got) { got += Got(session.method.name, session.uri, session.headers.toMap(), String(body)) }
            return when (session.uri) {
                "/missing" -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"gone"}""")
                "/moved" -> newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply { addHeader("Location", "/there") }
                "/elsewhere" ->
                    newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply { addHeader("Location", "http://localhost:$listeningPort/there") }
                "/slow" -> { Thread.sleep(1500); newFixedLengthResponse("late") }
                "/part" -> newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", "abcd").apply { addHeader("Content-Range", "bytes 2-5/10") }
                else -> newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
            }
        }
    }

    private val server = Server()
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private val last get() = synchronized(server.got) { server.got.last() }

    @Before fun start() = server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)

    @After fun stop() = server.stop()

    @Test
    fun aGetSendsItsHeadersAndReadsTheAnswer() {
        val conn = Http.open("$base/files?q=a%20b").apply {
            setRequestProperty("Authorization", "Bearer abc")
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            assertEquals(200, conn.responseCode)
            assertEquals("""{"ok":true}""", conn.inputStream.bufferedReader().use { it.readText() })
            assertEquals("application/json", conn.contentType)
            assertNull(conn.errorStream)
        } finally {
            conn.disconnect()
        }
        assertEquals("GET", last.method)
        assertEquals("/files", last.path)
        assertEquals("Bearer abc", last.headers["authorization"])
    }

    @Test
    fun anErrorAnswerIsReadFromTheErrorStream() {
        val conn = Http.open("$base/missing")
        assertEquals(404, conn.responseCode)
        assertEquals("""{"error":"gone"}""", conn.errorStream?.bufferedReader()?.use { it.readText() })
        try {
            conn.inputStream
            fail("the input stream of an error answer opened")
        } catch (expected: FileNotFoundException) {
        }
        conn.disconnect()
    }

    @Test
    fun aBodyIsSentWithItsTypeUnderEachMethod() {
        for (method in listOf("POST", "PUT", "PATCH")) {
            val conn = Http.open("$base/write").apply {
                requestMethod = method
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(9)
            }
            conn.outputStream.use { it.write("""{"a":"é"}""".toByteArray()) }
            assertEquals(200, conn.responseCode)
            conn.disconnect()
            assertEquals(method, last.method)
            assertEquals("""{"a":"é"}""", last.body)
            assertEquals("application/json", last.headers["content-type"])
        }
    }

    @Test
    fun aDeleteGoesWithoutABody() {
        val conn = Http.open("$base/thing").apply { requestMethod = "DELETE" }
        assertEquals(200, conn.responseCode)
        conn.disconnect()
        assertEquals("DELETE", last.method)
        assertEquals("", last.body)
    }

    @Test
    fun aPostWithNothingWrittenStillGoes() {
        // How signing out asks Google to revoke a token: the token is in the address, the body empty.
        Http.open("$base/revoke?token=t").apply {
            requestMethod = "POST"; doOutput = true; outputStream.close(); responseCode; disconnect()
        }
        assertEquals("POST", last.method)
        assertEquals("", last.body)
        assertEquals("0", last.headers["content-length"])
        assertEquals("application/x-www-form-urlencoded", last.headers["content-type"])
    }

    @Test
    fun writingABodyTurnsAGetIntoAPost() {
        val conn = Http.open("$base/write").apply { doOutput = true }
        conn.outputStream.use { it.write("a=1".toByteArray()) }
        assertEquals(200, conn.responseCode)
        conn.disconnect()
        assertEquals("POST", last.method)
        assertEquals("a=1", last.body)
    }

    @Test
    fun aRedirectIsFollowedUnlessTurnedOff() {
        val followed = Http.open("$base/moved")
        assertEquals(200, followed.responseCode)
        followed.disconnect()
        assertEquals("/there", last.path)

        val kept = Http.open("$base/moved").apply { instanceFollowRedirects = false }
        assertEquals(301, kept.responseCode)
        assertEquals("/there", kept.getHeaderField("Location"))
        kept.disconnect()
        assertEquals("/moved", last.path)
    }

    @Test
    fun aRedirectToAnotherHostDoesNotTakeTheTokenAlong() {
        val conn = Http.open("$base/elsewhere").apply { setRequestProperty("Authorization", "Bearer abc") }
        assertEquals(200, conn.responseCode)
        conn.disconnect()
        assertEquals("/there", last.path)
        assertNull(last.headers["authorization"])
    }

    @Test
    fun aPartOfAFileIsAskedForAndSaysWhichPartItIs() {
        val conn = Http.open("$base/part").apply { setRequestProperty("Range", "bytes=2-5") }
        assertEquals(206, conn.responseCode)
        assertEquals("bytes 2-5/10", conn.getHeaderField("Content-Range"))
        assertEquals("4", conn.getHeaderField("Content-Length"))
        assertEquals("video/mp4", conn.contentType)
        assertEquals("abcd", conn.inputStream.bufferedReader().use { it.readText() })
        assertEquals("bytes=2-5", last.headers["range"])
    }

    @Test
    fun anAnswerThatTakesTooLongIsGivenUpOn() {
        val conn = Http.open("$base/slow").apply { readTimeout = 300 }
        val began = System.nanoTime()
        try {
            conn.responseCode
            fail("waited for an answer past its read timeout")
        } catch (expected: IOException) {
        }
        assertTrue("gave up after ${(System.nanoTime() - began) / 1_000_000} ms", System.nanoTime() - began < 1_200_000_000L)
        // The same failure every time it's asked, and no header to read.
        try {
            conn.responseCode
            fail("answered after failing")
        } catch (expected: IOException) {
        }
        assertNull(conn.getHeaderField("Content-Type"))
        assertNull(conn.errorStream)
        conn.disconnect()
    }

    @Test
    fun nothingListeningIsAnIOException() {
        val free = ServerSocket(0).use { it.localPort }
        val conn = Http.open("http://127.0.0.1:$free/").apply { connectTimeout = 2_000 }
        try {
            conn.responseCode
            fail("answered with nothing listening")
        } catch (expected: IOException) {
        }
        conn.disconnect()
    }

    @Test
    fun aRequestIsOnlyChangedBeforeItIsSent() {
        val conn: HttpURLConnection = Http.open("$base/files")
        assertEquals(200, conn.responseCode)
        try {
            conn.setRequestProperty("Authorization", "Bearer late")
            fail("a header was taken after the answer")
        } catch (expected: IllegalStateException) {
        }
        conn.disconnect()
    }
}
