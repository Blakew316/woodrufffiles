package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.screens.DashboardSamples
import com.woodruff.tvexplorer.ui.screens.openCameraAt
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * A check, not published: a camera opened full screen from the Command Center while the cameras
 * can't be reached (offline-states.md §3.6). It says "Offline · reconnecting…" on black in the
 * picture's place, as the doorbell's view does (doorbell.md §5), under the camera's name and hint
 * with no scrim, and starts no stream. Camera views look the same in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CameraOfflineGallery {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val out get() = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    /** Checks that aren't published: next to the gallery, not in it. */
    private val checks get() = File(out.parentFile, "gallery-checks").apply { mkdirs() }

    @After fun forget() {
        DashboardSamples.nest = null
        DashboardSamples.cameras = null
        openCameraAt = null
    }

    @Test
    fun drawFullScreenOffline() {
        val names = listOf("Front Door", "Backyard")
        // Home's panel shows these by name (no network, nothing encrypted); the Command Center lists
        // them as Google would, offline.
        DashboardSamples.cameras = names
        DashboardSamples.nest = NestState(
            step = NestStep.CONNECTED,
            cameras = names.mapIndexed { i, n -> Camera(n, n, null, webRtc = true, doorbell = i == 0) },
            offline = true,
        )
        val vm = MainViewModel(app)
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        for (light in listOf(false, true)) {
            val scheme = if (light) "light" else "dark"
            show(vm, Screen.HOME, light)
            // OK on the front door's tile: straight to it, full screen.
            openCameraAt = 0
            show(vm, Screen.CAMERAS, light)
            compose.onRoot().captureRoboImage(File(checks, "tv-camera-offline-$scheme.png").path)
        }
        compose.runOnIdle { Palette.light = false }
    }

    /** [screen] in the [light] or dark theme, composed afresh. */
    private fun show(vm: MainViewModel, screen: Screen, light: Boolean) {
        compose.runOnIdle { Palette.light = light; vm.show(screen) }
        settle()
    }

    /** Let recomposition, frames and the screens' glides run. */
    private fun settle() {
        repeat(8) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
        }
    }
}
