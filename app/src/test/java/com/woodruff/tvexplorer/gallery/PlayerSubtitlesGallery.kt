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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.text.Cue
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.player.EpisodeLabel
import com.woodruff.tvexplorer.player.SubtitleLook
import com.woodruff.tvexplorer.player.SubtitlePlace
import com.woodruff.tvexplorer.player.SubtitleSize
import com.woodruff.tvexplorer.player.SubtitleTracks
import com.woodruff.tvexplorer.player.TvMenu
import com.woodruff.tvexplorer.player.TvTimeBar
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.motion.AppMotion
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
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
 * Subtitles as the player draws them (specs/player-feedback.md §3.5–§3.7, §8): a two-line cue over a
 * bright sky (the hardest place for white letters), the same cue risen above the controls, and the
 * Subtitle size menu. Drawn with the player's own [SubtitleLook] and [SubtitlePlace] over a made-up
 * frame (no video plays on the JVM). The player is always dark, so the same picture goes under both
 * names. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerSubtitlesGallery {
    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }

    @Test
    fun drawSubtitles() {
        // Everything where it's going on the next frame: the shots wait for a still screen.
        AppMotion.reduced = true
        try {
            drawSubtitleStates()
        } finally {
            AppMotion.reduced = false
        }
    }

    private fun drawSubtitleStates() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.clipChildren = false
        root.background = BitmapDrawable(activity.resources, brightSky(1920, 1080))
        val player = activity.layoutInflater.inflate(R.layout.tv_player_view, root, false) as PlayerView
        player.setShutterBackgroundColor(0)
        player.setBackgroundColor(0)
        root.addView(player)
        val menu = TvMenu(activity, root)
        activity.setContentView(root)
        player.hideController()
        settle()

        // The player's own look, at the Standard size, and a two-line cue at Media3's own place.
        val subtitles = player.subtitleView!!
        SubtitleLook.apply(subtitles, activity, SubtitleSize.STANDARD, tvCaptions = false, screenHeightPx = activity.resources.displayMetrics.heightPixels)
        subtitles.setCues(listOf(Cue.Builder().setText("We’d better head back before the tide comes in.\nIt turns faster than you think.").build()))
        settle()
        save(root, "player-subtitles")

        // The controls up: the cue rises 12 dp above the title row, as the player moves it.
        player.showController()
        settle()
        val label = EpisodeLabel.of("Backyard Science S02E05 The Volcano")
        player.findViewById<TextView>(R.id.player_title).text = label.title
        player.findViewById<TextView>(R.id.player_eyebrow).apply { text = label.eyebrow; visibility = View.VISIBLE }
        val bar = player.findViewById<TvTimeBar>(androidx.media3.ui.R.id.exo_progress)
        bar.setDuration(44 * 60_000L + 10_000)
        bar.setPosition(12 * 60_000L + 34_000)
        bar.setBufferedPosition(15 * 60_000L)
        bar.isFocusableInTouchMode = true
        bar.requestFocus()
        settle()
        val place = SubtitlePlace(root, subtitles)
        place.update(listOf(player.findViewById(R.id.player_info_row)), fade = false)
        settle()
        save(root, "player-subtitles-raised")

        // Subtitles → Size: the Subtitle size menu above the same button (between two lines of the
        // film, so the menu is seen on its own).
        subtitles.setCues(emptyList())
        val subs = player.findViewById<View>(R.id.player_subtitles)
        subs.isFocusableInTouchMode = true
        subs.requestFocus()
        settle()
        menu.show("Subtitle size", SubtitleTracks.sizeRows(SubtitleSize.STANDARD) {}, subs)
        settle()
        save(root, "player-subtitle-size")
        menu.hide(animate = false)
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }

    private fun save(root: View, name: String) {
        for (scheme in listOf("dark", "light")) root.captureRoboImage(File(out, "tv-$name-$scheme.png").path)
    }

    /** A frame of film at its brightest: a hazy midday sky, whitest low down where subtitles sit, the sun glowing there. */
    private fun brightSky(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF3F7FC4.toInt(), 0xFF8FC0EA.toInt(), 0xFFDCEDFA.toInt(), 0xFFF6FAFE.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.46f, h * 0.9f, h * 0.45f, intArrayOf(0xFFFFFFFF.toInt(), 0xCCFFFDF2.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.46f, h * 0.9f, h * 0.45f, p)
        // A low line of far hills, pale in the haze.
        p.shader = LinearGradient(0f, h * 0.8f, 0f, h.toFloat(), intArrayOf(0xFFC9D9E3.toInt(), 0xFFB8CAD6.toInt()), null, Shader.TileMode.CLAMP)
        val hills = android.graphics.Path().apply {
            moveTo(0f, h * 0.86f)
            cubicTo(w * 0.2f, h * 0.82f, w * 0.35f, h * 0.88f, w * 0.55f, h * 0.85f)
            cubicTo(w * 0.75f, h * 0.82f, w * 0.9f, h * 0.87f, w.toFloat(), h * 0.84f)
            lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        c.drawPath(hills, p)
        return b
    }
}

/**
 * Settings → Appearance → Subtitle size, reached as on the TV (down to Appearance, into it, down to
 * the row): the same size the player's Subtitles menu changes. Dark and light. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SubtitleSizeSettingsGallery {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun drawSubtitleSizeSetting() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Notices.load(app)
        Notices.dismissLatest()
        val vm = MainViewModel(app)
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        // As on the TV after the first press: the focus in a screen, not the side menu.
        compose.runOnIdle { vm.show(Screen.HOME) }
        settle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        settle()
        for (light in listOf(false, true)) {
            val scheme = if (light) "light" else "dark"
            compose.runOnIdle { Palette.light = light; vm.show(Screen.SETTINGS) }
            settle()
            // Accounts, then Appearance; into it; Theme, Background photo, Cameras on Home, Match frame rate, Subtitle size.
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            settle(4)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            settle()
            repeat(4) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(4) }
            settle()
            captureScreenRoboImage(File(out, "tv-settings-subtitle-size-$scheme.png").path)
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
}
