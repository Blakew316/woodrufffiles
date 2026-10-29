package com.woodruff.tvexplorer.mail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One connection per account, used in turn, replaced once when it has dropped, closed when done with. */
class KeptConnectionsTest {
    private class Fake(val account: MailAccount) { @Volatile var closed = 0 }

    private val icloud = MailAccount("1", MailProvider.ICLOUD, "me@icloud.com", "imap.mail.me.com", 993, "abcd")
    private val gmail = MailAccount("2", MailProvider.GMAIL, "me@gmail.com", "imap.gmail.com", 993, "efgh")

    private val opened = Collections.synchronizedList(mutableListOf<Fake>())
    /** The server turns sign-ins away. */
    @Volatile private var refuse = false

    /** Runs [test] with closes and waits on the test's own thread; [idleMs]: how long one sits unused before closing. */
    private fun withKept(idleMs: Long = 60_000, test: suspend CoroutineScope.(KeptConnections<Fake>) -> Unit): Unit = runBlocking {
        val kept = KeptConnections(
            connect = { a -> if (refuse) throw IllegalStateException("refused"); Fake(a).also { opened += it } },
            close = { it.closed++ },
            // As MailClient's "That message was deleted".
            mayHaveDropped = { it !is NoSuchElementException },
            idleMs = idleMs,
            scope = this,
        )
        test(kept)
        kept.keep(false) // nothing left waiting
    }

    /** Lets the closes and waits already due run. */
    private suspend fun settle() = repeat(10) { yield() }

    @Test fun usesOfOneAccountShareItsConnection() = withKept { kept ->
        kept.keep(true)
        val first = kept.use(icloud) { c, reused -> assertFalse(reused); c }
        val second = kept.use(icloud) { c, reused -> assertTrue(reused); c }
        assertSame(first, second)
        assertEquals(1, opened.size)
        settle()
        assertEquals(0, first.closed)
    }

    @Test fun eachAccountHasItsOwn() = withKept { kept ->
        kept.keep(true)
        val a = kept.use(icloud) { c, _ -> c }
        val b = kept.use(gmail) { c, _ -> c }
        assertNotSame(a, b)
        assertEquals(listOf(icloud, gmail), opened.map { it.account })
    }

    @Test fun notKeptItClosesAfterEachUse() = withKept { kept ->
        val first = kept.use(icloud) { c, _ -> c }
        settle()
        assertEquals(1, first.closed)
        kept.use(icloud) { _, reused -> assertFalse(reused) }
        assertEquals(2, opened.size)
    }

    @Test fun leavingClosesThemAll() = withKept { kept ->
        kept.keep(true)
        val a = kept.use(icloud) { c, _ -> c }
        val b = kept.use(gmail) { c, _ -> c }
        kept.keep(false)
        settle()
        assertEquals(1, a.closed)
        assertEquals(1, b.closed)
    }

    @Test fun leavingDuringAUseClosesItAfterward() = withKept { kept ->
        kept.keep(true)
        val busy = CountDownLatch(1)
        val started = CompletableDeferred<Fake>()
        val use = launch(Dispatchers.Default) { kept.use(icloud) { c, _ -> started.complete(c); busy.await(5, TimeUnit.SECONDS) } }
        val c = started.await()
        kept.keep(false)
        settle()
        assertEquals(0, c.closed)
        busy.countDown()
        use.join()
        settle()
        assertEquals(1, c.closed)
    }

    @Test fun aDroppedConnectionIsReplacedOnce() = withKept { kept ->
        kept.keep(true)
        val old = kept.use(icloud) { c, _ -> c }
        val result = kept.use(icloud) { c, reused ->
            if (reused) throw IOException("Connection reset")
            c
        }
        assertEquals(2, opened.size)
        assertSame(opened[1], result)
        settle()
        assertEquals(1, old.closed)
        // The new one is kept in its place.
        kept.use(icloud) { c, reused -> assertTrue(reused); assertSame(result, c) }
    }

    @Test fun itTriesOnceMoreOnly() = withKept { kept ->
        kept.keep(true)
        kept.use(icloud) { _, _ -> }
        val e = runCatching { kept.use(icloud) { _, reused -> throw IOException(if (reused) "Connection reset" else "Still down") } }.exceptionOrNull()
        assertEquals("Still down", e?.message)
        assertEquals(2, opened.size)
    }

