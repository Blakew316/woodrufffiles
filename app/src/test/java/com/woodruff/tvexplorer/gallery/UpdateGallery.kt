package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.screens.DashboardSamples
import com.woodruff.tvexplorer.ui.screens.UpdateOffer
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.update.Updater
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Updates on a TV that asks for a press before each one (as a Sony does): the offer as Woodruff
 * opens, over Home, and Settings → Updates saying why. Then the morning after an update that went in
 * by itself overnight: Settings says "Updated today at 3:12 AM", the one place that says so (no
 * notice). Dark and light, at a TV's 960x540dp. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class UpdateGallery {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun drawUpdates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        seed(app)
        // This TV tried 2.0.170 by itself and Android asked for a press: it's ready, waiting for one.
        Updater.resetForTest()
        Updater.onResult(app, PackageInstaller.STATUS_PENDING_USER_ACTION, null, null, how = Updater.How.BY_ITSELF, build = 170)
        val vm = MainViewModel(app)
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
        var offer by mutableStateOf<String?>(null)
        var lastTry by mutableStateOf<String?>(null)
        compose.setContent {
            TvExplorerTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) }
                offer?.let { name ->
                    // The dim Android draws behind every dialog window (the Material dialog's 60 % black,
                    // which the TV's window manager adds and the JVM doesn't draw).
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                    UpdateOffer(name, lastTry, onInstall = {}, onLater = {})
                }
            }
        }
        // As Woodruff opens; and again after a try that didn't go through.
        for ((shot, reason) in listOf("update-offer" to null, "update-offer-retry" to "the TV is out of space")) {
            for (light in listOf(false, true)) {
                val scheme = if (light) "light" else "dark"
                compose.runOnIdle { Palette.light = light; vm.show(Screen.HOME); lastTry = reason; offer = "2.0.170" }
                settle()
                captureScreenRoboImage(File(out, "tv-$shot-$scheme.png").path)
                compose.runOnIdle { offer = null }
                settle()
            }
        }
        // Settings → Updates on this TV, which asks before each update: why it's offered instead.
        settings(vm, out, page = "settings-updates-asks")

        // Updated by itself at 3:12 AM (yesterday's, if it isn't that late yet): Settings' first page
        // says "Updated today" by Updates, and the version row the time.
        val updated = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 3); set(java.util.Calendar.MINUTE, 12); set(java.util.Calendar.SECOND, 0)
            if (timeInMillis > System.currentTimeMillis() - 60_000) add(java.util.Calendar.DAY_OF_MONTH, -1)
        }.timeInMillis
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).apply {
            firstInstallTime = updated - 20 * 24 * 3_600_000L
            lastUpdateTime = updated
        }
        Updater.onResult(app, PackageInstaller.STATUS_SUCCESS, null, null, how = Updater.How.BY_ITSELF, build = Updater.current)
        settings(vm, out, home = "settings-updated", page = "settings-updates")
    }

    /**
     * Settings → Updates, reached as on the TV: down to Updates ([home]: the first page then, with
     * Updates focused), then into it ([page]: its first row is the version). Updates is the first
     * page's seventh row, under TV health, Accounts, Appearance, Screensaver, TVs & phones and Storage.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun settings(vm: MainViewModel, out: File, home: String? = null, page: String) {
        for (light in listOf(false, true)) {
            val scheme = if (light) "light" else "dark"
            compose.runOnIdle { Palette.light = light; vm.show(Screen.SETTINGS) }
            settle()
            repeat(6) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(4) }
            settle()
            home?.let { captureScreenRoboImage(File(out, "tv-$it-$scheme.png").path) }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            settle()
            captureScreenRoboImage(File(out, "tv-$page-$scheme.png").path)
            compose.onRoot().performKeyInput { pressKey(Key.Back) }
            settle()
            compose.runOnIdle { vm.show(Screen.HOME) }
            settle()
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

    /** Something in progress on Home, behind the offer (nothing here touches the network). */
    private fun seed(app: Application) {
        Notices.load(app)
        Notices.dismissLatest()
        RecentStore(app).apply {
            save("drive:sample-3", "Paper Planes (2022)", 3_000_000, 5_600_000)
            save("drive:sample-2", "Backyard Science · S2 E4", 410_000, 1_320_000)
            save("drive:sample-1", "Midnight Harbor (2024)", 2_710_000, 6_840_000)
        }
        DashboardSamples.cameras = listOf("Front Door", "Backyard", "Garage", "Driveway")
    }
}
