package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.player.FrameRateMatch.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which refresh rate a film gets: a whole multiple of its frame rate, at the same resolution. */
class FrameRateMatchTest {
    private val hd60 = Mode(1, 1920, 1080, 60f)
    // Rates as TVs report them: 23.976 and 59.94 aren't round numbers.
    private val modes = listOf(
        hd60, Mode(2, 1920, 1080, 59.94006f), Mode(3, 1920, 1080, 50f), Mode(4, 1920, 1080, 24f),
        Mode(5, 1920, 1080, 23.976025f), Mode(6, 3840, 2160, 60f), Mode(7, 3840, 2160, 24f),
    )

    private fun pick(fps: Float, current: Mode = hd60, all: List<Mode> = modes) = FrameRateMatch.pick(all, current, fps)?.id

    @Test fun filmsGetTheirOwnRate() {
        assertEquals(5, pick(23.976024f))
        assertEquals(4, pick(24f))
        assertEquals(3, pick(25f))
    }

    @Test fun theResolutionStays() = assertEquals(7, pick(24f, current = Mode(6, 3840, 2160, 60f)))

    @Test fun nothingToDoWhenTheRateAlreadyFits() {
        assertNull(pick(30f)) // twice 30 is 60
        assertNull(pick(60f))
        assertNull(pick(24f, current = Mode(4, 1920, 1080, 24f)))
    }

    @Test fun theLowestWholeMultiple() {
        val highRates = listOf(hd60, Mode(8, 1920, 1080, 120f), Mode(9, 1920, 1080, 48f))
        assertEquals(9, pick(24f, all = highRates))
        assertEquals(4, pick(24f, all = highRates + Mode(4, 1920, 1080, 24f)))
    }

    @Test fun aNearRateOnlyWhenThereIsNoExactOne() {
        val no23976 = listOf(hd60, Mode(4, 1920, 1080, 24f))
        assertEquals(4, pick(23.976024f, all = no23976))
        assertNull(pick(29.97003f, all = no23976)) // 60 Hz is already that close
        assertEquals(2, pick(29.97003f)) // but 59.94 is exact
    }

    @Test fun nothingWhenNoModeFitsOrTheRateIsUnknown() {
        assertNull(pick(24f, all = listOf(hd60)))
        assertNull(pick(-1f)) // Format.NO_VALUE
        assertNull(pick(Float.NaN))
    }
}
