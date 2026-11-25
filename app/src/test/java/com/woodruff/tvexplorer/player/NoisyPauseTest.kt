package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** A film paused because its sound output left plays again when that output is back, and only then. */
class NoisyPauseTest {
    private val hdmi = 9          // AudioDeviceInfo.TYPE_HDMI
    private val speaker = 2       // TYPE_BUILTIN_SPEAKER
    private val bluetooth = 8     // TYPE_BLUETOOTH_A2DP

    private fun paused(vararg outputs: Int) = NoisyPause.Paused(at = 1_000, usedAt = 500, outputs = outputs.toSet(), item = "film")

    @Test fun aSoundbarReadingTheConnectionAgainStartsTheFilmWhenItsBack() {
        val p = paused(hdmi)
        // Still settling: not looked at yet, whatever is listed.
        assertEquals(NoisyPause.Step.WAIT, NoisyPause.step(p, setOf(hdmi), 500, takenOver = false))
        // HDMI gone: waited for.
        assertEquals(NoisyPause.Step.WAIT, NoisyPause.step(p, emptySet(), 3_000, takenOver = false))
        // Back: it plays.
        assertEquals(NoisyPause.Step.PLAY, NoisyPause.step(p, setOf(hdmi), 6_000, takenOver = false))
    }

    @Test fun anOutputThatLeftAndCameBackBeforeTheFirstLookStartsTheFilm() {
        assertEquals(NoisyPause.Step.PLAY, NoisyPause.step(paused(hdmi, speaker), setOf(speaker, hdmi), 2_000, takenOver = false))
    }

    @Test fun headphonesThatStayOffLeaveItPaused() {
        val p = paused(bluetooth, hdmi)
        // The TV's own output is still there, but the headphones are what left: not started through the TV.
        assertEquals(NoisyPause.Step.WAIT, NoisyPause.step(p, setOf(hdmi), 5_000, takenOver = false))
        assertEquals(NoisyPause.Step.LEAVE, NoisyPause.step(p, setOf(hdmi), NoisyPause.WITHIN_MS + 1, takenOver = false))
        // Back within the time (a Bluetooth soundbar that dropped for a moment): it plays.
        assertEquals(NoisyPause.Step.PLAY, NoisyPause.step(p, setOf(hdmi, bluetooth), 9_000, takenOver = false))
    }

    @Test fun aTvSwitchedOffLeavesItPaused() {
        assertEquals(NoisyPause.Step.LEAVE, NoisyPause.step(paused(hdmi), emptySet(), NoisyPause.WITHIN_MS + 1, takenOver = false))
    }

    @Test fun someoneUsingTheRemoteMeanwhileTakesOver() {
        assertEquals(NoisyPause.Step.LEAVE, NoisyPause.step(paused(hdmi), setOf(hdmi), 4_000, takenOver = true))
    }

    @Test fun withNothingKnownOfWhatWasThereItStaysPaused() {
        assertEquals(NoisyPause.Step.LEAVE, NoisyPause.step(paused(), setOf(hdmi), 4_000, takenOver = false))
    }
}
