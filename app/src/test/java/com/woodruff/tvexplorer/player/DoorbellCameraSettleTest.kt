package com.woodruff.tvexplorer.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The doorbell view's picture as Left / Right go through the cameras: only the camera stopped on is
 * shown (each one passed started a stream of its own from Google, one every 2 s, and a few flicks could
 * reach Google's limit on camera streams); opening, or another doorbell ringing, shows its camera at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DoorbellCameraSettleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyTheCameraStoppedOnIsShownAndARingShowsItsCameraAtOnce() {
        var chosen by mutableStateOf("Front Door")
        var flicked by mutableStateOf(false)
        val shown = ArrayList<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val live = settledWhileFlicked(chosen, flicked, onSettled = { flicked = false }, settleMs = 400)
            SideEffect { if (shown.lastOrNull() != live) shown += live }
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("Front Door"), shown)

        // Right, Right, Right, a tenth of a second apart: the picture stays until the presses stop.
        for (camera in listOf("Backyard", "Garage", "Driveway")) {
            chosen = camera
            flicked = true
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(100)
        }
        assertEquals(listOf("Front Door"), shown)
        compose.mainClock.advanceTimeBy(500)
        assertEquals("the one stopped on, and none passed", listOf("Front Door", "Driveway"), shown)

        // Another doorbell rings while it's up: its camera at once.
        chosen = "Front Door"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(listOf("Front Door", "Driveway", "Front Door"), shown)
    }
}
