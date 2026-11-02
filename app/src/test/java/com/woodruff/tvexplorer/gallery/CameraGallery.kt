package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.CameraStream.Status
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.nowplaying.Playing
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.tvs.TvStatus
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.screens.CameraSamples
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
 * Camera tiles as their frames say (finding 37), on Home and in the Command Center: live (with Home's
 * LIVE badge), a picture that stopped ("Reconnecting…" over it), one connecting over its last picture,
 * and one that can't be reached. No WebRTC in the gallery: sample views (drawn scenes, no real place)
 * go through the same words and veil as live ones. Camera pictures are the same in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CameraGallery {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val out get() = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    /** Checks that aren't published: next to the gallery, not in it. */
    private val checks get() = File(out.parentFile, "gallery-checks").apply { mkdirs() }

    private val names = listOf("Front Door", "Backyard", "Garage", "Driveway")

    @After fun forget() {
        CameraSamples.views = null
        DashboardSamples.nest = null
        DashboardSamples.cameras = null
        DashboardSamples.tvs = null
    }

    @Test
    fun drawCameraStates() {
        seed()
        val door = javaClass.getResourceAsStream("/gallery/cameras/front_door.jpg")!!.use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        val states = mapOf(
            "Front Door" to CameraSamples.Sample(Status.Live, door),
            "Backyard" to CameraSamples.Sample(Status.Stalled(1), scene(Scene.BACKYARD)),
            "Garage" to CameraSamples.Sample(Status.Connecting, scene(Scene.GARAGE)),
            "Driveway" to CameraSamples.Sample(Status.Reconnecting(1), longTrouble = true),
        )
        val connecting = names.associateWith { CameraSamples.Sample(Status.Connecting) }
        val vm = MainViewModel(app)
        // Read as the screens compose (not state): set before the first one does.
        CameraSamples.views = states
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        for (light in listOf(false, true)) {
            val scheme = if (light) "light" else "dark"
            CameraSamples.views = states
            show(vm, Screen.HOME, light)
            compose.onRoot().captureRoboImage(File(out, "tv-home-cameras-$scheme.png").path)
            show(vm, Screen.CAMERAS, light)
            compose.onRoot().captureRoboImage(File(out, "tv-command-center-cameras-$scheme.png").path)
            // Unpublished checks: every tile connecting with no picture yet (the placeholder's words
            // clear the camera's name in Home's short tiles), and a stopped picture full screen.
            CameraSamples.views = connecting
            show(vm, Screen.HOME, light)
            compose.onRoot().captureRoboImage(File(checks, "tv-home-cameras-connecting-$scheme.png").path)
            CameraSamples.views = states
            openCameraAt = 1
            show(vm, Screen.CAMERAS, light)
            compose.onRoot().captureRoboImage(File(checks, "tv-camera-reconnecting-$scheme.png").path)
        }
        compose.runOnIdle { Palette.light = false }
    }

    /**
     * [screen] in the [light] or dark theme. The shots alternate Home and the Command Center, so each
     * screen is composed afresh with the samples as they are then.
     */
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

    /** Home's sample content, as the main gallery has it (no network, nothing encrypted), and four cameras. */
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
            TvStatus("den", "Den", true, false, Playing("Backyard Science", null, "YouTube", true, 410_000, 1_320_000)),
            TvStatus("kitchen", "Kitchen", false, false, null),
        )
        DashboardSamples.cameras = names
        // The Command Center's cameras, named as the samples are (a doorbell first, as Google lists them).
        DashboardSamples.nest = NestState(
            step = NestStep.CONNECTED,
            cameras = names.mapIndexed { i, n -> Camera(n, n, null, webRtc = true, doorbell = i == 0) },
        )
    }

    private enum class Scene { BACKYARD, GARAGE }

    /** A drawn camera view (no real place), 640 × 360, for tiles that aren't the front door. */
    private fun scene(kind: Scene): ImageBitmap {
        val w = 640f
        val h = 360f
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun gradient(top: Float, bottom: Float, from: Long, to: Long) {
            p.shader = LinearGradient(0f, top, 0f, bottom, from.toInt(), to.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, top, w, bottom, p)
            p.shader = null
        }
        fun rect(color: Long, l: Float, t: Float, r: Float, b: Float) {
            p.color = color.toInt()
            c.drawRect(l, t, r, b, p)
        }
        when (kind) {
            Scene.BACKYARD -> {
                gradient(0f, h * 0.5f, 0xFF8FBFE3, 0xFFE3EEF5)
                gradient(h * 0.5f, h, 0xFF78A84E, 0xFF3D7330)
                // A wooden fence across the lawn, and a tree.
                for (x in 0 until w.toInt() step 28) rect(0xFFC0956A, x.toFloat(), h * 0.34f, x + 23f, h * 0.6f)
                rect(0xFF8E6440, 0f, h * 0.41f, w, h * 0.44f)
                rect(0xFF8E6440, 0f, h * 0.52f, w, h * 0.55f)
                rect(0xFF5A3B20, 468f, 118f, 486f, 236f)
                p.color = 0xFF2E6A2A.toInt()
                c.drawCircle(477f, 98f, 64f, p)
                p.color = 0xFF3A7E33.toInt()
                c.drawCircle(440f, 120f, 42f, p)
                c.drawCircle(516f, 124f, 38f, p)
            }
            Scene.GARAGE -> {
                gradient(0f, h * 0.72f, 0xFFCFCBC4, 0xFFB9B4AC)
                gradient(h * 0.72f, h, 0xFF9C9891, 0xFF7E7A74)
                // A panelled garage door under a lamp.
                rect(0xFFEDEBE7, 150f, 70f, 490f, h * 0.72f)
                for (i in 0 until 4) rect(0xFFD9D6D0, 162f, 82f + i * 46f, 478f, 82f + i * 46f + 36f)
                rect(0xFF55524D, 304f, 36f, 336f, 52f)
                p.color = 0xFFFFE9A8.toInt()
                c.drawCircle(320f, 58f, 9f, p)
            }
        }
        return bmp.asImageBitmap()
    }
}
