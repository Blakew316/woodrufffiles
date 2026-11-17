package com.woodruff.tvexplorer.ui

import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.NestStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A doorbell ring on the Command Center's cameras opens that camera there, in place, only when it can be seen:
 * otherwise (a pop-up over them, their sign-in ended) the doorbell's own view opens over everything.
 */
class RingsInPlaceTest {
    private val door = Camera("enterprises/p/devices/door", "Front Door", "Porch", webRtc = true, doorbell = true)
    private val connected = NestState(step = NestStep.CONNECTED, cameras = listOf(door))

    @Test fun onTheCamerasWithNothingOverThemItOpensInPlace() {
        assertTrue(ringsInPlace(Screen.CAMERAS, connected, listening = true, popUp = false))
    }

    @Test fun underSearchATitlePageOrTheUpdatePromptTheDoorbellsOwnViewOpens() {
        assertFalse(ringsInPlace(Screen.CAMERAS, connected, listening = true, popUp = true))
    }

    @Test fun withTheSignInEndedAndTheCamerasKeptTheDoorbellsOwnViewOpens() {
        assertFalse(ringsInPlace(Screen.CAMERAS, connected.copy(step = NestStep.SIGN_IN), listening = true, popUp = false))
    }

    @Test fun elsewhereOrBeforeTheCamerasAreUpTheDoorbellsOwnViewOpens() {
        assertFalse(ringsInPlace(Screen.HOME, connected, listening = true, popUp = false))
        assertFalse(ringsInPlace(Screen.MAIL, connected, listening = true, popUp = false))
        assertFalse(ringsInPlace(Screen.CAMERAS, connected, listening = false, popUp = false))
        assertFalse(ringsInPlace(Screen.CAMERAS, connected.copy(cameras = emptyList()), listening = true, popUp = false))
    }
}
