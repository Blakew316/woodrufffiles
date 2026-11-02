package com.woodruff.tvexplorer.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MimeTypes
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.player.GlidingCard
import com.woodruff.tvexplorer.player.PlayerStyle
import com.woodruff.tvexplorer.player.SoundTrack
import com.woodruff.tvexplorer.player.SoundTracks
import com.woodruff.tvexplorer.player.TvMenu
import com.woodruff.tvexplorer.player.TvTimeBar
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.Locale

/**
 * The player telling the family what's going on (specs/player-feedback.md): a film's sound this TV
 * can't play, said as it starts, and the Audio menu listing every track with the ones that can't
 * play marked. Drawn over a made-up frame of film (no video plays on the JVM); the rows and words
 * come from the player's own [SoundTracks], so the pictures show what the player really says. The
 * player is always dark, so the same picture goes under both names. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerFeedbackGallery {
    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }

    /** A Blu-ray remux: its main sound is Dolby TrueHD 7.1, which this TV can't play. */
    private val remux = listOf(
        SoundTrack("en", 6, MimeTypes.AUDIO_AC3, isDefault = true, selected = true),
        SoundTrack("en", 2, MimeTypes.AUDIO_AC3, name = "Commentary"),
        SoundTrack("en", 8, MimeTypes.AUDIO_TRUEHD, playable = false),
        SoundTrack("es", 6, MimeTypes.AUDIO_AC3),
    )

    @Test
    fun drawSoundThisTvCantPlay() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.clipChildren = false
        root.background = BitmapDrawable(activity.resources, film(1920, 1080))
        val player = activity.layoutInflater.inflate(R.layout.tv_player_view, root, false) as PlayerView
        player.setShutterBackgroundColor(0)
        player.setBackgroundColor(0)
        root.addView(player)
        val message = PlayerStyle.message(activity)
        val messageCard = GlidingCard(message, GlidingCard.Way.Rise)
        root.addView(message, PlayerStyle.messageParams(activity))
        val menu = TvMenu(activity, root)
        activity.setContentView(root)
        settle()

        // The Audio menu, open above its button, the platter on the track this TV can't play.
        player.showController()
        settle()
        player.findViewById<TextView>(R.id.player_title).text = "Midnight Harbor"
        player.findViewById<TextView>(R.id.player_eyebrow).visibility = View.GONE
        val bar = player.findViewById<TvTimeBar>(androidx.media3.ui.R.id.exo_progress)
        bar.setDuration(2 * 3_600_000L + 4 * 60_000L)
        bar.setPosition(38 * 60_000L + 12_000)
        bar.setBufferedPosition(40 * 60_000L)
        val audio = player.findViewById<View>(R.id.player_audio)
        // The JVM runs in touch mode: let the remote's focus show as it would on the TV.
        audio.isFocusableInTouchMode = true
        audio.requestFocus()
        settle()
        menu.show("Audio", SoundTracks.menuRows(remux, Locale.US, choose = {}, explain = {}), audio)
        settle()
        repeat(2) { menu.handleKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        settle()
        save(root, "player-audio-menu")
        menu.hide(animate = false)

        // A film starting on another track because its main sound can't play here: said once,
        // over the film, with the controls away.
        player.hideController()
        settle()
        val started = listOf(
            SoundTrack("en", 6, MimeTypes.AUDIO_DTS_HD, isDefault = true, playable = false),
            SoundTrack("en", 2, MimeTypes.AUDIO_AC3, selected = true),
        )
        val verdict = SoundTracks.verdict(started, null)
        assertNotNull(verdict)
        message.text = SoundTracks.startMessage(verdict!!, SoundTracks.labels(started, Locale.US))
        messageCard.show()
        settle()
        save(root, "player-no-sound")
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }

    private fun save(root: View, name: String) {
        for (scheme in listOf("dark", "light")) root.captureRoboImage(File(out, "tv-$name-$scheme.png").path)
    }

    /** A stand-in frame of film: a sunset over the sea, as in the player's other gallery shots. */
    private fun film(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, intArrayOf(0xFF2B1E4A.toInt(), 0xFFB2496A.toInt(), 0xFFF2A65A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.62f, p)
        p.shader = RadialGradient(w * 0.62f, h * 0.6f, h * 0.22f, 0xFFFFE2A8.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.62f, h * 0.6f, h * 0.22f, p)
        p.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(), intArrayOf(0xFF6B3A5C.toInt(), 0xFF1B1530.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), p)
        return b
    }
}
