package com.woodruff.tvexplorer.player

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.ui.motion.AppMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Subtitles' look and size (specs/player-feedback.md §3.5, §3.6) and their stepping out of the way
 * of the controls, Skip Intro and messages (§3.7), on the player's own layout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SubtitleLookTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Media3's SubtitleView keeps its settings to itself: read for the test. */
    private fun <T> SubtitleView.field(name: String): T =
        SubtitleView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    @Test fun theSizeChosenIsKept() {
        assertEquals(SubtitleSize.STANDARD, SubtitleLook.size(context))
        SubtitleLook.setSize(context, SubtitleSize.LARGE)
        assertEquals(SubtitleSize.LARGE, SubtitleLook.size(context))
        // Where Settings → Appearance and FrameRateMatch keep theirs.
        assertEquals("large", context.getSharedPreferences("player", 0).getString("subtitle_size", null))
    }

    @Test fun woodruffsLookWhiteLettersWithAnEdgeAtTheSizeChosenOfTheScreen() {
        val view = SubtitleView(context)
        SubtitleLook.apply(view, context, SubtitleSize.LARGE, tvCaptions = false, screenHeightPx = 1080)
        val style = view.field<CaptionStyleCompat>("style")
        assertEquals(android.graphics.Color.WHITE, style.foregroundColor)
        assertEquals(android.graphics.Color.TRANSPARENT, style.backgroundColor)
        assertEquals(android.graphics.Color.TRANSPARENT, style.windowColor)
        assertEquals(SubtitleLook.EDGE, style.edgeType)
        assertNotNull(style.typeface)
        // A share of the screen's height, not the picture's: the same size on a 2.39:1 film.
        assertEquals(0.064f * 1080, view.field<Float>("defaultTextSize"), 0.01f)
        assertFalse(view.field<Boolean>("applyEmbeddedFontSizes"))
        assertTrue(view.field<Boolean>("applyEmbeddedStyles"))
        assertEquals(SubtitleLook.BOTTOM_PADDING, view.field<Float>("bottomPaddingFraction"), 0.0001f)
    }

    @Test fun theTvsOwnCaptionSettingsWin() {
        val view = SubtitleView(context)
        SubtitleLook.apply(view, context, SubtitleSize.LARGE, tvCaptions = false, screenHeightPx = 1080)
        SubtitleLook.apply(view, context, SubtitleSize.LARGE, tvCaptions = true, screenHeightPx = 1080)
        // Media3's own: the TV's style (with no settings of its own here, Media3's boxes), at Media3's own share of the picture.
        assertEquals(CaptionStyleCompat.DEFAULT.backgroundColor, view.field<CaptionStyleCompat>("style").backgroundColor)
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, view.field<Float>("defaultTextSize"), 0.0001f)
        assertTrue(view.field<Boolean>("applyEmbeddedFontSizes"))
    }

    @Test fun liftsAreWorkedOutFromTheHighestThingAtTheBottom() {
        assertEquals(0f, SubtitleLook.lift(500f, emptyList(), 24f), 0f)
        // Text ends at 500; something's top at 450: up to 24 above it.
        assertEquals(74f, SubtitleLook.lift(500f, listOf(450f, 480f), 24f), 0.001f)
        // Well clear already: stays.
        assertEquals(0f, SubtitleLook.lift(400f, listOf(450f), 24f), 0f)
    }

    @Test fun subtitlesRiseAboveTheControlsAndAMessageAndComeBack() {
        AppMotion.reduced = true // there on the next frame
        try {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val root = FrameLayout(activity)
            val player = activity.layoutInflater.inflate(R.layout.tv_player_view, root, false) as PlayerView
            root.addView(player)
            val message = PlayerStyle.message(activity).apply { text = "Sleeping in 30 minutes" }
            root.addView(message, PlayerStyle.messageParams(activity))
            activity.setContentView(root)
            settle()
            val subtitles = player.subtitleView!!
            val place = SubtitlePlace(root, subtitles)
            val d = activity.resources.displayMetrics.density

            // The controls up: text's bottom edge 12 dp above the title row's top.
            player.showController()
            settle()
            val infoRow = player.findViewById<View>(R.id.player_info_row)
            place.update(listOf(infoRow), fade = false)
            settle()
            val rowTop = IntArray(2).also(infoRow::getLocationInWindow)[1] - IntArray(2).also(root::getLocationInWindow)[1]
            val textBottom = subtitles.height * (1 - SubtitleLook.BOTTOM_PADDING) + subtitles.translationY
            assertEquals(rowTop - 12 * d, textBottom, 1f)
            // About 97 dp on the 960 × 540 screen, as the spec has it (a little more: this row has no eyebrow).
            assertTrue("${place.lift / d} dp", place.lift / d in 60f..110f)

            // A message alone: lower than the controls, so the subtitles come down to just above it.
            player.hideController()
            settle()
            place.update(listOf(message), fade = false)
            settle()
            assertEquals(message.top - 12 * d, subtitles.height * (1 - SubtitleLook.BOTTOM_PADDING) + subtitles.translationY, 1f)

            // Nothing at the bottom: back in Media3's own place.
            place.update(emptyList(), fade = false)
            settle()
            assertEquals(0f, subtitles.translationY, 0f)

            // Up next: faded away, and back.
            place.update(emptyList(), fade = true)
            settle()
            assertEquals(0f, subtitles.alpha, 0f)
            place.update(emptyList(), fade = false)
            settle()
            assertEquals(1f, subtitles.alpha, 0f)
        } finally {
            AppMotion.reduced = false
        }
    }

    @Test fun onAWideFilmTheyRiseFromThePicturesOwnBottom() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        // A 2.39:1 picture letterboxed in the middle of the screen, as Media3 frames it.
        val frame = FrameLayout(activity)
        val subtitles = SubtitleView(activity)
        frame.addView(subtitles, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val d = activity.resources.displayMetrics.density
        root.addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (402 * d).toInt(), android.view.Gravity.CENTER))
        val bar = View(activity)
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (130 * d).toInt(), android.view.Gravity.BOTTOM))
        activity.setContentView(root)
        settle()
        val place = SubtitlePlace(root, subtitles)
        // The picture ends at 471 dp, its text at 439 dp; the bar starts at 410 dp: up 41 dp.
        assertEquals(41f, place.liftFor(listOf(bar)) / d, 1f)
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }
}
