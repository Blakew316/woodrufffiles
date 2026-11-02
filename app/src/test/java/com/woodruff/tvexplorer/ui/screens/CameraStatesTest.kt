package com.woodruff.tvexplorer.ui.screens

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.nest.CameraStream.Status
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a camera view shows besides its live picture (finding 37): live only while frames come, the
 * words while it connects or reconnects, and the veil over a picture that has stopped.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CameraStatesTest {
    @get:Rule val compose = createComposeRule()

    private val picture = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).asImageBitmap()

    @After fun noSamples() { CameraSamples.views = null }

    @Test fun theWordsFollowTheStreamNeverItsConnection() {
        assertNull(streamWords(Status.Live, long = false))
        assertEquals("Connecting…", streamWords(Status.Connecting, long = false))
        assertEquals("Reconnecting…", streamWords(Status.Stalled(1), long = false))
        assertEquals("Reconnecting…", streamWords(Status.Reconnecting(1), long = false))
        assertEquals("Can’t reach this camera right now", streamWords(Status.Reconnecting(1), long = true))
        assertEquals("Can’t reach this camera right now", streamWords(Status.Stalled(1), long = true))
    }

    @Test fun liveOnlyWhileFramesComeAndAHeldPictureOnlyInTrouble() {
        assertEquals(CameraPicture.LIVE, cameraPicture(Status.Live, held = true))
        // Full screen: live with its stream, so the caption's scrim stays put through a switch of camera.
        assertEquals(CameraPicture.LIVE, cameraPicture(Status.Live, held = false))
        // A tile joining a stream that's already live: LIVE only once it has drawn its own picture.
        assertEquals(CameraPicture.NONE, cameraPicture(Status.Live, held = false, tile = true))
        assertEquals(CameraPicture.LIVE, cameraPicture(Status.Live, held = true, tile = true))
        assertEquals(CameraPicture.HELD, cameraPicture(Status.Stalled(5), held = true, tile = true))
        assertEquals(CameraPicture.HELD, cameraPicture(Status.Stalled(5), held = true))
        assertEquals(CameraPicture.HELD, cameraPicture(Status.Reconnecting(5), held = true))
        assertEquals(CameraPicture.NONE, cameraPicture(Status.Reconnecting(5), held = false))
        // Connecting again with an old picture in the view: not live, and not a picture to read words over.
        assertEquals(CameraPicture.NONE, cameraPicture(Status.Connecting, held = true))
    }

    @Test fun whatGoesOverAViewFollowsItsStreamAndWhatItHasDrawn() {
        // Live, and drawn: nothing over it.
        assertEquals(Overlay.NONE, cameraOverlay(Status.Live, held = true, hasStill = true, big = false))
        assertEquals(Overlay.NONE, cameraOverlay(Status.Live, held = true, hasStill = false, big = true))
        // A tile joining a live stream keeps its last still until its own first frame, not black.
        assertEquals(Overlay.STILL, cameraOverlay(Status.Live, held = false, hasStill = true, big = false))
        assertEquals(Overlay.NONE, cameraOverlay(Status.Live, held = false, hasStill = false, big = false))
        assertEquals(Overlay.NONE, cameraOverlay(Status.Live, held = false, hasStill = true, big = true))
        // A stopped picture: held under the veil (full screen and tiles alike).
        assertEquals(Overlay.VEIL, cameraOverlay(Status.Stalled(5), held = true, hasStill = true, big = false))
        assertEquals(Overlay.VEIL, cameraOverlay(Status.Reconnecting(5), held = true, hasStill = false, big = true))
        // Connecting: full screen on black (an old picture could be taken for the visitor); a tile over its still.
        assertEquals(Overlay.WORDS, cameraOverlay(Status.Connecting, held = true, hasStill = false, big = true))
        assertEquals(Overlay.VEIL, cameraOverlay(Status.Connecting, held = true, hasStill = true, big = false))
        assertEquals(Overlay.STILL_VEIL, cameraOverlay(Status.Connecting, held = false, hasStill = true, big = false))
        assertEquals(Overlay.PLACEHOLDER, cameraOverlay(Status.Reconnecting(5), held = false, hasStill = false, big = false))
    }

    @Test fun aLiveViewHasNothingOverItsPicture() {
        compose.setContent { TvExplorerTheme { CameraOverlay(Status.Live, longTrouble = false, held = true, still = picture, big = true) } }
        compose.onAllNodesWithText("Connecting…").assertCountEquals(0)
        compose.onAllNodesWithText("Reconnecting…").assertCountEquals(0)
    }

    @Test fun fullScreenSaysConnectingOnBlack() {
        compose.setContent { TvExplorerTheme { CameraOverlay(Status.Connecting, longTrouble = false, held = true, still = picture, big = true) } }
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test fun aTileShowsItsLastPictureUnderTheWordsWhileItConnects() {
        // Never a still that looks live: the Command Center's tiles have no LIVE badge to say otherwise.
        compose.setContent { TvExplorerTheme { CameraOverlay(Status.Connecting, longTrouble = false, held = false, still = picture, big = false) } }
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test fun aTileWithNoPictureSaysConnecting() {
        compose.setContent { TvExplorerTheme { CameraOverlay(Status.Connecting, longTrouble = false, held = false, still = null, big = false) } }
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test fun aStoppedPictureSaysReconnectingThenThatItCantBeReached() {
        var long by mutableStateOf(false)
        compose.setContent { TvExplorerTheme { CameraOverlay(Status.Stalled(10), longTrouble = long, held = true, still = null, big = false) } }
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        compose.runOnIdle { long = true }
        compose.onNodeWithText("Can’t reach this camera right now").assertIsDisplayed()
        compose.onAllNodesWithText("Reconnecting…").assertCountEquals(0)
    }

    @Test fun aGallerySampleReportsWhatItShows() {
        val seen = mutableListOf<CameraPicture>()
        var sample by mutableStateOf(CameraSamples.Sample(Status.Connecting, picture))
        compose.setContent { TvExplorerTheme { SampleVideo(sample, fill = true) { seen += it } } }
        compose.runOnIdle { sample = CameraSamples.Sample(Status.Live, picture) }
        compose.runOnIdle { sample = CameraSamples.Sample(Status.Stalled(3), picture) }
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(CameraPicture.NONE, CameraPicture.LIVE, CameraPicture.HELD), seen) }
    }

    @Test fun homesSampleTilesStreamOnlyWhenTheyHaveASample() {
        CameraSamples.views = mapOf("Front Door" to CameraSamples.Sample(Status.Live, picture))
        assertEquals("Front Door", CameraSamples.camera("Front Door")?.label)
        assertNull(CameraSamples.camera("Garage"))
        CameraSamples.views = null
        assertNull(CameraSamples.camera("Front Door"))
    }
}
