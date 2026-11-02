package com.woodruff.tvexplorer.player

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.ui.TimeBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * One press back is instant: the scrub bar aims from where the film really is, and the spot it
 * plays keeps its distance from the film, which goes on playing while the spot is chosen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TvTimeBarScrubTest {
    private lateinit var bar: TvTimeBar
    private val events = mutableListOf<String>()
    /** Where the film is "now" (the player's own position). */
    private var live: Long? = 1_200_900L

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        bar = TvTimeBar(activity)
        activity.setContentView(bar)
        bar.setDuration(3_600_000)
        // The bar was last told 20:00.000: Media3 refreshes it about once a second.
        bar.setPosition(1_200_000)
        bar.livePosition = { live }
        bar.onLanding = { events += "landing $it" }
        bar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { events += "start $position" }
            override fun onScrubMove(timeBar: TimeBar, position: Long) { events += "move $position" }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                events += if (canceled) "dropped" else "stop $position"
            }
        })
    }

    private fun press(keyCode: Int, repeat: Int = 0) =
        bar.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, repeat))

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun aScrubStartsFromWhereTheFilmReallyIs() {
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(listOf("start 1200900", "move 1190900"), events)
        assertTrue(bar.isScrubbing)
    }

    @Test fun onePressBackWhilePlayingLandsTenSecondsBehindTheFilm() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        // The film plays on while the spot waits to be played (a moment after the last press).
        live = 1_202_100L
        pass(1_100)
        assertTrue("not played before the pause after the last press", bar.isScrubbing)
        pass(200)
        assertFalse(bar.isScrubbing)
        // Told where it lands first (the player gets ready for that seek), then the seek.
        assertEquals(listOf("start 1200900", "move 1190900", "landing 1192100", "stop 1192100"), events)
    }

    @Test fun pausedThePressedSpotIsPlayed() {
        live = 1_200_000L
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        pass(1_300)
        assertEquals("stop 1190000", events.last())
    }

    @Test fun okPlaysTheSpotAtOnce() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        live = 1_201_300L
        assertTrue(press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("landing 1191300", "stop 1191300"), events.takeLast(2))
    }

    @Test fun severalPressesKeepTheirDistanceFromTheFilm() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        pass(400)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        pass(400)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        // 30 s back from where the film started, and it played 2 s meanwhile.
        live = 1_202_900L
        pass(1_300)
        assertEquals("stop 1172900", events.last())
    }

    @Test fun leftThenRightPlaysNothing() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        live = 1_202_100L
        pass(1_300)
        assertFalse(bar.isScrubbing)
        // Media3's controller doesn't seek for a scrub that ends canceled: the film just carries on.
        assertEquals("dropped", events.last())
        assertTrue(events.none { it.startsWith("landing") })
    }

    @Test fun backGoesBackWithoutPlayingAnything() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(bar.cancelScrub())
        assertEquals("dropped", events.last())
        assertFalse(bar.cancelScrub())
        assertTrue(events.none { it.startsWith("landing") })
    }

    @Test fun scrubbedToTheStartStaysAtTheStart() {
        live = 6_000L
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        live = 7_200L
        pass(1_300)
        assertEquals("stop 0", events.last())
    }

    @Test fun withoutAPlayerItScrubsFromItsOwnPosition() {
        bar.livePosition = null
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        pass(1_300)
        assertEquals(listOf("start 1200000", "move 1190000", "landing 1190000", "stop 1190000"), events)
    }

    @Test fun anUnknownLivePositionFallsBackToTheBars() {
        live = null
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("start 1200000", events.first())
        live = 1_201_000L
        pass(1_300)
        // Its start wasn't known, so there's nothing to measure the film's progress from.
        assertEquals("stop 1190000", events.last())
        assertNull(events.firstOrNull { it == "dropped" })
    }
}
