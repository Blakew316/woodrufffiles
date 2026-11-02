package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.nest.DoorbellListening
import com.woodruff.tvexplorer.nest.doorbellAlerts
import com.woodruff.tvexplorer.tvs.Health
import com.woodruff.tvexplorer.tvs.HealthFacts
import com.woodruff.tvexplorer.tvs.HealthGrant
import com.woodruff.tvexplorer.tvs.InstallFact
import com.woodruff.tvexplorer.tvs.KeysFact
import com.woodruff.tvexplorer.tvs.SignInFact
import com.woodruff.tvexplorer.tvs.SignInState
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.tvs.UpdateFact
import com.woodruff.tvexplorer.tvs.buildHealth
import com.woodruff.tvexplorer.tvs.doorbellFact
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.screens.HealthSamples
import com.woodruff.tvexplorer.ui.screens.SettingsScreen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
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
 * Unpublished checks (build/gallery-checks), dark and light: this TV's health report on an Android 12 TV
 * with automatic updates on and "Pop-ups over other apps" not allowed, so Woodruff can't open again
 * after an update. The summary with its one item, then the row itself (amber "Not allowed") with its fix.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HealthPopUpsGallery {
    @get:Rule val compose = createComposeRule()

    @After fun forget() = HealthGallery.forgetSamples()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun drawPopUpsNotAllowed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TvLinks.setName(app, "Living Room")
        val now = System.currentTimeMillis()
        val day = 24 * 3_600_000L
        HealthSamples.thisReport = buildHealth(
            HealthFacts(
                id = "living-room", name = "Living Room", version = "2.0.171", sdk = 31, uptimeMs = 2 * day,
                signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) },
                doorbell = doorbellFact(doorbellAlerts(subscription = true, camerasHere = true, relaysOut = true, relayFrom = null, listening = DoorbellListening.Own)),
                update = UpdateFact("upToDate", "2.0.171"),
                installed = InstallFact("Installed fresh Oct 12, 9:10 AM · updated Nov 1, 3:02 AM by Woodruff itself", "Woodruff itself", now - 20 * day, now - day),
                installAllowed = true, popUpsAllowed = false, crash = null, exits = emptyList(), freeBytes = 12_400L * 1024 * 1024 * 1024 / 1000,
                keys = KeysFact(googleClientId = true, googleClientSecret = true, camerasProject = true, tmdb = true),
            ),
            Health.clock(app, now),
        )
        val vm = MainViewModel(app)
        val checks = File(File(System.getProperty("gallery.dir") ?: "build/gallery").parentFile, "gallery-checks").apply { mkdirs() }
        val focus = FocusRequester()
        compose.setContent {
            TvExplorerTheme {
                Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop().focusRequester(focus).focusGroup()) { SettingsScreen(vm) }
            }
        }
        settle()
        compose.runOnIdle { focus.requestFocus() }
        settle()
        fun press(keys: List<Key>) = keys.forEach { k -> compose.onRoot().performKeyInput { pressKey(k) }; settle(6) }
        fun shoot(name: String) {
            settle()
            for (light in listOf(false, true)) {
                compose.runOnIdle { Palette.light = light }
                settle()
                compose.onRoot().captureRoboImage(File(checks, "tv-$name-${if (light) "light" else "dark"}.png").path)
            }
            compose.runOnIdle { Palette.light = false }
            settle()
        }
        // No linked TVs: TV health opens this TV's report, its summary focused.
        press(listOf(Key.DirectionRight))
        shoot("health-popups-summary")
        // Down past the sign-ins, the doorbell, the version and Installing updates.
        press(List(8) { Key.DirectionDown })
        shoot("health-popups")
    }

    /** Let recomposition, the pages' glides and the about panel's fade run. */
    private fun settle(times: Int = 20) {
        repeat(times) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
