package com.woodruff.tvexplorer.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.woodruff.tvexplorer.R
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player's scrub bar, drawn the way tvOS draws it: a thin rounded bar (thicker while it has
 * focus), white for what's played over a faint track, the time played under its left end and the
 * time left ("-12:34") under its right. While scrubbing, a white playhead marks the spot and the
 * times follow it.
 *
 * Left / Right scrub (10 s a press, faster the longer one is held); the spot is played a moment
 * after the last press, or at once with OK. [cancelScrub] (Back) goes back to where it was.
 * Media3's controller drives it like its own bar ([TimeBar]): the position, what's buffered,
 * and seeking when a scrub ends.
 */
@OptIn(UnstableApi::class)
class TvTimeBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), TimeBar {
    private val d = resources.displayMetrics.density
    private val listeners = CopyOnWriteArraySet<TimeBar.OnScrubListener>()

    private var duration = 0L
    private var position = 0L
    private var buffered = 0L

    /** Scrubbing with the remote: [scrubPosition] is the spot shown, not yet played. */
    var isScrubbing = false
        private set
    private var scrubPosition = 0L
    private val commit = Runnable { stopScrub(canceled = false) }

    /** 0 at rest, 1 with focus: the bar grows from [BAR_DP] to [BAR_FOCUSED_DP]. */
    private var grow = 0f
    private var growAnim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val playhead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(6 * d, 0f, 1 * d, 0x80000000.toInt())
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 17 * resources.displayMetrics.scaledDensity
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter_semibold) }.getOrNull()
        // Digits all one width, so the times don't jiggle as they count.
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = ((BAR_AREA_DP + LABELS_DP) * d).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    /** Where [positionMs] falls along the bar, in this view's own pixels. */
    fun xFor(positionMs: Long): Float =
        if (duration <= 0) 0f else width * (positionMs.coerceIn(0, duration).toFloat() / duration)

    override fun onDraw(canvas: Canvas) {
        val barH = (BAR_DP + (BAR_FOCUSED_DP - BAR_DP) * grow) * d
        val cy = BAR_AREA_DP * d / 2
        val r = barH / 2
        val w = width.toFloat()
        rect.set(0f, cy - r, w, cy + r)
        canvas.drawRoundRect(rect, r, r, track)
        val shown = if (isScrubbing) scrubPosition else position
        if (duration > 0) {
            val bx = xFor(buffered)
            if (bx > 0) { rect.set(0f, cy - r, bx.coerceAtLeast(barH), cy + r); canvas.drawRoundRect(rect, r, r, bufferedPaint) }
            val px = xFor(shown)
            if (px > 0) { rect.set(0f, cy - r, px.coerceAtLeast(barH), cy + r); canvas.drawRoundRect(rect, r, r, played) }
            if (isScrubbing) {
                val hw = PLAYHEAD_W_DP * d / 2
                val hh = (barH + PLAYHEAD_OVER_DP * d) / 2
                val x = px.coerceIn(hw, w - hw)
                rect.set(x - hw, cy - hh, x + hw, cy + hh)
                canvas.drawRoundRect(rect, hw, hw, playhead)
            }
        }
        // The times under the bar: white while scrubbing, as they follow the playhead.
        label.color = if (isScrubbing) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        val base = BAR_AREA_DP * d + LABEL_BASELINE_DP * d
        canvas.drawText(clock(shown), 0f, base, label)
        if (duration > 0) {
            val left = "-" + clock(duration - shown)
            canvas.drawText(left, w - label.measureText(left), base, label)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && isScrubbing) stopScrub(canceled = false)
        growAnim?.cancel()
        growAnim = ValueAnimator.ofFloat(grow, if (gainFocus) 1f else 0f).apply {
            duration = GROW_MS
            interpolator = EASE
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && duration > 0) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isScrubbing) startScrub()
                val step = stepFor(event.repeatCount)
                scrubPosition = (scrubPosition + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step).coerceIn(0, duration)
                for (l in listeners) l.onScrubMove(this, scrubPosition)
                removeCallbacks(commit)
                postDelayed(commit, COMMIT_MS)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (isScrubbing) {
                stopScrub(canceled = false)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Back while scrubbing: back to where it was, nothing played. False when not scrubbing. */
    fun cancelScrub(): Boolean {
        if (!isScrubbing) return false
        stopScrub(canceled = true)
        return true
    }

    private fun startScrub() {
        isScrubbing = true
        scrubPosition = position
        isPressed = true
        for (l in listeners) l.onScrubStart(this, scrubPosition)
    }

    private fun stopScrub(canceled: Boolean) {
        removeCallbacks(commit)
        if (!isScrubbing) return
        isScrubbing = false
        isPressed = false
        // Shown where it lands at once (the player catches up a moment later).
        if (!canceled) position = scrubPosition
        for (l in listeners) l.onScrubStop(this, scrubPosition, canceled)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        if (isScrubbing) stopScrub(canceled = true)
        growAnim?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- TimeBar --------------------------------------------------------------------------------

    override fun addListener(listener: TimeBar.OnScrubListener) { listeners.add(listener) }
    override fun removeListener(listener: TimeBar.OnScrubListener) { listeners.remove(listener) }
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled && isScrubbing) stopScrub(canceled = true)
    }
    // Its own steps instead ([stepFor]).
    override fun setKeyTimeIncrement(time: Long) = Unit
    override fun setKeyCountIncrement(count: Int) = Unit
    override fun setPosition(position: Long) { if (this.position != position) { this.position = position; if (!isScrubbing) invalidate() } }
    override fun setBufferedPosition(bufferedPosition: Long) { if (buffered != bufferedPosition) { buffered = bufferedPosition; invalidate() } }
    override fun setDuration(duration: Long) {
        val known = if (duration == C.TIME_UNSET) 0L else duration
        if (known == this.duration) return
        this.duration = known
        if (isScrubbing && known <= 0) stopScrub(canceled = true)
        invalidate()
    }
    // How long a pixel of bar lasts: no point updating more often than the fill can move.
    override fun getPreferredUpdateDelay(): Long = if (width <= 0 || duration <= 0) Long.MAX_VALUE else duration / width
    override fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int) = Unit

    companion object {
        private const val BAR_DP = 6f
        private const val BAR_FOCUSED_DP = 10f
        private const val BAR_AREA_DP = 28f
        private const val LABELS_DP = 28f
        private const val LABEL_BASELINE_DP = 20f
        private const val PLAYHEAD_W_DP = 4f
        private const val PLAYHEAD_OVER_DP = 18f
        private const val GROW_MS = 180L
        /** The spot is played this long after the last press (OK plays it at once). */
        private const val COMMIT_MS = 1_200L
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

        /** How far a press moves: 10 s, then 30 s and a minute as Left / Right is held down. */
        internal fun stepFor(repeatCount: Int): Long = when {
            repeatCount < 8 -> 10_000L
            repeatCount < 24 -> 30_000L
            else -> 60_000L
        }

        /** "4:05", "1:02:09": as tvOS shows times. */
        internal fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0) + 500) / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
        }
    }
}
