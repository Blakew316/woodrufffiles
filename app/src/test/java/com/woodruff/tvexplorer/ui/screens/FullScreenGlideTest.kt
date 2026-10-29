package com.woodruff.tvexplorer.ui.screens

import androidx.compose.runtime.BroadcastFrameClock
import com.woodruff.tvexplorer.ui.motion.TvMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camera full screen coming and going (FullScreenGlide), frame by frame. Its picture is a
 * SurfaceView, shown through a hole that erases whatever is drawn under it: it may only be drawn
 * (or stop being drawn) while the page and its black all but cover the screen, and the page may only
 * fade while it isn't drawn.
 */
class FullScreenGlideTest {
    private val glide = FullScreenGlide(shown = false, gridUnder = true)
    private val clock = BroadcastFrameClock()
    private var now = 0L
    private var job: Job? = null

    // What the last frame showed.
    private var wasLive = false
    private var lastPage = 0f

    @Test fun openingShowsThePageFirstAndThenThePicture(): Unit = runBlocking {
        start { glide.open() }
        frames(3)
        assertTrue(glide.shown)
        assertFalse(glide.live)
        assertTrue("the page is on its way in", glide.page.value > 0f)
        frames(60)
        assertTrue(glide.live)
        assertEquals(1f, glide.page.value, 0f)
        assertEquals(0f, glide.cover.value, 0f)
        // All in: the grid and the pills went under it.
        assertFalse(glide.gridUnder)
        assertTrue(cameraFullScreen.value)
        stop()
    }

    @Test fun closingDimsThePictureFirstAndThenThePageGoes(): Unit = runBlocking {
        start { glide.open() }
        frames(60)
        start { glide.close() }
        frames(1)
        // The grid and the pills are back under it at once.
        assertTrue(glide.gridUnder)
        assertFalse(cameraFullScreen.value)
        assertTrue(glide.live)
        assertEquals("the page waits for the picture to go", 1f, glide.page.value, 0f)
        frames(60)
        assertFalse(glide.live)
        assertFalse(glide.shown)
        assertEquals(0f, glide.page.value, 0f)
        stop()
    }

    @Test fun turningBackHalfWayKeepsTheRules(): Unit = runBlocking {
        // Back while it opens, OK again while it closes, at every point on the way.
        for (openFor in listOf(4, 12, 17, 22, 30)) {
            for (closeFor in listOf(3, 8, 12, 20)) {
                start { glide.open() }
                frames(openFor)
                start { glide.close() }
                frames(closeFor)
                start { glide.open() }
                frames(60)
                assertTrue(glide.live)
                assertEquals(1f, glide.page.value, 0f)
                assertEquals(0f, glide.cover.value, 0f)
                start { glide.close() }
                frames(60)
                assertFalse(glide.shown)
            }
        }
        stop()
    }

    @Test fun nothingOpenNothingToClose(): Unit = runBlocking {
        start { glide.close() }
        frames(2)
        assertFalse(glide.shown)
        assertTrue(glide.gridUnder)
        assertFalse(cameraFullScreen.value)
        stop()
    }

    @Test fun openedStraightOntoACameraThePillsGoAtOnce(): Unit = runBlocking {
        val straight = FullScreenGlide(shown = true, gridUnder = false)
        cameraFullScreen.value = false
        val run = launch(clock) { straight.open() }
        repeat(4) { yield() }
        assertTrue(cameraFullScreen.value)
        assertFalse(straight.live)
        run.cancel()
    }

    /** Runs [step] in place of what was running (as the screen's LaunchedEffect does when it's asked the other way). */
    private fun kotlinx.coroutines.CoroutineScope.start(step: suspend () -> Unit) {
        job?.cancel()
        job = launch(clock) { step() }
    }

    private fun stop() {
        job?.cancel()
        job = null
    }

    /** [n] frames at 60 a second, checking the rules after each. */
    private suspend fun frames(n: Int) {
        repeat(n) {
            repeat(4) { yield() }
            clock.sendFrame(now)
            now += 16_666_667L
            repeat(4) { yield() }
            check()
        }
    }

    private fun check() {
        val live = glide.live
        val page = glide.page.value
        val cover = glide.cover.value
        if (live) assertTrue("drawn under a page only $page in", page >= TvMotion.LiveSwap)
        if (live != wasLive) {
            // Shown or hidden: under a page and black that all but cover the screen, so it goes unseen.
            assertTrue("swapped at page $page", page >= TvMotion.LiveSwap)
            assertTrue("swapped at black $cover", cover >= TvMotion.LiveSwap)
        }
        // The page fades out only once the picture isn't drawn (its hole would show through it).
        if (page < lastPage) assertFalse("page fading at $page with the picture drawn", live)
        if (!live) assertTrue("black not over the picture: $cover", cover >= TvMotion.LiveSwap)
        assertTrue(cover in 0f..1f)
        wasLive = live
        lastPage = page
    }
}
