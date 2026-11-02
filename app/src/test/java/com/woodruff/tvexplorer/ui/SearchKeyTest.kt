package com.woodruff.tvexplorer.ui

import android.os.Looper
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The remote's Search button asks for Search once a press, and none of it reaches Android's own handling. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchKeyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun stop() = scope.cancel()

    @Test fun oncePerPressAndAllOfItTaken() {
        var presses = 0
        scope.launch(start = CoroutineStart.UNDISPATCHED) { SearchKey.presses.collect { presses++ } }
        for (code in listOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST)) {
            assertTrue(SearchKey.take(KeyEvent(KeyEvent.ACTION_DOWN, code)))
            // Held: its repeats ask for nothing more, but are taken too.
            assertTrue(SearchKey.take(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 3)))
            assertTrue(SearchKey.take(KeyEvent(KeyEvent.ACTION_UP, code)))
            // (Each press comes in a message of its own, after the last was handled.)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(2, presses)
    }

    @Test fun otherKeysAreLeftAlone() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_S)) {
            assertFalse(SearchKey.take(KeyEvent(KeyEvent.ACTION_DOWN, code)))
        }
    }
}
