package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.server.ServerState
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The phone connection when it isn't simply open: waiting for the TV to be on the home network (with
 * Network settings focused, too), and off because Android didn't let it start. Then the two Settings
 * rows that say what keeps it on. Dark and light, at a TV's 960x540dp. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PhoneGallery {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun drawPhoneConnection() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Notices.load(app)
        Notices.dismissLatest()
        val vm = MainViewModel(app)
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        for (light in listOf(false, true)) {
            val scheme = if (light) "light" else "dark"
            // Arriving from another screen, as from Settings → Connect a phone: the focus starts in a
            // screen (not the side menu) and has nothing to land on here until there's something to press.
            compose.runOnIdle { Palette.light = light; vm.show(Screen.HOME) }
            settle()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            settle()
            compose.runOnIdle { vm.show(Screen.TRANSFER) }
            settle()
            // On, the TV not on Wi-Fi yet: past the grace, the calm offline words.
            compose.runOnIdle { TransferService.showForGallery(ServerState(on = true, waiting = true)) }
            settle(60)
            captureScreenRoboImage(File(out, "tv-phone-waiting-$scheme.png").path)
            // The one thing to press there.
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            settle()
            captureScreenRoboImage(File(out, "tv-phone-waiting-focused-$scheme.png").path)
            // Off: Android didn't let it start (the screen's own start had already been asked for).
            compose.runOnIdle { TransferService.showForGallery(ServerState(error = TransferService.COULDNT_START)) }
            settle()
            captureScreenRoboImage(File(out, "tv-phone-off-$scheme.png").path)
            compose.runOnIdle { TransferService.showForGallery(ServerState()); vm.show(Screen.HOME) }
            settle()
        }
        // Settings → TVs & phones → Start when the TV turns on, and Advanced → Pop-ups over other apps,
        // reached as on the TV: down the list (TV health is its first row), into the page, down to the row.
        for ((shot, page, row) in listOf(Triple("settings-start", 4, 4), Triple("settings-popups", 7, 3))) {
            for (light in listOf(false, true)) {
                val scheme = if (light) "light" else "dark"
                compose.runOnIdle { Palette.light = light; vm.show(Screen.SETTINGS) }
                settle()
                repeat(page) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(4) }
                compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
                settle()
                repeat(row) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(4) }
                settle()
                captureScreenRoboImage(File(out, "tv-$shot-$scheme.png").path)
                compose.onRoot().performKeyInput { pressKey(Key.Back) }
                settle()
                compose.runOnIdle { vm.show(Screen.HOME) }
                settle()
            }
        }
    }

    /** Let recomposition, window animations and frames run. */
    private fun settle(times: Int = 20) {
        repeat(times) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
