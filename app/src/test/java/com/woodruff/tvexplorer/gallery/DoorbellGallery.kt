package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.CameraStream
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.nowplaying.Playing
import com.woodruff.tvexplorer.player.DoorbellPage
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.tvs.TvStatus
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.screens.CameraPicture
import com.woodruff.tvexplorer.ui.screens.CameraSamples
import com.woodruff.tvexplorer.ui.screens.DashboardSamples
import com.woodruff.tvexplorer.ui.screens.LiveVideo
import com.woodruff.tvexplorer.ui.screens.cameraHint
import com.woodruff.tvexplorer.ui.screens.rangAt
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
import java.util.Calendar

/**
 * The doorbell (doorbell.md §8): its camera opened by a ring over the slideshow and over Home, with
 * a sample still (a drawn porch, test resources) in place of the live picture, and a person seen as
 * the notice over Home. The live view's own states (connecting, reconnecting over the held picture)
 * are drawn by its real words and veil, through the gallery's sample views. Camera views are dark in
 * both themes; both are drawn to show it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DoorbellGallery {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val out get() = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    /** Checks that aren't published (a very long name): next to the gallery, not in it. */
    private val checks get() = File(out.parentFile, "gallery-checks").apply { mkdirs() }

    /**
     * One doorbell page: what Back says, words in place of the picture, the camera's name, and the
     * live view's state when it isn't simply live ([sample]).
     */
    private data class Page(
        val name: String, val back: String, val message: String? = null, val title: String = "Front Door",
        val sample: CameraSamples.Sample? = null, val dir: File? = null,
    )

    private val door = Camera("enterprises/sample/devices/front-door", "Front Door", null, webRtc = true, doorbell = true)

    @Test
    fun drawDoorbell() {
        val still = javaClass.getResourceAsStream("/gallery/cameras/front_door.jpg")!!.use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        // 7:42 PM today, in the TV's own clock format, as the eyebrow shows a ring's time.
        val at = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 42) }.timeInMillis
        val eyebrow = rangAt(at, android.text.format.DateFormat.getTimeFormat(app))
        val pages = listOf(
            Page("doorbell-slideshow", "Back to the slideshow"),
            Page("doorbell-ring-home", "Back to Home"),
            // Opened from the film's card: the stream starts from scratch, and says so until its first frame.
            Page("doorbell-connecting", "Back to keep watching", sample = CameraSamples.Sample(CameraStream.Status.Connecting)),
            // Its frames stopped: the picture holds under the veil, the words on its scrim.
            Page("doorbell-reconnecting", "Back to Home", sample = CameraSamples.Sample(CameraStream.Status.Stalled(1), still)),
            Page("doorbell-offline", "Back to the slideshow", message = "Offline · reconnecting…"),
            Page("doorbell-long-name", "Back to Home", title = "The Covered Side Porch Doorbell Beside the Garden Gate Path", dir = checks),
            Page("doorbell-cant-reach", "Back to Home", sample = CameraSamples.Sample(CameraStream.Status.Reconnecting(1), still, longTrouble = true), dir = checks),
            // The other words in place of the picture (doorbell.md §5), laid out as offline is.
            Page("doorbell-no-live-view", "Back to close", message = "This camera model doesn’t support live view here", dir = checks),
            Page("doorbell-not-connected", "Back to Home", message = "Connect your cameras in the Command Center first", dir = checks),
        )
        var page by mutableStateOf(pages.first())
        compose.setContent {
            TvExplorerTheme {
                DoorbellPage(eyebrow = eyebrow, title = page.title, hint = cameraHint(page.back, 4), message = page.message) { onPicture ->
                    key(page.name) {
                        val sample = page.sample
                        if (sample != null) LiveVideo(door, audio = false, onPicture = onPicture)
                        else {
                            // The doorbell's live picture, letterboxed as the live view would be.
                            Image(still, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            LaunchedEffect(Unit) { onPicture(CameraPicture.LIVE) }
                        }
                    }
                }
            }
        }
        for (light in listOf(false, true)) {
            for (p in pages) {
                compose.runOnIdle {
                    CameraSamples.views = p.sample?.let { mapOf(door.name to it) }
                    Palette.light = light
                    page = p
                }
                settle()
                compose.onRoot().captureRoboImage(File(p.dir ?: out, "tv-${p.name}-${if (light) "light" else "dark"}.png").path)
            }
        }
        compose.runOnIdle { Palette.light = false; CameraSamples.views = null }
    }

    @Test
    fun drawPersonNotice() {
        seed()
        val vm = MainViewModel(app)
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        var seen: com.woodruff.tvexplorer.notify.Notice? = null
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light; vm.show(Screen.HOME) }
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
            // A person seen by the backyard camera: what happened, then which camera (once: the
            // second theme shows the same notice again).
            compose.runOnIdle {
                seen = seen?.also { Notices.popUp(app, it) } ?: Notices.post(app, NoticeSource.SYSTEM, "Person seen", "Backyard")
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(out, "tv-home-person-notice-${if (light) "light" else "dark"}.png").path)
            compose.runOnIdle { Notices.dismissLatest() }
            compose.mainClock.advanceTimeBy(1_000)
        }
        compose.runOnIdle { Palette.light = false }
    }

    /** Home's sample content, as the main gallery has it (no network, nothing encrypted). */
    private fun seed() {
        freshSamples(app)
        Notes.load(app)
        if (Notes.items.value.isEmpty()) {
            Notes.save(app, null, "Groceries", "Milk\nEggs\nCoffee beans\nBananas", pinned = true)
            Notes.save(app, null, "Movie night", "Friday 7pm: popcorn, blankets, pick a film")
        }
        Notices.load(app)
        Notices.post(app, NoticeSource.MAIL, "Your order has shipped", "Arriving Thursday")
        Notices.post(app, NoticeSource.SYSTEM, "Movie starts in 5 minutes", "")
        Notices.markAllRead(app)
        Notices.dismissLatest()
        RecentStore(app).apply {
            save("drive:sample-3", "Paper Planes (2022)", 3_000_000, 5_600_000)
            save("drive:sample-2", "Backyard Science · S2 E4", 410_000, 1_320_000)
            save("drive:sample-1", "Midnight Harbor (2024)", 2_710_000, 6_840_000)
        }
        DashboardSamples.tvs = listOf(
            TvStatus("this", "Living Room", true, true, Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000)),
            TvStatus("kids", "Den", true, false, Playing("Backyard Science", null, "YouTube", true, 410_000, 1_320_000)),
            TvStatus("bed", "Kitchen", false, false, null),
        )
        DashboardSamples.cameras = listOf("Front Door", "Backyard", "Garage", "Driveway")
    }

    /** Let recomposition and frames run after each page switch. */
    private fun settle() {
        repeat(10) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
