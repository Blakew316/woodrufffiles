package com.woodruff.tvexplorer.player

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * How big subtitles are in the player (specs/player-feedback.md §3.6), chosen in the player's
 * Subtitles menu and in Settings → Appearance. [fraction] is of the screen's height: Standard is
 * Media3's own size on a 16:9 film.
 */
internal enum class SubtitleSize(val key: String, val label: String, val fraction: Float) {
    SMALL("small", "Small", 0.045f),
    STANDARD("standard", "Standard", 0.0533f),
    LARGE("large", "Large", 0.064f);

    /** The next size along, for Settings' row (OK cycles Small → Standard → Large → Small). */
    fun next(): SubtitleSize = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String?): SubtitleSize = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/**
 * What text subtitles look like, as Apple TV and Netflix draw them (specs/player-feedback.md §3.5):
 * clean white letters in Woodruff's typeface with a soft dark edge, never in black boxes, at the
 * size chosen. When the TV's own caption settings are on, they win, as they do in every app. Picture
 * subtitles (a Blu-ray's) are drawn as the film made them, whatever is chosen here. Also where
 * subtitles go when something appears at the bottom of the screen ([lift]).
 */
@OptIn(UnstableApi::class)
internal object SubtitleLook {
    private const val KEY = "subtitle_size"

    /** Media3's own place for text subtitles: their bottom edge 8 % of the picture's height above its bottom. */
    const val BOTTOM_PADDING = 0.08f

    /** How far above something at the bottom of the screen subtitles rise to (§3.7), in dp. */
    const val GAP_DP = 12f

    /**
     * The edge around each letter: a thin near-black outline. §3.5 asks for a soft drop shadow unless
     * that fails over a bright sky, and it did: in the gallery's shot the shadow drew only the letters'
     * lower right, and their tops and left sides melted into the sky. The outline holds on anything.
     */
    const val EDGE = CaptionStyleCompat.EDGE_TYPE_OUTLINE
    private const val EDGE_COLOR = 0xF2000000.toInt()

    /** Formats that are pictures of words (Blu-ray, DVD, broadcast): no style or size of ours applies. */
    private val PICTURES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** The size chosen (the `player` settings file, warmed as Woodruff starts). */
    fun size(c: Context): SubtitleSize = SubtitleSize.of(prefs(c).getString(KEY, null))

    fun setSize(c: Context, size: SubtitleSize) {
        prefs(c).edit().putString(KEY, size.key).apply()
    }

    /** Whether the TV's own caption settings are on (Settings → Accessibility → Captions): then they choose. */
    fun tvCaptionsOn(c: Context): Boolean =
        runCatching { (c.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager)?.isEnabled == true }.getOrDefault(false)

    /** Whether [mime] (a subtitle track's own format) is pictures rather than text. */
    fun isPictures(mime: String?): Boolean = mime in PICTURES

    /**
     * Styles [view] (the player's subtitles): with the TV's caption settings on ([tvCaptions]),
     * Media3's own handling of them; otherwise white letters in Inter SemiBold with a near-black edge
     * and no boxes, [size] tall as a share of the screen ([screenHeightPx]: Media3 measures its own
     * sizes against the picture, which made subtitles a quarter smaller on 2.39:1 films than on 16:9
     * ones), with SSA's own font sizes set aside so the size choice holds for them too, but their
     * positions and colours kept (signs stay where the film puts them).
     */
    fun apply(view: SubtitleView, c: Context, size: SubtitleSize, tvCaptions: Boolean, screenHeightPx: Int) {
        view.setBottomPaddingFraction(BOTTOM_PADDING)
        if (tvCaptions) {
            view.setUserDefaultStyle()
            view.setUserDefaultTextSize()
            view.setApplyEmbeddedStyles(true)
            view.setApplyEmbeddedFontSizes(true)
            return
        }
        view.setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, EDGE, EDGE_COLOR, PlayerStyle.semibold(c)))
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, size.fraction * screenHeightPx)
        view.setApplyEmbeddedFontSizes(false)
        view.setApplyEmbeddedStyles(true)
    }

    /**
     * How far subtitles rise (§3.7) so their bottom edge, at [cueBottom], sits [gap] above the
     * highest thing at the bottom of the screen ([obstacleTops]: the controls, a Skip Intro pill, a
     * message); 0 with nothing there, or nothing in the way. All in the same pixels, top down.
     */
    fun lift(cueBottom: Float, obstacleTops: List<Float>, gap: Float): Float {
        val highest = obstacleTops.minOrNull() ?: return 0f
        return (cueBottom - (highest - gap)).coerceAtLeast(0f)
    }
}

/**
 * Where the player's subtitles sit (specs/player-feedback.md §3.7): Media3's own place while nothing
 * is at the bottom of the screen; risen so their bottom edge is 12 dp above whatever comes up there
 * (the controls, Skip Intro or Next Episode, a message), gliding up as it comes ([TvMotion.PresentMs])
 * and back down as the last one goes ([TvMotion.DismissMs]); faded away while Up next is up. Only the
 * layer moves, so picture subtitles rise too, and nothing is laid out or drawn again. [subtitles] is
 * Media3's subtitle view, which fills the picture's own frame (so on a 2.39:1 film its text sits
 * inside the picture, above the black bar); [root] is the player's whole screen. Main thread.
 */
internal class SubtitlePlace(private val root: android.view.View, private val subtitles: android.view.View) {
    private val glide = ViewGlide(subtitles)

    /** How far they're risen (or rising), in pixels. */
    var lift = 0f
        private set
    private var faded = false

    /** Places them for what's showing at the bottom now ([obstacles], each laid out), faded away while [fade]. */
    fun update(obstacles: List<android.view.View>, fade: Boolean) {
        if (fade != faded) {
            faded = fade
            glide.fadeTo(if (fade) 0f else 1f, com.woodruff.tvexplorer.ui.motion.TvMotion.DismissMs)
        }
        val target = liftFor(obstacles)
        if (target == lift) return
        val rising = target > lift
        lift = target
        glide.shiftTo(-target, if (rising) com.woodruff.tvexplorer.ui.motion.TvMotion.PresentMs else com.woodruff.tvexplorer.ui.motion.TvMotion.DismissMs)
    }

    /** How far they'd rise for [obstacles] ([SubtitleLook.lift]): from the picture's frame, where text sits 8 % above its bottom. */
    fun liftFor(obstacles: List<android.view.View>): Float {
        val frame = subtitles.parent as? android.view.View ?: return 0f
        if (!frame.isLaidOut || frame.height == 0) return 0f
        val cueBottom = topIn(frame) + frame.height * (1f - SubtitleLook.BOTTOM_PADDING)
        val tops = obstacles.filter { it.isLaidOut && it.height > 0 }.map(::topIn)
        return SubtitleLook.lift(cueBottom, tops, PlayerStyle.dp(root.context, SubtitleLook.GAP_DP))
    }

    /** Back in Media3's own place, at once (the player stopping, or starting afresh). */
    fun reset() {
        glide.stop()
        subtitles.translationY = 0f
        subtitles.alpha = 1f
        lift = 0f
        faded = false
    }

    /** [v]'s top on the player's screen as laid out, its own glide aside. */
    private fun topIn(v: android.view.View): Float {
        val at = IntArray(2).also(v::getLocationInWindow)
        val origin = IntArray(2).also(root::getLocationInWindow)
        return at[1] - origin[1] - v.translationY
    }
}
