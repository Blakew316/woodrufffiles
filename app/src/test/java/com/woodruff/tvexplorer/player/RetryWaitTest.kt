package com.woodruff.tvexplorer.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stalled film is tried again the moment the network is back, or after the wait, whichever is first. */
class RetryWaitTest {
    private val comebacks = MutableStateFlow(0)

    @Test fun withNoNewsFromTheNetworkItTriesAfterTheWait() = runBlocking {
        var tries = 0
        val wait = RetryWait(this, comebacks, delayMs = 300) { tries++ }
        wait.arm()
        assertTrue(wait.waiting)
        delay(150)
        assertEquals(0, tries)
        delay(400)
        assertEquals(1, tries)
        assertFalse(wait.waiting)
        // One try per arm: nothing more by itself.
        delay(500)
        assertEquals(1, tries)
    }

    @Test fun theNetworkComingBackTriesAtOnce() = runBlocking {
        var tries = 0
        val wait = RetryWait(this, comebacks, delayMs = 60_000) { tries++ }
        wait.arm()
        delay(50)
        assertEquals(0, tries)
        comebacks.value++ // the Wi-Fi rejoined
        delay(50)
        assertEquals(1, tries)
        assertFalse(wait.waiting)
    }

    @Test fun aComebackBeforeTheStallIsNotTakenForOne() = runBlocking {
        var tries = 0
        comebacks.value = 7
        val wait = RetryWait(this, comebacks, delayMs = 300) { tries++ }
        wait.arm()
        delay(100)
        assertEquals(0, tries)
        wait.stop()
    }

    @Test fun armingAgainStartsTheWaitAfresh() = runBlocking {
        var tries = 0
        val wait = RetryWait(this, comebacks, delayMs = 400) { tries++ }
        wait.arm()
        delay(250)
        // The try failed and stalled again: a fresh wait, still one try.
        wait.arm()
        delay(250)
        assertEquals(0, tries)
        delay(350)
        assertEquals(1, tries)
    }

    @Test fun stoppedItNeverTries() = runBlocking {
        var tries = 0
        val wait = RetryWait(this, comebacks, delayMs = 200) { tries++ }
        wait.arm()
        wait.stop()
        assertFalse(wait.waiting)
        comebacks.value++
        delay(400)
        assertEquals(0, tries)
    }
}
