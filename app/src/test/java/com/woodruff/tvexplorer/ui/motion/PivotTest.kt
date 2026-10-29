package com.woodruff.tvexplorer.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scrolling to the focus keeps the focused item at a fixed spot, eased (not cut off) at a list's ends. */
class PivotTest {
    private val inf = Float.POSITIVE_INFINITY

    @Test fun theFocusedItemMovesToThePivot() {
        // Pivot 18 px in: an item at 500 scrolls by 482 to sit there.
        assertEquals(482f, pivotDistance(500f, 200f, 1000f, 0f, 18f, inf, inf), 0.001f)
        // Already there: no scroll.
        assertEquals(0f, pivotDistance(18f, 200f, 1000f, 0f, 18f, inf, inf), 0.001f)
    }

    @Test fun thePivotCanBeAShareOfTheView() {
        assertEquals(0f, pivotDistance(400f, 60f, 1000f, 0.4f, 0f, inf, inf), 0.001f)
        assertEquals(-300f, pivotDistance(100f, 60f, 1000f, 0.4f, 0f, inf, inf), 0.001f)
    }

    @Test fun nearTheEndsItScrollsOnlyAsFarAsItCan() {
        assertEquals(100f, pivotDistance(500f, 200f, 1000f, 0f, 18f, 100f, inf), 0.001f)
        assertEquals(-50f, pivotDistance(-200f, 200f, 1000f, 0f, 18f, inf, 50f), 0.001f)
        // Nothing left to scroll either way.
        assertEquals(0f, pivotDistance(500f, 200f, 1000f, 0f, 18f, 0f, 0f), 0.001f)
    }

    @Test fun anItemTooBigForThePivotEndsAtTheFarEdge() {
        // 900 px after a 200 px pivot doesn't fit: it ends at 1000 instead (starts at 100).
        assertEquals(200f, pivotDistance(300f, 900f, 1000f, 0.2f, 0f, inf, inf), 0.001f)
        // Bigger than the view: it starts at the near edge.
        assertEquals(300f, pivotDistance(300f, 1500f, 1000f, 0.2f, 0f, inf, inf), 0.001f)
    }
}
