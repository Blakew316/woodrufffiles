package com.woodruff.tvexplorer.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import com.woodruff.tvexplorer.ui.motion.AppMotion

/**
 * The loading ring over the film (specs/player-feedback.md §3.1): a small glass disc in the middle
 * of the screen with a white ring turning in it and, when there's something to say, a caption card
 * under it ("Reconnecting…"). It comes and goes like the player's other pop-ups ([GlidingCard], in
 * from just larger), never takes focus or keys, and sits over the controls and under the menus. The
 * disc stays in the middle when words come and go under it; a caption alone (a sign-in that ended)
 * sits in the middle itself. What it shows is decided by [Buffering]. Main thread only.
 */
internal class BufferingIndicator(context: Context, root: FrameLayout) {
    private val ring = BufferingRing(context)
    private val disc = FrameLayout(context).apply {
        background = PlayerStyle.glass(context, DISC_DP / 2)
        elevation = PlayerStyle.dp(context, 12f)
        val size = PlayerStyle.dp(context, RING_DP).toInt()
        addView(ring, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        contentDescription = "Loading"
    }
    private val caption = PlayerStyle.caption(context).apply {
        // Read out when it changes, for anyone using the TV's screen reader.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val layer = RingLayout(context, disc, caption)
    private val card = GlidingCard(layer, GlidingCard.Way.Zoom)

    /** What it shows; null while it's hidden (or on its way out). */
    var look: BufferingLook? = null
        private set

    init {
        root.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Shows [look], gliding in if it wasn't up; new words replace the old in place. */
    fun show(look: BufferingLook) {
        if (look != this.look) {
            this.look = look
            disc.visibility = if (look.ring) View.VISIBLE else View.GONE
            caption.text = words(look)
            caption.visibility = if (look.caption == null) View.GONE else View.VISIBLE
        }
        if (look.ring) ring.spin() else ring.stop()
        card.show()
    }

    /** Glides it away ([animate] false: gone at once, the player stopping). The ring stops once it's gone. */
    fun hide(animate: Boolean = true) {
        val wasUp = look != null
        look = null
        if (wasUp || !animate) card.hide(animate) { ring.stop() }
    }

    /** The caption, with its quieter second line (smaller, in the player's secondary white) when there's one. */
    private fun words(look: BufferingLook): CharSequence {
        val detail = look.detail ?: return look.caption.orEmpty()
        return SpannableStringBuilder(look.caption.orEmpty()).apply {
            append("\n")
            val from = length
            append(detail)
            setSpan(ForegroundColorSpan(PlayerStyle.SECONDARY), from, length, 0)
            setSpan(RelativeSizeSpan(DETAIL_SP / CAPTION_SP), from, length, 0)
        }
    }

    /**
     * Fills the screen without drawing anything itself: the disc in the very middle, the caption
     * [GAP_DP] under it, or the caption in the middle when there's no disc. (Stacked in a centred
     * column instead, the ring would jump up each time words appeared under it.)
     */
    private class RingLayout(context: Context, private val disc: View, private val caption: View) : ViewGroup(context) {
        private val discPx = PlayerStyle.dp(context, DISC_DP).toInt()
        private val gapPx = PlayerStyle.dp(context, GAP_DP).toInt()

        init {
            clipChildren = false
            clipToPadding = false
            addView(disc)
            addView(caption)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            disc.measure(MeasureSpec.makeMeasureSpec(discPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(discPx, MeasureSpec.EXACTLY))
            caption.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val cx = (r - l) / 2
            val cy = (b - t) / 2
            val captionTop = if (disc.visibility != View.GONE) {
                val half = disc.measuredWidth / 2
                disc.layout(cx - half, cy - half, cx - half + disc.measuredWidth, cy - half + disc.measuredHeight)
                cy - half + disc.measuredHeight + gapPx
            } else {
                cy - caption.measuredHeight / 2
            }
            if (caption.visibility != View.GONE) {
                val left = cx - caption.measuredWidth / 2
                caption.layout(left, captionTop, left + caption.measuredWidth, captionTop + caption.measuredHeight)
            }
        }
    }

    companion object {
        const val DISC_DP = 56f
        const val RING_DP = 28f
        const val GAP_DP = 12f
        /** The caption's size (PlayerStyle.caption) and its second line's. */
        const val CAPTION_SP = 16f
        const val DETAIL_SP = 14f
    }
}

/**
 * The turning ring: a 270° white arc, drawn once, turned a full circle every [TURN_MS] by changing
 * only its rotation (a property of its RenderNode, so nothing is drawn again), a display frame at
 * a time on the display's clock. Like the player's other motion ([ViewGlide]) it keeps its speed
 * whatever Android's animation scales say (an Animator would stand still on a TV with animations
 * off), and it doesn't turn with Settings → Appearance → Reduce motion. It turns only between
 * [spin] and [stop]: nothing runs while it's hidden.
 */
internal class BufferingRing(context: Context) : View(context) {
    private val stroke = PlayerStyle.dp(context, STROKE_DP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val arc = RectF()
    private val frame = Runnable { turn() }
    private var startMs = 0L

    /** It's turning (a frame is due). */
    var spinning = false
        private set

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        arc.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(arc, -90f, SWEEP, false, paint)
    }

    /** Starts it turning, from wherever it stopped (not with a jump back to the top). */
    fun spin() {
        if (AppMotion.reduced) { stop(); return }
        if (spinning) return
        spinning = true
        startMs = AnimationUtils.currentAnimationTimeMillis() - (rotation / 360f * TURN_MS).toLong()
        postOnAnimation(frame)
    }

    fun stop() {
        spinning = false
        removeCallbacks(frame)
    }

    private fun turn() {
        if (!spinning) return
        val t = (AnimationUtils.currentAnimationTimeMillis() - startMs) % TURN_MS
        rotation = t * 360f / TURN_MS
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        /** One turn a second, at an even speed. */
        const val TURN_MS = 1_000L
        const val SWEEP = 270f
        const val STROKE_DP = 3f
    }
}
