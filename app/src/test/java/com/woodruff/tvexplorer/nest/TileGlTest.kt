package com.woodruff.tvexplorer.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camera tile on a screen that stopped (Woodruff left from Home for another app) holds no GL, so
 * the tiles' shared GL thread can be let go after ten minutes; it's set up again as the screen starts.
 */
class TileGlTest {
    // The tiles' background thread: what's asked runs in order, when drained.
    private val queue = ArrayDeque<() -> Unit>()
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    private var setUps = 0
    private var letGos = 0
    /** What the GL side calls back on its next drawn frame; letting go forgets it, as WebRTC's renderer does. */
    private val listeners = ArrayList<() -> Unit>()

    private fun tile() = TileGl({ queue.addLast(it) }, setUp = { setUps++ }, letGo = { letGos++; listeners.clear() }) { listeners += it }

    private fun drawFrame() = listeners.toList().also { listeners.clear() }.forEach { it() }

    @Test fun aTileOnAStoppedScreenHoldsNoGlUntilItStartsAgain() {
        val t = tile()
        drain()
        assertEquals(1, setUps)
        t.pause()
        drain()
        assertEquals("its GL side goes as its screen stops", 1, letGos)
        t.pause()
        drain()
        assertEquals(1, letGos)
        t.resume()
        drain()
        assertEquals("set up again as it starts", 2, setUps)
        t.resume()
        drain()
        assertEquals(2, setUps)
    }

    @Test fun eachStepComesInTheOrderAsked() {
        // The screen's first start, with the tile's set-up still waiting: nothing more.
        val t = tile()
        t.resume()
        drain()
        assertEquals(1, setUps)
        assertEquals(0, letGos)
        // Stopped before its set-up ran: set up, then let go.
        val u = tile()
        u.pause()
        drain()
        assertEquals(2, setUps)
        assertEquals(1, letGos)
    }

    @Test fun whatWasWaitedForIsAskedForAgainOnceItsSetUpAgain() {
        val t = tile()
        var drawn = 0
        t.afterNextFrame { drawn++ }
        drain()
        t.pause()
        t.resume()
        drain()
        drawFrame()
        assertEquals(1, drawn)
        drawFrame()
        assertEquals("once", 1, drawn)
        // Asked for while it's let go: asked for once it's set up again.
        t.pause()
        t.afterNextFrame { drawn++ }
        drain()
        assertTrue(listeners.isEmpty())
        t.resume()
        drain()
        drawFrame()
        assertEquals(2, drawn)
    }

    @Test fun aFrameDrawnJustBeforeItWasLetGoCountsOnce() {
        // The GL thread drew a frame just as the tile was let go: its word comes late, then again after a set-up.
        val t = TileGl({ queue.addLast(it) }, setUp = { setUps++ }, letGo = { letGos++ }) { listeners += it }
        var drawn = 0
        t.afterNextFrame { drawn++ }
        drain()
        t.pause()
        t.resume()
        drain()
        drawFrame()
        assertEquals(1, drawn)
    }

    @Test fun letGoForGoodItIsNeverSetUpAgain() {
        val t = tile()
        drain()
        t.pause()
        drain()
        t.release()
        drain()
        assertEquals("let go once, not twice", 1, letGos)
        t.resume()
        t.afterNextFrame {}
        drain()
        assertEquals(1, setUps)
        assertTrue(listeners.isEmpty())
        assertTrue(t.released)
        // Let go before it was ever set up: never set up at all.
        val u = tile()
        u.release()
        drain()
        assertEquals(1, setUps)
        assertEquals(1, letGos)
    }
}
