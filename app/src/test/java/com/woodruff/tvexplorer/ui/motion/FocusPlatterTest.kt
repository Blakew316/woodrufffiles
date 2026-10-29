package com.woodruff.tvexplorer.ui.motion

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The focus platter: when it glides, shows up or comes back, where it is on the way, and its spring. */
class FocusPlatterTest {
    private val first = Any()
    private val second = Any()

    @Test fun focusComingIntoTheListShowsItOnThatRow() {
        assertEquals(PlatterMove.Appear, platterMove(onRow = null, leaving = false, shown = 0f, row = first))
        // It had faded right out where the focus left: it shows up on the row, never glides from there.
        assertEquals(PlatterMove.Appear, platterMove(onRow = first, leaving = true, shown = 0f, row = second))
        assertEquals(PlatterMove.Appear, platterMove(onRow = first, leaving = true, shown = 0f, row = first))
    }

    @Test fun focusMovingWithinTheListGlides() {
        assertEquals(PlatterMove.Glide, platterMove(onRow = first, leaving = false, shown = 1f, row = second))
        // Still showing up on the first row: it glides on from there.
        assertEquals(PlatterMove.Glide, platterMove(onRow = first, leaving = false, shown = 0.3f, row = second))
        assertEquals(PlatterMove.Stay, platterMove(onRow = first, leaving = false, shown = 1f, row = first))
    }

    @Test fun focusComingBackWhileItFadesOut() {
        // To the row it's fading out on: it fades back in there.
        assertEquals(PlatterMove.Return, platterMove(onRow = first, leaving = true, shown = 0.5f, row = first))
        // To another row: it shows up there (the old one finishes fading where it was).
        assertEquals(PlatterMove.Appear, platterMove(onRow = first, leaving = true, shown = 0.5f, row = second))
    }

    @Test fun onTheWayItIsBetweenTheTwoRows() {
        val from = Rect(0f, 0f, 400f, 56f)
        val to = Rect(0f, 64f, 400f, 120f)
        assertSame(to, glidingRect(null, to, 0.3f))
        assertRectEquals(from, glidingRect(from, to, 0f))
        assertRectEquals(to, glidingRect(from, to, 1f))
        assertRectEquals(Rect(0f, 32f, 400f, 88f), glidingRect(from, to, 0.5f))
        // The spring's overshoot carries it a touch past the row, and back.
        assertRectEquals(Rect(0f, 64.32f, 400f, 120.32f), glidingRect(from, to, 1.005f))
    }

    @Test fun itFollowsTheListAsItScrolls() {
        // Both ends are where those rows are now: the list scrolling 40 px moves it 40 px, mid-glide too.
        val from = Rect(0f, 0f, 400f, 56f)
        val to = Rect(0f, 64f, 400f, 120f)
        val scrolled = Offset(0f, -40f)
        for (g in listOf(0f, 0.25f, 0.8f, 1f)) {
            val moved = glidingRect(from.translate(scrolled), to.translate(scrolled), g)
            assertRectEquals(glidingRect(from, to, g).translate(scrolled), moved)
        }
    }

    @Test fun itGrowsAboutItsCentre() {
        val r = Rect(0f, 0f, 100f, 50f)
        assertSame(r, r.grownBy(1f))
        assertRectEquals(Rect(-5f, -2.5f, 105f, 52.5f), r.grownBy(1.1f))
        assertRectEquals(Rect(3f, 1.5f, 97f, 48.5f), r.grownBy(TvMotion.PlatterAppearScale))
    }

    @Test fun aNewGlideCarriesOnItsSpeed() {
        val from = Rect(0f, 0f, 100f, 50f)
        val to = Rect(0f, 60f, 100f, 110f)
        // Moving down at 120 px/s toward a row 60 px down: 2 glides a second.
        assertEquals(2f, carriedGlideSpeed(from, to, 0f, 120f), 0.0001f)
        // Moving the other way: it starts by curving back.
        assertEquals(-2f, carriedGlideSpeed(from, to, 0f, -120f), 0.0001f)
        // Only the motion along the new way counts.
        assertEquals(0f, carriedGlideSpeed(from, to, 300f, 0f), 0.0001f)
        assertEquals(2f, carriedGlideSpeed(from, to, 300f, 120f), 0.0001f)
        // Already there, or flung absurdly fast: no carried speed, or a bounded one.
        assertEquals(0f, carriedGlideSpeed(from, from, 0f, 120f), 0f)
        assertEquals(20f, carriedGlideSpeed(from, to, 0f, 1_000_000f), 0f)
    }

