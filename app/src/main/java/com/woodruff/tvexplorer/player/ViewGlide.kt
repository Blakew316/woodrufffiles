package com.woodruff.tvexplorer.player

import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.ui.motion.AppMotion

/**
 * Glides a view's drawing properties (its alpha, a vertical shift, a zoom) to new values on the app
 * windows' curve (res/interpolator/tv_glide) or the [curve] given, a frame at a time on the display's
 * clock. Only those properties change: the render thread draws the view's last picture faded or
 * moved, and nothing is laid out or drawn again. Unlike ViewPropertyAnimator (an Animator), it plays
 * at the same speed whatever Android's animation scales are: with them off, as on the owner's TV,
 * animators jump straight to their end. Like the rest of the app's motion ([AppMotion]), it only
 * stops gliding with Settings → Appearance → Reduce motion: everything then gets where it's going on
 * the next frame. Main thread only.
 */
internal class ViewGlide(
    private val view: View,
    curve: Interpolator = AnimationUtils.loadInterpolator(view.context, R.interpolator.tv_glide),
) {
    private val ease: (Float) -> Float = { curve.getInterpolation(it) }
    private val fade = GlideTrack()
    private val shift = GlideTrack()
    private val zoom = GlideTrack()
    private var then: (() -> Unit)? = null
    private var framing = false
    private val frame = Runnable { step() }

    fun fadeTo(alpha: Float, ms: Int) = start(fade, view.alpha, alpha, ms)
    fun shiftTo(translationY: Float, ms: Int) = start(shift, view.translationY, translationY, ms)
    fun zoomTo(scale: Float, ms: Int) = start(zoom, view.scaleX, scale, ms)

    /** [action] runs once everything under way has arrived (in place of an earlier one; null: none). */
    fun then(action: (() -> Unit)?) {
        then = action
    }

    /** Stops everything where it is, without the [then]. */
    fun stop() {
        fade.stop(); shift.stop(); zoom.stop()
        then = null
        if (framing) { framing = false; view.removeCallbacks(frame) }
    }

    private fun start(track: GlideTrack, from: Float, to: Float, ms: Int) {
        track.start(from, to, AnimationUtils.currentAnimationTimeMillis(), if (AppMotion.reduced) 0 else ms)
        if (!framing) { framing = true; view.postOnAnimation(frame) }
    }

    private fun step() {
        framing = false
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (fade.moving) view.alpha = fade.at(now, ease)
        if (shift.moving) view.translationY = shift.at(now, ease)
        if (zoom.moving) zoom.at(now, ease).let { view.scaleX = it; view.scaleY = it }
        if (fade.moving || shift.moving || zoom.moving) {
            framing = true
            view.postOnAnimation(frame)
        } else {
            then?.let { then = null; it() }
        }
    }
}

/**
 * One property on its way from one value to another in a given time, on a curve: where it is at a
 * moment (in the display clock's milliseconds), and whether it's still on its way.
 */
internal class GlideTrack {
    var moving = false
        private set
    private var from = 0f
    private var to = 0f
    private var startMs = 0L
    private var ms = 1

    /** From [from] to [to] in [ms], starting at [nowMs]. */
    fun start(from: Float, to: Float, nowMs: Long, ms: Int) {
        this.from = from
        this.to = to
        startMs = nowMs
        this.ms = ms.coerceAtLeast(1)
        moving = true
    }

    fun stop() {
        moving = false
    }

    /** Its value at [nowMs], [curve] giving how far along it is for how much of its time has gone. Arrived, it stops. */
    fun at(nowMs: Long, curve: (Float) -> Float): Float {
        val t = ((nowMs - startMs).toFloat() / ms).coerceIn(0f, 1f)
        if (t >= 1f) {
            moving = false
            return to
        }
        return from + (to - from) * curve(t)
    }
}
