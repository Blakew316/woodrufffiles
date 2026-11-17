package com.woodruff.tvexplorer.ui.motion

import android.os.Looper
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A list's gliding highlight follows Settings → Appearance → Reduce motion as everything else does: with it on, the
 * highlight is on the focused row at once, under its dark words; with it off, it glides there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlatterReducedMotionTest {
    @get:Rule val compose = createComposeRule()

    @After fun motionBack() { AppMotion.reduced = false }

    private val platter = FocusPlatter()
    private val rows = List(3) { FocusRequester() }
    private val tops = HashMap<Int, Float>()
    private var focused = -1

    /** Frames for [steps] × 20 ms (Robolectric's main looper runs between them, as the TV's would). */
    private fun settle(steps: Int = 30) = repeat(steps) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        compose.mainClock.advanceTimeBy(20)
        compose.waitForIdle()
    }

    private fun list() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(Modifier.focusPlatterHost(platter, RectangleShape, Color.White)) {
                rows.forEachIndexed { i, focus ->
                    Box(
                        Modifier.size(200.dp, 40.dp)
                            .onGloballyPositioned { tops[i] = it.positionInParent().y }
                            .focusPlatterItem(platter)
                            .focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) focused = i else if (focused == i) focused = -1 }
                            .focusable(),
                    )
                }
            }
        }
        settle()
        compose.runOnIdle { rows[0].requestFocus() }
        settle(60)
        compose.runOnIdle {
            assertEquals(0, focused)
            assertFalse(platter.isMoving)
            assertEquals("on the first row", tops.getValue(0), platter.drawnAt!!.top, 0.5f)
        }
    }

    /** The focus to row [i]: whether the highlight is still on its way the moment it moved, and where it shows then. */
    private fun focus(i: Int): Pair<Boolean, Rect?> {
        var after: Pair<Boolean, Rect?> = true to null
        compose.runOnIdle {
            rows[i].requestFocus()
            after = platter.isMoving to platter.drawnAt
        }
        return after
    }

    @Test fun withReduceMotionItIsOnTheFocusedRowAtOnce() {
        AppMotion.reduced = true
        list()
        val (moving, at) = focus(2)
        assertFalse(moving)
        assertNotNull(at)
        assertEquals(tops.getValue(2), at!!.top, 0.5f)
    }

    @Test fun otherwiseItGlidesThere() {
        list()
        val (moving, _) = focus(2)
        // On its way, over the frames to come.
        assertTrue(moving)
        settle(60)
        compose.runOnIdle {
            assertFalse(platter.isMoving)
            assertEquals(tops.getValue(2), platter.drawnAt!!.top, 0.5f)
        }
    }
}