    @Test fun aNewConnectionIsNotTriedTwice() = withKept { kept ->
        kept.keep(true)
        val e = runCatching { kept.use(icloud) { _, _ -> throw IOException("No route to host") } }.exceptionOrNull()
        assertEquals("No route to host", e?.message)
        assertEquals(1, opened.size)
    }

    @Test fun aRefusedSignInReachesTheCaller() = withKept { kept ->
        kept.keep(true)
        kept.use(icloud) { _, _ -> }
        refuse = true
        val e = runCatching { kept.use(icloud) { _, reused -> if (reused) throw IOException("Connection reset") } }.exceptionOrNull()
        assertEquals("refused", e?.message)
        refuse = false
        kept.use(icloud) { _, reused -> assertFalse(reused) }
        assertEquals(2, opened.size)
    }

    @Test fun aDeletedMessageKeepsTheConnection() = withKept { kept ->
        kept.keep(true)
        val c = kept.use(icloud) { c, _ -> c }
        val e = runCatching { kept.use(icloud) { _, _ -> throw NoSuchElementException("That message was deleted") } }.exceptionOrNull()
        assertTrue(e is NoSuchElementException)
        assertEquals(1, opened.size)
        settle()
        assertEquals(0, c.closed)
        kept.use(icloud) { _, reused -> assertTrue(reused) }
    }

    @Test fun aChangedPasswordSignsInAfresh() = withKept { kept ->
        kept.keep(true)
        val old = kept.use(icloud) { c, _ -> c }
        kept.use(icloud.copy(password = "wxyz")) { _, reused -> assertFalse(reused) }
        assertEquals(2, opened.size)
        settle()
        assertEquals(1, old.closed)
    }

    @Test fun keepOnlyClosesTheRest() = withKept { kept ->
        kept.keep(true)
        val a = kept.use(icloud) { c, _ -> c }
        val b = kept.use(gmail) { c, _ -> c }
        kept.keepOnly(listOf(gmail))
        settle()
        assertEquals(1, a.closed)
        assertEquals(0, b.closed)
        kept.use(gmail) { _, reused -> assertTrue(reused) }
    }

    @Test fun anUnusedConnectionClosesAfterAWhile() = withKept(idleMs = 100) { kept ->
        kept.keep(true)
        val c = kept.use(icloud) { c, _ -> c }
        delay(50)
        // Used again: the wait starts over.
        kept.use(icloud) { _, reused -> assertTrue(reused) }
        delay(70)
        assertEquals(0, c.closed)
        delay(100)
        assertEquals(1, c.closed)
        kept.use(icloud) { _, reused -> assertFalse(reused) }
    }

    @Test fun usesOfOneAccountTakeTurns() = withKept { kept ->
        kept.keep(true)
        val inside = AtomicInteger()
        val overlapped = AtomicBoolean()
        (1..8).map {
            launch(Dispatchers.Default) {
                kept.use(icloud) { _, _ ->
                    if (inside.incrementAndGet() > 1) overlapped.set(true)
                    Thread.sleep(5)
                    inside.decrementAndGet()
                }
            }
        }.joinAll()
        assertFalse(overlapped.get())
        assertEquals(1, opened.size)
    }

    @Test fun aUseGivenUpWhileWaitingNeverRuns() = withKept { kept ->
        kept.keep(true)
        val busy = CountDownLatch(1)
        val started = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) { kept.use(icloud) { _, _ -> started.complete(Unit); busy.await(5, TimeUnit.SECONDS) } }
        started.await()
        val ran = AtomicBoolean()
        val second = launch(Dispatchers.Default) { kept.use(icloud) { _, _ -> ran.set(true) } }
        delay(20) // waiting its turn
        second.cancel()
        busy.countDown()
        joinAll(first, second)
        assertFalse(ran.get())
    }

    @Test fun aUseGivenUpDoesNotSignInAgain() = withKept { kept ->
        kept.keep(true)
        kept.use(icloud) { _, _ -> }
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            kept.use(icloud) { _, reused ->
                job.cancel() // another message was opened meanwhile
                if (reused) throw IOException("Connection reset")
            }
        }
        job.join()
        assertEquals(1, opened.size)
    }
}
