package com.woodruff.tvexplorer.player

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.ui.motion.AppMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import kotlin.math.abs

/** The loading ring over the film: where it sits, what it says, and that it only turns while it's up. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class BufferingIndicatorTest {
    private lateinit var root: FrameLayout
    private lateinit var indicator: BufferingIndicator
    private val layer get() = root.getChildAt(0) as ViewGroup
    private val disc get() = layer.getChildAt(0) as ViewGroup
    private val caption get() = layer.getChildAt(1) as TextView
    private val ring get() = disc.getChildAt(0) as BufferingRing
    private val density get() = root.resources.displayMetrics.density

    @Before fun setUp() {
        // Frames come every 16 ms of the test's clock, as on a TV (see [frames]). Left to itself,
        // Robolectric moves its clock on each time a frame is asked for, and a ring that turns for as
        // long as it's up would never let the looper go idle.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        root = FrameLayout(activity)
        indicator = BufferingIndicator(activity, root)
        activity.setContentView(root)
        settle()
    }

    @After fun motionBack() {
        AppMotion.reduced = false
    }

    @Test fun nothingShowsUntilAsked() {
        assertEquals(View.GONE, layer.visibility)
        assertFalse(ring.spinning)
    }

    @Test fun theRingSitsInTheMiddleAndStaysThereWhenWordsComeUnderIt() {
        indicator.show(BufferingLook(ring = true, caption = null))
        settle()
        // Glided in: full size, fully there.
        assertEquals(1f, layer.scaleX)
        assertEquals(1f, layer.alpha)
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals(View.GONE, caption.visibility)
        val centre = centreOf(disc)
        assertEquals(root.width / 2f, centre.first, 1f)
        assertEquals(root.height / 2f, centre.second, 1f)
        assertEquals(56 * density, disc.width.toFloat(), 1f)
        assertEquals(28 * density, ring.width.toFloat(), 1f)

        indicator.show(BufferingLook(ring = true, caption = "Reconnecting…"))
        settle()
        assertEquals(centre, centreOf(disc))
        assertEquals(View.VISIBLE, caption.visibility)
        assertEquals("Reconnecting…", caption.text.toString())
        // 12 dp under the disc, centred under it.
        assertEquals(disc.bottom + 12 * density, caption.top.toFloat(), 1f)
        assertEquals(centre.first, centreOf(caption).first, 1f)
    }

    @Test fun theLongestWordsReadAsWhatsHappeningThenWhatToDo() {
        indicator.show(Buffering.look(null, Stall.NETWORK, stalledMs = 31_000, reduced = false)!!)
        settle()
        assertEquals(2, caption.lineCount)
        assertTrue(caption.width <= 420 * density + 1)
        val text = caption.text.toString()
        val lines = (0 until caption.lineCount).map { text.substring(caption.layout.getLineStart(it), caption.layout.getLineEnd(it)).trim() }
        assertEquals(listOf("Still reconnecting.", "Check the TV’s internet connection."), lines)
    }

    @Test fun aSignInThatEndedIsACardAloneInTheMiddle() {
        indicator.show(Buffering.look(null, Stall.SIGNED_OUT, 0, reduced = false)!!)
        settle()
        assertEquals(View.GONE, disc.visibility)
        assertFalse(ring.spinning)
        assertEquals(root.height / 2f, centreOf(caption).second, 1f)
        assertEquals("Sign in to Google Drive again\nOpen Streaming on this TV and scan the code with your phone.", caption.text.toString())
    }

    @Test fun theRingTurnsOnlyWhileItsUp() {
        indicator.show(BufferingLook(ring = true, caption = null))
        assertTrue(ring.spinning)
        frames(120)
        val a = ring.rotation
        frames(240)
        val b = ring.rotation
        assertNotEquals(a, b, 0.01f)
        assertTrue(b in 0f..360f)
        // A turn a second: about a third of a turn in those 360 ms.
        assertEquals(360f * 0.36f, b, 40f)

        indicator.hide()
        // On its way out it still turns; once it's gone, nothing runs.
        settle()
        assertEquals(View.GONE, layer.visibility)
        assertFalse(ring.spinning)
    }

    @Test fun backBeforeItsGoneKeepsItTurning() {
        indicator.show(BufferingLook(ring = true, caption = null))
        settle()
        indicator.hide()
        indicator.show(BufferingLook(ring = true, caption = "Reconnecting…"))
        settle()
        assertEquals(View.VISIBLE, layer.visibility)
        assertTrue(ring.spinning)
    }

    @Test fun withReduceMotionTheRingStandsStill() {
        AppMotion.reduced = true
        indicator.show(BufferingLook(ring = true, caption = Buffering.LOADING))
        settle()
        assertFalse(ring.spinning)
        assertEquals(0f, ring.rotation)
    }

    @Test fun goneAtOnceWhenThePlayerStops() {
        indicator.show(BufferingLook(ring = true, caption = "Reconnecting…"))
        settle()
        indicator.hide(animate = false)
        assertEquals(View.GONE, layer.visibility)
        assertFalse(ring.spinning)
    }

    @Test fun itNeverTakesFocus() {
        indicator.show(BufferingLook(ring = true, caption = "Reconnecting…"))
        settle()
        for (v in listOf(layer, disc, ring, caption)) assertFalse(v.isFocusable)
    }

    /** Where [v]'s middle is laid out on the screen (the layer fills it from its top left). */
    private fun centreOf(v: View): Pair<Float, Float> = (layer.left + v.left + v.width / 2f) to (layer.top + v.top + v.height / 2f)

    /** Lets [ms] of frames go by, one every 16 ms (with the clock paused, each wait gives one frame). */
    private fun frames(ms: Long) {
        repeat((ms / 16).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun settle() = frames(800)
}
