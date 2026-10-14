package com.woodruff.tvexplorer.gallery

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every main TV screen, dark and light, at a TV's 960x540dp (1920x1080 px), for the UI
 * gallery on GitHub Pages. Not a pass/fail test: it only runs with `-Pgallery` (see the
 * "gallery" job in .github/workflows/build.yml).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvGallery {
    @get:Rule val compose = createComposeRule()

    private val screens = listOf(
        Screen.HOME to "home",
        Screen.CAMERAS to "command-center",
        Screen.DRIVE to "google-drive",
        Screen.MAIL to "mail",
        Screen.NOTIFICATIONS to "notifications",
        Screen.NOTES to "notes",
        Screen.APPS to "apps",
        Screen.TRANSFER to "phone",
        Screen.SETTINGS to "settings",
    )

    @Test
    fun drawScreens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        seed(app)
        val vm = MainViewModel(app)
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light }
            for ((screen, name) in screens) {
                compose.runOnIdle { vm.show(screen) }
                compose.mainClock.advanceTimeBy(1_500) // let the screen cross-fade finish
                compose.waitForIdle()
                compose.onRoot().captureRoboImage(File(out, "tv-$name-${if (light) "light" else "dark"}.png").path)
            }
        }
    }

    /** Sample content so screens aren't empty. Nothing here touches the encrypted store. */
    private fun seed(app: Application) {
        Notes.load(app)
        Notes.save(app, null, "Groceries", "Milk\nEggs\nCoffee beans\nBananas", pinned = true)
        Notes.save(app, null, "Wi-Fi for guests", "Network: Woodruff-Guest\nPassword is on the fridge")
        Notes.save(app, null, "Movie night", "Friday 7pm: popcorn, blankets, pick a film")
        Notices.load(app)
        Notices.post(app, NoticeSource.PHONE, "Dinner's ready", "Come downstairs when you can")
        Notices.post(app, NoticeSource.MAIL, "Your order has shipped", "Arriving Thursday")
        Notices.post(app, NoticeSource.SYSTEM, "Movie starts in 5 minutes", "")
        Notices.markAllRead(app)
    }
}
