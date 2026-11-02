package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.content.Context
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
import com.woodruff.tvexplorer.tvs.AppExit
import com.woodruff.tvexplorer.tvs.CrashFact
import com.woodruff.tvexplorer.tvs.DoorbellFact
import com.woodruff.tvexplorer.tvs.DoorbellState
import com.woodruff.tvexplorer.tvs.ExitKind
import com.woodruff.tvexplorer.tvs.Health
import com.woodruff.tvexplorer.tvs.HealthFacts
import com.woodruff.tvexplorer.tvs.HealthGrant
import com.woodruff.tvexplorer.tvs.InstallFact
import com.woodruff.tvexplorer.tvs.KeysFact
import com.woodruff.tvexplorer.tvs.LinkedTv
import com.woodruff.tvexplorer.tvs.SignInFact
import com.woodruff.tvexplorer.tvs.SignInState
import com.woodruff.tvexplorer.tvs.TvHealthKind
import com.woodruff.tvexplorer.tvs.TvHealthView
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
 * Settings → TV health, dark and light: the list of the house's TVs, a TV that needs attention, this
 * TV all good, and a TV whose link stopped working. Sample reports (no network): Living Room (this
 * TV, all good), Den (its Drive sign-in ended and the last update didn't install), Kitchen (off for
 * two hours) and Bedroom (reset, turning down its link). The phone's health gallery shows the same four.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HealthGallery {
    @get:Rule val compose = createComposeRule()

    @After fun forget() = forgetSamples()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun drawHealth() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        seedSamples(app)
        val vm = MainViewModel(app)
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
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
                compose.onRoot().captureRoboImage(File(out, "tv-$name-${if (light) "light" else "dark"}.png").path)
            }
            compose.runOnIdle { Palette.light = false }
            settle()
        }
        // Settings' first row (TV health) has the focus: open the list, Den focused.
        press(listOf(Key.DirectionRight, Key.DirectionDown))
        shoot("health")
        // Den's report, its ended Drive sign-in focused.
        press(listOf(Key.DirectionRight, Key.DirectionDown))
        shoot("health-report")
        // Back to the list (Den), up to this TV, and its report.
        press(listOf(Key.DirectionLeft, Key.DirectionUp, Key.DirectionRight))
        shoot("health-good")
        // Bedroom, whose link stopped working.
        press(listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionRight))
        shoot("health-link-again")
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

    companion object {
        /** The four TVs every health shot shows (also Settings' first page in TvGallery). */
        fun seedSamples(c: Context) {
            com.woodruff.tvexplorer.tvs.TvLinks.setName(c, "Living Room")
            val now = System.currentTimeMillis()
            val hour = 3_600_000L
            val day = 24 * hour
            fun facts(
                name: String, signIns: Map<HealthGrant, SignInFact> = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) },
                doorbell: DoorbellFact = doorbellFact(subscribed = true, status = null, networkUp = true),
                update: UpdateFact = UpdateFact("upToDate", "2.0.171"), crash: CrashFact? = null, exits: List<AppExit> = emptyList(),
                free: Long = 12_400L * 1024 * 1024 * 1024 / 1000,
            ) = HealthFacts(
                id = name.lowercase(), name = name, version = "2.0.171", sdk = 31, uptimeMs = 2 * day,
                signIns = signIns, doorbell = doorbell, update = update,
                installed = InstallFact("Installed fresh Oct 12, 9:10 AM · updated Nov 1, 3:02 AM by Woodruff itself", "Woodruff itself", now - 20 * day, now - day),
                installAllowed = true, crash = crash, exits = exits, freeBytes = free,
                keys = KeysFact(googleClientId = true, googleClientSecret = true, camerasProject = true, tmdb = true),
            )
            val through = DoorbellFact(DoorbellState.THROUGH, "Through Living Room", "This TV hears the doorbell from Living Room while that TV is on. Connect the cameras on this TV to hear it on its own.")
            val living = buildHealth(facts("Living Room", exits = listOf(AppExit(ExitKind.LOW_MEMORY, now - 11 * hour))), Health.clock(c, now))
            val den = buildHealth(facts(
                "Den",
                signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } +
                    (HealthGrant.DRIVE to SignInFact(SignInState.EXPIRED, now - day)) + (HealthGrant.CAMERAS to SignInFact(SignInState.UNREACHABLE)) +
                    (HealthGrant.CALENDAR to SignInFact(SignInState.NOT_SIGNED_IN)),
                doorbell = through,
                update = UpdateFact("ready", "2.0.172", message = "The package installer said no."),
                crash = CrashFact(now - 12 * day, CRASH),
            ), Health.clock(c, now))
            val kitchen = buildHealth(facts("Kitchen", doorbell = through), Health.clock(c, now - 2 * hour))
            val bedroom = buildHealth(facts("Bedroom", doorbell = through), Health.clock(c, now - day))
            HealthSamples.thisReport = living
            HealthSamples.linked = listOf(
                LinkedTv("den", "Den", "http://den.local:8080", "k1") to TvHealthView("Den", false, TvHealthKind.ATTENTION, den, now - 3 * 60_000L, now),
                LinkedTv("kitchen", "Kitchen", "http://kitchen.local:8080", "k2") to TvHealthView("Kitchen", false, TvHealthKind.OFF, kitchen, now - 2 * hour, now - 2 * hour),
                LinkedTv("bedroom", "Bedroom", "http://bedroom.local:8080", "k3") to TvHealthView("Bedroom", false, TvHealthKind.REFUSED, bedroom, now - day, now - day),
            )
        }

        /**
         * Takes the four TVs away again once a class's shots are drawn. They're kept for the whole gallery run, so
         * every class drawn after one that seeded them showed them too: PhoneGallery's TVs & phones page said
         * "Other TVs · 3 linked" or "None linked" depending on which class happened to run first.
         */
        fun forgetSamples() {
            HealthSamples.thisReport = null
            HealthSamples.linked = null
        }

        private const val CRASH = "Version 2.0.170 · Oct 20, 9:41 PM · thread main\n" +
            "java.lang.IllegalStateException: The player was released\n" +
            "\tat androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:2861)\n" +
            "\tat com.woodruff.tvexplorer.player.PlayerActivity.onStop(PlayerActivity.kt:410)\n"
    }
}