    @Test fun theGlideTakesAboutAFifthOfASecondWithASoftSettle() {
        val s = SpringValue(0f).apply { animateTo(1f, TvMotion.GlideDamping, TvMotion.GlideStiffness) }
        var ms = 0
        var ninety = -1
        var lastOutside = 0
        var peak = 0f
        while (!s.atRest && ms < 2000) {
            s.step(0.001f)
            ms++
            if (ninety < 0 && s.value >= 0.9f) ninety = ms
            if (kotlin.math.abs(s.value - 1f) > 0.01f) lastOutside = ms
            peak = maxOf(peak, s.value)
        }
        assertTrue("90% at $ninety ms", ninety in 125..150)
        assertTrue("within 1% from $lastOutside ms", lastOutside in 180..215)
        // A soft settle: past it by half a percent, never more than one.
        assertTrue("peak $peak", peak > 1.003f && peak < 1.01f)
        assertTrue("at rest at $ms ms", s.atRest && ms < 500)
        assertEquals(1f, s.value, 0f)
    }

    @Test fun itMovesOnComposesOwnSpring() {
        // The platter's spring and the rows' (TvMotion.Glide, through Compose) move as one. (Compose
        // reads its spring at whole milliseconds, so frames of 16 here.)
        val compose = TvMotion.Glide.vectorize(Float.VectorConverter)
        val s = SpringValue(0f).apply { animateTo(1f, TvMotion.GlideDamping, TvMotion.GlideStiffness) }
        for (frame in 1..30) {
            s.step(0.016f)
            val nanos = frame * 16_000_000L
            val expected = compose.getValueFromNanos(nanos, AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)).value
            assertEquals("frame $frame", expected, s.value, 0.001f)
        }
    }

    @Test fun showingAndHidingNeverOvershoot() {
        for ((from, to, stiffness) in listOf(Triple(0f, 1f, TvMotion.PlatterInStiffness), Triple(1f, 0f, TvMotion.PlatterOutStiffness))) {
            val s = SpringValue(from).apply { animateTo(to, 1f, stiffness) }
            var previous = from
            while (!s.atRest) {
                s.step(1f / 60f)
                assertTrue("${s.value} after $previous", if (to > from) s.value >= previous && s.value <= to else s.value <= previous && s.value >= to)
                previous = s.value
            }
            assertEquals(to, s.value, 0f)
        }
    }

    @Test fun aSlowFrameLandsItWhereItWouldHaveBeen() {
        val oneStep = SpringValue(0f).apply { animateTo(1f, TvMotion.GlideDamping, TvMotion.GlideStiffness) }
        val manySteps = SpringValue(0f).apply { animateTo(1f, TvMotion.GlideDamping, TvMotion.GlideStiffness) }
        oneStep.step(0.1f)
        repeat(100) { manySteps.step(0.001f) }
        assertEquals(manySteps.value, oneStep.value, 0.0001f)
        assertEquals(manySteps.velocity, oneStep.velocity, 0.001f)
    }

    @Test fun retargetingKeepsItsSpeed() {
        val s = SpringValue(0f).apply { animateTo(1f, TvMotion.GlideDamping, TvMotion.GlideStiffness) }
        repeat(5) { s.step(1f / 60f) }
        val value = s.value
        val velocity = s.velocity
        assertTrue(velocity > 0f)
        s.animateTo(2f, TvMotion.GlideDamping, TvMotion.GlideStiffness)
        assertEquals(value, s.value, 0f)
        assertEquals(velocity, s.velocity, 0f)
        // ...and carries on the same way, curving into the new target.
        s.step(1f / 60f)
        assertTrue(s.value > value)
    }

    @Test fun snappingAndFinishing() {
        val s = SpringValue(1f)
        assertTrue(s.atRest)
        s.snapTo(0f, speed = 3f)
        assertEquals(0f, s.value, 0f)
        assertEquals(3f, s.velocity, 0f)
        assertEquals(1f, s.target, 0f)
        s.finish()
        assertTrue(s.atRest)
        assertEquals(1f, s.value, 0f)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }
}
