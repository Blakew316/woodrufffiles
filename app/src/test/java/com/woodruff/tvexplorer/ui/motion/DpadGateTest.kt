package com.woodruff.tvexplorer.ui.motion

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Held arrows: a repeat goes through only once the last move has been drawn and, for the TV's remote,
 * a minimum gap has passed; stale ones are dropped; single presses always go through. An arrow held
 * on the phone keeps the phone's own pace, which Wi-Fi bunches up a little.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DpadGateTest {
    private var downAt = 0L

    /** Time passes (and frames are drawn). */
    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun press(code: Int = KeyEvent.KEYCODE_DPAD_DOWN, repeat: Int = 0, age: Long = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        if (repeat == 0) downAt = now
        return KeyEvent(downAt, now - age, KeyEvent.ACTION_DOWN, code, repeat)
    }

    private fun phoneDrops(event: KeyEvent): Boolean {
        var dropped = false
        DpadGate.fromPhone(event) { dropped = DpadGate.shouldDrop(event) }
        return dropped
    }

    @Before fun aMomentSinceAnyMove() = pass(1_000)

    @Test fun singlePressesAlwaysGoThrough() {
        assertFalse(DpadGate.shouldDrop(press()))
        assertFalse(DpadGate.shouldDrop(press(KeyEvent.KEYCODE_DPAD_UP)))
        assertFalse(DpadGate.shouldDrop(press(KeyEvent.KEYCODE_DPAD_LEFT)))
    }

    @Test fun theRemotesRepeatsKeepTheirGap() {
        assertFalse(DpadGate.shouldDrop(press()))
        pass(100)
        // Drawn by now, but Up/Down keep 130 ms apart.
        assertTrue(DpadGate.shouldDrop(press(repeat = 1)))
        pass(40)
        assertFalse(DpadGate.shouldDrop(press(repeat = 2)))
    }

    @Test fun aStaleRepeatIsDropped() {
        assertFalse(DpadGate.shouldDrop(press()))
        pass(300)
        // It waited 150 ms for the main thread: the button may well be up by now.
        assertTrue(DpadGate.shouldDrop(press(repeat = 1, age = 150)))
    }

    @Test fun thePhonesHeldArrowKeepsItsOwnPace() {
        assertFalse(phoneDrops(press()))
        // The phone's repeats come every 140 ms; Wi-Fi brings this one in 100 ms after the last: it moves.
        pass(100)
        assertFalse(phoneDrops(press(repeat = 1)))
        pass(100)
        assertFalse(phoneDrops(press(repeat = 2)))
        // Several arriving at once still move once: the last move hasn't been drawn yet.
        assertTrue(phoneDrops(press(repeat = 3)))
        // And a stale one (the main thread was busy) is dropped, as the remote's are.
        pass(300)
        assertTrue(phoneDrops(press(repeat = 4, age = 150)))
    }

    @Test fun onlyThePressHandedInIsThePhones() {
        assertFalse(DpadGate.shouldDrop(press()))
        pass(100)
        // The phone handing in another press meanwhile doesn't make this repeat the phone's.
        val remote = press(repeat = 1)
        DpadGate.fromPhone(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {}
        assertTrue(DpadGate.shouldDrop(remote))
    }
}
