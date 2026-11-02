package com.woodruff.tvexplorer.tvs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The phone's keyboard on a linked TV, through the hub (finding 42): the hub asks the TV for its text box and sends
 * what's typed with its link key; the TV's own refusal comes back in its words, and a TV on an older Woodruff
 * (which turns typing down with a link key that works for the rest) is told apart from a link that stopped working.
 * Against a stand-in TV on this machine's loopback address (nothing leaves the test).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TvTypingTest {
    private var server: ServerSocket? = null
    private var asked: String? = null

    @After fun stop() { server?.close() }

    /** A TV at a loopback port that answers one request with [status] and [body]. */
    private fun tv(status: String, body: String, type: String = "application/json"): LinkedTv {
        server?.close()
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

    @Test fun typingIsALinkRouteLikeTheRemoteKeys() {
        assertTrue("/api/type" in TvLinks.LINK_ROUTES)
        assertTrue("/api/key" in TvLinks.LINK_ROUTES)
        // The hub's own relays stay behind the phone's pairing key.
        assertFalse("/api/tvs/type" in TvLinks.LINK_ROUTES)
    }

    @Test fun theHubAsksTheTvForItsTextBox() {
        val den = tv("200 OK", """{"field":{"id":7,"label":"Search","secret":false,"multiline":false,"text":"har"}}""")
        val f = TvLinks.field(den)!!
        assertEquals(7L, f.getLong("id"))
        assertEquals("Search", f.getString("label"))
        assertEquals("har", f.getString("text"))
        assertTrue(asked!!.startsWith("GET /api/type?k=link-key "))
    }

    @Test fun noTextBoxOpenIsNull() {
        assertNull(TvLinks.field(tv("200 OK", """{"field":null}""")))
    }

    @Test fun whatIsTypedGoesWithTheBoxItWasTypedFor() {
        val den = tv("200 OK", """{"ok":true}""")
        TvLinks.type(den, 7, "Midnight Harbor", submit = true)
        val sent = asked!!
        assertTrue(sent.startsWith("POST /api/type?k=link-key "))
        val body = JSONObject(sent.substringAfter('\n'))
        assertEquals(7L, body.getLong("id"))
        assertEquals("Midnight Harbor", body.getString("text"))
        assertTrue(body.getBoolean("submit"))
    }

    @Test fun withNoBoxNamedItGoesToWhicheverIsOpen() {
        val den = tv("200 OK", """{"ok":true}""")
        TvLinks.type(den, null, "har", submit = false)
        val body = JSONObject(asked!!.substringAfter('\n'))
        assertFalse(body.has("id"))
        assertFalse(body.getBoolean("submit"))
    }

    @Test fun theTvsOwnRefusalComesBackInItsWords() {
        val den = tv("400 Bad Request", "No text box is open on Den", "text/plain")
        val e = assertThrows(TvLinks.Refused::class.java) { TvLinks.type(den, 7, "har", submit = false) }
        assertEquals("No text box is open on Den", e.message)
    }

    @Test fun anOlderWoodruffTurningTypingDownIsNotALinkThatStopped() {
        val den = tv("401 Unauthorized", "Scan the QR code on the TV (or type the full address shown there) to connect.", "text/plain")
        assertThrows(TvLinks.TypingRefused::class.java) { TvLinks.type(den, 7, "har", submit = false) }
        assertFalse(TvLinks.refuses(den.id))
        val again = tv("401 Unauthorized", "Scan the QR code", "text/plain")
        assertThrows(TvLinks.TypingRefused::class.java) { TvLinks.field(again) }
    }

    @Test fun aTvThatDoesNotAnswerFailsAsNotAnswering() {
        val gone = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { s -> s.localPort.also { s.close() } }
        val den = LinkedTv("den-1", "Den", "http://127.0.0.1:$gone", "link-key")
        val e = assertThrows(IOException::class.java) { TvLinks.type(den, 7, "har", submit = false) }
        assertFalse(e is TvLinks.Refused || e is TvLinks.TypingRefused)
    }
}
