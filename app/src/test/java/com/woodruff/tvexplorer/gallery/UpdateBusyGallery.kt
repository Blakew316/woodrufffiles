package com.woodruff.tvexplorer.gallery

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ViewGroup
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
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.update.RecoveryView
import com.woodruff.tvexplorer.update.Updater
import com.woodruff.tvexplorer.update.Updater.State
import com.woodruff.tvexplorer.update.Updater.Step
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * GitHub turned an update check down for its hourly limit (60 requests for the whole home, shared by every TV): no
 * check asks it again before the time it gave, and Settings › Updates and the recovery screen say when that is. Dark
 * and light at a TV's 960x540dp (the recovery screen is always dark: the same picture under both names). Only runs
 * with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class UpdateBusyGallery {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }

    /** 9:40 PM today: when GitHub said it may be asked again. */
    private val until = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 21); set(java.util.Calendar.MINUTE, 40); set(java.util.Calendar.SECOND, 0)
    }.timeInMillis
    private val busy get() = State(
        Step.ERROR, message = "GitHub is busy · checking again at ${android.text.format.DateFormat.getTimeFormat(app).format(java.util.Date(until))}",
        busyUntil = until,
    )

    @After fun restore() {
        RecoveryView.resetForTest()
        Updater.resetForTest()
    }

    @Test fun drawRecoveryWhileGitHubIsBusy() {
        RecoveryView.lookForFix = { _, _ -> }
        RecoveryView.load = { it.run() }
        Updater.showForTest(busy)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        RecoveryView.show(activity)
        settleViews()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        for (scheme in listOf("dark", "light")) root.captureRoboImage(File(out, "tv-recovery-busy-$scheme.png").path)
    }

    /** Settings › Updates, reached as on the TV: down to Updates (the first page's seventh row), then into it. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun drawSettingsUpdatesWhileGitHubIsBusy() {
        Updater.showForTest(busy)
        val vm = MainViewModel(app)
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        // Home first, as Woodruff opens, so the focus is where it is on the TV before going down to Settings.
        compose.runOnIdle { vm.show(Screen.HOME) }
        settle()
        for (light in listOf(false, true)) {
            val scheme = if (light) "light" else "dark"
            compose.runOnIdle { Palette.light = light; vm.show(Screen.SETTINGS) }
            settle()
            repeat(6) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(4) }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            settle()
            captureScreenRoboImage(File(out, "tv-settings-updates-busy-$scheme.png").path)
            compose.onRoot().performKeyInput { pressKey(Key.Back) }
            settle()
            compose.runOnIdle { vm.show(Screen.HOME) }
            settle()
        }
    }

    /** Let the focus grow (150 ms) and every frame settle. */
    private fun settleViews() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
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
