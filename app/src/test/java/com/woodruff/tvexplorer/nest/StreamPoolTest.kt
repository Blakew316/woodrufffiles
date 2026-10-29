package com.woodruff.tvexplorer.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One stream per camera, shared by its views, closed a while after the last one lets go. */
class StreamPoolTest {
    private class Fake(val name: String) { var closed = 0 }

    private val opened = mutableListOf<Fake>()
    /** Closes waiting for their time, as (delay, action). */
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val pool = StreamPool<Fake>(
        close = { it.closed++ },
        later = { ms, action ->
            val t = ms to action
            timers += t
            val cancel: () -> Unit = { timers.remove(t) }
            cancel
        },
    )

    private fun open(name: String) = pool.acquire(name) { Fake(name).also { opened += it } }

    /** Time passes: every waiting close runs. */
    private fun elapse() {
        val due = timers.toList()
        timers.clear()
        due.forEach { it.second() }
    }

    @Test fun viewsOfOneCameraShareItsStream() {
        val a = open("door")
        val b = open("door")
        assertSame(a, b)
        assertEquals(1, opened.size)
        assertNotSame(a, open("yard"))
    }

    @Test fun theStreamStaysUpUntilItsLastViewLetsGo() {
        val s = open("door")
        open("door")
        pool.release("door", s, 0)
        assertEquals(0, s.closed)
        pool.release("door", s, 0)
        assertEquals(1, s.closed)
        assertTrue(pool.running().isEmpty())
    }

    @Test fun afterTheLastViewItLingersThenCloses() {
        val s = open("door")
        pool.release("door", s, 10_000)
        assertEquals(0, s.closed)
        assertEquals(10_000L, timers.single().first)
        elapse()
        assertEquals(1, s.closed)
        assertTrue(pool.running().isEmpty())
    }

    @Test fun theNextScreenTakesOverALingeringStream() {
        val s = open("door")
        pool.release("door", s, 10_000)
        assertSame(s, open("door"))
        assertTrue(timers.isEmpty())
        elapse()
        assertEquals(0, s.closed)
        assertEquals(1, opened.size)
    }

    @Test fun lingeringAgainStartsTheWaitOver() {
        val s = open("door")
        pool.release("door", s, 10_000)
        open("door")
        pool.release("door", s, 10_000)
        assertEquals(1, timers.size)
        elapse()
        assertEquals(1, s.closed)
    }

    @Test fun aClosedCameraStartsAFreshStream() {
        val s = open("door")
        pool.release("door", s, 0)
        val again = open("door")
        assertNotSame(s, again)
        assertEquals(2, opened.size)
    }

    @Test fun closeIdleLeavesStreamsInUse() {
        val shown = open("door")
        val idle = open("yard")
        pool.release("yard", idle, 10_000)
        pool.closeIdle()
        assertEquals(1, idle.closed)
        assertEquals(0, shown.closed)
        assertTrue(timers.isEmpty())
        assertEquals(setOf("door"), pool.running())
        // Its wait was called off: nothing closes twice.
        elapse()
        assertEquals(1, idle.closed)
    }

    @Test fun aLateOrRepeatedReleaseChangesNothing() {
        val old = open("door")
        pool.release("door", old, 0)
        val now = open("door")
        // A view of the closed stream letting go doesn't touch its successor.
        pool.release("door", old, 0)
        assertEquals(0, now.closed)
        pool.release("door", now, 10_000)
        // Released once too often: the linger isn't cut short.
        pool.release("door", now, 0)
        assertEquals(0, now.closed)
        elapse()
        assertEquals(1, now.closed)
        assertEquals(1, old.closed)
    }
}
