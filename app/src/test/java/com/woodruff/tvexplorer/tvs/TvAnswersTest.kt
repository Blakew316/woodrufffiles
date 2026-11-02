package com.woodruff.tvexplorer.tvs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * What a linked TV answers to a start or a control comes back to the phone, and a TV that doesn't
 * answer, or says no, still fails: the phone says "Den didn't respond" or the TV's own words, and
 * Watch together counts only the TVs that started. Against a stand-in TV on this machine's loopback
 * address (nothing leaves the test).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TvAnswersTest {
    private var server: ServerSocket? = null
    private var asked: String? = null

    @After fun stop() { server?.close() }

    /** A TV at a loopback port that answers one request with [status] and [body]. */
    private fun tv(status: String, body: String, type: String = "application/json"): LinkedTv {
        val s = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { server = it }
        thread(isDaemon = true) {
            runCatching {
                s.accept().use { c ->
                    val input = c.getInputStream().bufferedReader()
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val len = head.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val chars = CharArray(len).also { var n = 0; while (n < len) { val r = input.read(it, n, len - n); if (r < 0) break; n += r } }
                    asked = head.first() + "\n" + String(chars)
                    val bytes = body.toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        return LinkedTv("den-1", "Den", "http://127.0.0.1:${s.localPort}", "link-key")
    }

    @Test fun aStartBringsBackWhetherItIsOnScreen() {
        val den = tv("200 OK", """{"ok":true,"started":false,"message":"It’s waiting on the TV: open Woodruff Family there."}""")
        val answer = TvLinks.watch(den, "drive:m1", "Midnight Harbor (2024).mkv", 2_710_000, again = false)
        assertFalse(answer.getBoolean("started"))
        assertEquals("It’s waiting on the TV: open Woodruff Family there.", answer.getString("message"))
        val sent = asked!!
        assertTrue(sent.startsWith("POST /api/watch?k=link-key "))
        assertTrue(sent.contains("\"position\":2710000"))
    }

    @Test fun anOlderWoodruffThatJustSaysOkStillCounts() {
        val den = tv("200 OK", "ok", "text/plain")
        val answer = TvLinks.watch(den, "drive:m1", "Midnight Harbor (2024).mkv", 0, again = false)
        assertTrue("nothing to read, so it started as far as anyone knows", answer.optBoolean("started", true))
    }

    @Test fun aControlBringsBackItsWordsWhenTheTvSaysNo() {
        val den = tv("400 Bad Request", "Nothing is playing on Den", "text/plain")
        val e = assertThrows(TvLinks.Refused::class.java) { TvLinks.media(den, "fwd30") }
        assertEquals("Nothing is playing on Den", e.message)
        assertTrue(asked!!.startsWith("POST /api/media?k=link-key "))
        assertTrue(asked!!.contains("\"action\":\"fwd30\""))
    }

    @Test fun aControlBringsBackWhatItIsDoingNow() {
        val den = tv("200 OK", """{"ok":true,"playing":false}""")
        assertEquals(false, TvLinks.media(den, "playpause").getBoolean("playing"))
    }

    @Test fun aTvThatDoesNotAnswerFails() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }   // nothing listens there any more
        val gone = LinkedTv("den-1", "Den", "http://127.0.0.1:$closed", "link-key")
        val e = assertThrows(IOException::class.java) { TvLinks.watch(gone, "drive:m1", "Midnight Harbor (2024).mkv", 0, again = false) }
        assertFalse(e is TvLinks.Refused)
        assertNotNull(assertThrows(IOException::class.java) { TvLinks.media(gone, "back10") })
    }
}
