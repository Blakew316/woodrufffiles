package com.woodruff.tvexplorer.ui.motion

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.util.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A key pressed in a dialog window (a title page, a menu, the filters) never reaches an activity: it
 * counts as use through the gate every Dialog root has ([dpadGate]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DialogKeysUseTest {
    @get:Rule val compose = createComposeRule()
    private var now = 70_000_000L

    @Before fun fakeClock() {
        Presence.clock = { now }
        Presence.resetForTest()
    }

    @After fun realClock() {
        Presence.resetForTest()
        Presence.clock = { android.os.SystemClock.elapsedRealtime() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aKeyInADialogCountsAsUse() {
        compose.setContent {
            Dialog(onDismissRequest = {}) {
                val focus = remember { FocusRequester() }
                Box(Modifier.dpadGate()) {
                    Box(Modifier.size(40.dp).testTag("row").focusRequester(focus).focusable())
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        compose.waitForIdle()
        now += 45 * 60_000L
        compose.onNodeWithTag("row").performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(0, Presence.sinceUseMs())
    }
}
