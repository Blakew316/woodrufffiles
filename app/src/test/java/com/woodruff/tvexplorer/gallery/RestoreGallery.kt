package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.player.EpisodeLabel
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.TvTimeBar
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.screens.DashboardSamples
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.update.Restore
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
 * After an update that installed while nobody was watching: what Woodruff comes back to. The film
 * that was paused, at its spot with the controls up (the player is always dark, so one picture serves
 * both names); and, when that film has gone meanwhile, the screen it was started from with the one
 * message that says so. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RestoreGallery {
    @get:Rule val compose = createComposeRule()
    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }

    @Test
    fun drawRestoredFilm() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = BitmapDrawable(activity.resources, film(1920, 1080))
        val player = activity.layoutInflater.inflate(R.layout.tv_player_view, root, false) as PlayerView
        player.setShutterBackgroundColor(0)
        player.setBackgroundColor(0)
        root.addView(player)
        activity.setContentView(root)
        idle()
        // As it comes back: paused, so the controls stay up, the scrub bar focused at the spot (an
        // hour into the film), the title as the player shows it.
        player.showController()
        idle()
        val label = EpisodeLabel.of("Midnight Harbor (2024)")
        player.findViewById<TextView>(R.id.player_title).text = label.title
        player.findViewById<TextView>(R.id.player_eyebrow).visibility = View.GONE
        val bar = player.findViewById<TvTimeBar>(androidx.media3.ui.R.id.exo_progress)
        bar.setDuration(114 * 60_000L)
        bar.setPosition(62 * 60_000L + 41_000)
        bar.setBufferedPosition(63 * 60_000L)
        bar.isFocusableInTouchMode = true
        bar.requestFocus()
        idle()
        for (scheme in listOf("dark", "light")) root.captureRoboImage(File(out, "tv-player-restored-$scheme.png").path)
    }

    @Test
    fun drawFilmGone() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Notices.load(app)
        Notices.dismissLatest()
        RecentStore(app).apply {
            save("drive:sample-3", "Paper Planes (2022)", 3_000_000, 5_600_000)
            save("drive:sample-2", "Backyard Science · S2 E4", 410_000, 1_320_000)
        }
        DashboardSamples.cameras = listOf("Front Door", "Backyard", "Garage", "Driveway")
        val vm = MainViewModel(app)
        compose.setContent { TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } } }
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light; vm.dismissMessage() }
            settle()
            // The screen the film was started from, as the main window opens there and says why.
            compose.runOnIdle { Restore.apply(Restore.App(Screen.HOME, say = Restore.VIDEO_GONE), vm) { error("not Streaming") } }
            settle(10)
            captureScreenRoboImage(File(out, "tv-restore-gone-${if (light) "light" else "dark"}.png").path)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))

    /** Let recomposition, window animations and frames run (the message pill glides up). */
    private fun settle(times: Int = 20) {
        repeat(times) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }

    /** A stand-in frame of film: a harbour at dusk (no video plays on the JVM). */
    private fun film(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.6f, intArrayOf(0xFF0E1A33.toInt(), 0xFF2E3F6B.toInt(), 0xFFC97B5A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.6f, p)
        p.shader = RadialGradient(w * 0.3f, h * 0.58f, h * 0.18f, 0xFFFFD9A0.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.3f, h * 0.58f, h * 0.18f, p)
        p.shader = LinearGradient(0f, h * 0.6f, 0f, h.toFloat(), intArrayOf(0xFF3A2E4F.toInt(), 0xFF0A0F1E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), p)
        // The harbour wall and a few masts against the sky.
        p.shader = null
        p.color = 0xFF070B16.toInt()
        c.drawRect(0f, h * 0.56f, w.toFloat(), h * 0.62f, p)
        for (x in listOf(0.55f, 0.62f, 0.71f, 0.83f)) c.drawRect(w * x, h * 0.28f, w * x + 6f, h * 0.58f, p)
        return b
    }
}
