package com.woodruff.tvexplorer.gallery

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
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.player.EpisodeLabel
import com.woodruff.tvexplorer.player.TvMenu
import com.woodruff.tvexplorer.player.TvTimeBar
import com.woodruff.tvexplorer.player.UpNextPanel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Draws the player's own screens for the UI gallery: its controls, a track menu, and Up next, over
 * a made-up frame of film (no video plays on the JVM). The player is always dark, so the same
 * picture goes under both names. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerGallery {
    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }

    @Test
    fun drawPlayer() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.background = BitmapDrawable(activity.resources, film(1920, 1080))
        val player = activity.layoutInflater.inflate(R.layout.tv_player_view, root, false) as PlayerView
        player.setShutterBackgroundColor(0)
        player.setBackgroundColor(0)
        root.addView(player)
        val menu = TvMenu(activity, root)
        val upNext = UpNextPanel(activity, root)
        activity.setContentView(root)
        settle()

        // The controls, as OK brings them up: the scrub bar focused, 12:34 into a 44-minute episode.
        player.showController()
        settle()
        val label = EpisodeLabel.of("Backyard Science S02E05 The Volcano")
        player.findViewById<TextView>(R.id.player_title).text = label.title
        player.findViewById<TextView>(R.id.player_eyebrow).apply { text = label.eyebrow; visibility = View.VISIBLE }
        val bar = player.findViewById<TvTimeBar>(androidx.media3.ui.R.id.exo_progress)
        bar.setDuration(44 * 60_000L + 10_000)
        bar.setPosition(12 * 60_000L + 34_000)
        bar.setBufferedPosition(15 * 60_000L)
        // The JVM runs in touch mode: let the remote's focus show as it would on the TV.
        bar.isFocusableInTouchMode = true
        bar.requestFocus()
        settle()
        save(root, "player")

        // The Subtitles menu, open above its button.
        val subs = player.findViewById<View>(R.id.player_subtitles)
        subs.isFocusableInTouchMode = true
        subs.requestFocus()
        menu.show("Subtitles", listOf(
            TvMenu.Row("Off") {}, TvMenu.Row("English", checked = true) {}, TvMenu.Row("English (SDH)") {},
            TvMenu.Row("Español") {}, TvMenu.Row("Français") {},
        ), subs)
        settle()
        save(root, "player-menu")
        menu.hide(animate = false)

        // Up next: the controls away, the next episode counting down.
        player.hideController()
        player.useController = false
        upNext.show(EpisodeLabel.of("Backyard Science S02E06 Tide Pools"), 10)
        upNext.setArt(film(640, 360, warm = false))
        settle()
        save(root, "player-up-next")
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }

    private fun save(root: View, name: String) {
        for (scheme in listOf("dark", "light")) root.captureRoboImage(File(out, "tv-$name-$scheme.png").path)
    }

    /** A stand-in frame of film: a sunset over the sea (or a cool daytime one for Up next's picture). */
    private fun film(w: Int, h: Int, warm: Boolean = true): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sky = if (warm) intArrayOf(0xFF2B1E4A.toInt(), 0xFFB2496A.toInt(), 0xFFF2A65A.toInt()) else intArrayOf(0xFF1D4E89.toInt(), 0xFF4F9BD9.toInt(), 0xFFBFE3F2.toInt())
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, sky, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.62f, p)
        p.shader = RadialGradient(w * 0.62f, h * 0.6f, h * 0.22f, if (warm) 0xFFFFE2A8.toInt() else 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.62f, h * 0.6f, h * 0.22f, p)
        val sea = if (warm) intArrayOf(0xFF6B3A5C.toInt(), 0xFF1B1530.toInt()) else intArrayOf(0xFF2A7AB8.toInt(), 0xFF0E3557.toInt())
        p.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(), sea, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), p)
        return b
    }
}
