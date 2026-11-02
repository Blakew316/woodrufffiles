package com.woodruff.tvexplorer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The player's controls read over the brightest picture a film can have (snow, a beach, a white sky):
 * the show and episode line, the title, the round buttons and the times, each measured against what the
 * controls' scrim leaves of a pure white frame behind it, at its top edge (the scrim is weakest there).
 * The scrim was 70 % black at the bottom edge and only 15 % behind the title: 1.4:1 to 1.6:1.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerChromeContrastTest {
    @Test fun theControlsReadOverAWhiteFrame() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val player = activity.layoutInflater.inflate(R.layout.tv_player_view, root, false) as PlayerView
        root.addView(player)
        activity.setContentView(root)
        settle()
        player.showController()
        val eyebrow = player.findViewById<TextView>(R.id.player_eyebrow).apply { text = "Backyard Science · S2, E5"; visibility = View.VISIBLE }
        val title = player.findViewById<TextView>(R.id.player_title).apply { text = "The Volcano" }
        val bar = player.findViewById<TvTimeBar>(androidx.media3.ui.R.id.exo_progress)
        settle()

        // The scrim over a white frame: what's left of the picture behind each part of the controls.
        val scrim = player.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)
        val frame = Bitmap.createBitmap(scrim.width, scrim.height, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply { drawColor(Color.WHITE); scrim.draw(this) }
        val origin = IntArray(2).also(scrim::getLocationInWindow)
        fun behind(v: View, below: Float = 0f): Int {
            val at = IntArray(2).also(v::getLocationInWindow)
            val x = (at[0] - origin[0] + v.width / 2).coerceIn(0, frame.width - 1)
            val y = (at[1] - origin[1] + below.toInt() + 1).coerceIn(0, frame.height - 1)
            return frame.getPixel(x, y) or 0xFF000000.toInt()
        }

        assertReads("the show and episode line", over(eyebrow.currentTextColor, behind(eyebrow)), behind(eyebrow), 4.5)
        assertReads("the title", over(title.currentTextColor, behind(title)), behind(title), 4.5)
        for (id in listOf(R.id.player_subtitles, R.id.player_audio, R.id.player_more)) {
            val button = player.findViewById<ImageButton>(id)
            val glass = (button.background.current as GradientDrawable).color!!.defaultColor
            val fill = over(glass, behind(button))
            val icon = button.imageTintList!!.getColorForState(button.drawableState, Color.WHITE)
            assertReads("${button.contentDescription}'s icon on its glass", over(icon, fill), fill, 3.0)
        }
        // The times sit under the bar, its labels' line starting 18 dp down (white 80 % at rest).
        val labels = behind(bar, below = 18 * activity.resources.displayMetrics.density)
        assertReads("the times", over(0xCCFFFFFF.toInt(), labels), labels, 4.5)
    }

    private fun assertReads(what: String, fg: Int, bg: Int, needed: Double) {
        val ratio = contrast(fg, bg)
        assertTrue("$what is ${"%.2f".format(ratio)}:1 over a white frame (needs $needed:1)", ratio >= needed)
    }

    /** [fg] (which may be see-through) drawn over the opaque [bg]. */
    private fun over(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255.0
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a)).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(fg), Color.red(bg)), mix(Color.green(fg), Color.green(bg)), mix(Color.blue(fg), Color.blue(bg)))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun channel(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }
}
