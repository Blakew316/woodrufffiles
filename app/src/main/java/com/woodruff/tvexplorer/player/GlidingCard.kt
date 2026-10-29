package com.woodruff.tvexplorer.player

import android.view.View
import android.view.ViewTreeObserver
import com.woodruff.tvexplorer.ui.motion.TvMotion

/**
 * A card that glides in and out instead of popping, the way the app's windows do: on their curve and
 * [TvMotion]'s window numbers, in in [TvMotion.PresentMs] (its fade a little quicker) and out in
 * [TvMotion.DismissMs]. The player's cards over the film (Skip intro, Up next, the doorbell's, the
 * title, the scrubbing preview, its messages and options) and a notice over other apps. Moved by
 * [ViewGlide]: drawing properties only (alpha, a shift, a zoom), faded part by part (as the app's
 * ModulateAlpha) with no offscreen copy of the card, and at normal speed whatever Android's animation
 * scales are.
 *
 * [shown] is what to act on (OK, Back): a card on its way out no longer is. It stays on screen as it
 * glides away, and only then goes (GONE).
 */
internal class GlidingCard(private val view: View, private val way: Way) {
    /** How it comes and goes: up from below its place, down from above it, or in from just larger (a pop-up). */
    enum class Way { Rise, Drop, Zoom }

    var shown = false
        private set

    private val glide = ViewGlide(view)
    // Showing, but not laid out yet: how far it glides is its own height, known just before it's drawn.
    private var waiting = false
    private var gone: (() -> Unit)? = null

    init {
        view.forceHasOverlappingRendering(false)
        view.visibility = View.GONE
    }

    fun set(show: Boolean) = if (show) show() else hide()

    fun show() {
        if (shown) return
        shown = true
        gone = null
        // Still gliding out: it turns back from where it is.
        if (view.visibility == View.VISIBLE) { glideIn(); return }
        glide.stop()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        waiting = true
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // The observer it was added to may since have been merged into the window's.
                view.viewTreeObserver.removeOnPreDrawListener(this)
                if (waiting) {
                    waiting = false
                    when (way) {
                        Way.Rise -> view.translationY = view.height.toFloat()
                        Way.Drop -> view.translationY = -view.height.toFloat()
                        Way.Zoom -> { view.scaleX = TvMotion.DialogScale; view.scaleY = TvMotion.DialogScale }
                    }
                    glideIn()
                }
                return true
            }
        })
    }

    /** [animate] false: gone at once (the player stopping). [then]: runs once it's gone. */
    fun hide(animate: Boolean = true, then: (() -> Unit)? = null) {
        if (then != null) gone = gone.let { earlier -> if (earlier == null) then else ({ earlier(); then() }) }
        // Nothing on screen (most calls: the player asks every second): gone already.
        if (view.visibility == View.GONE) { shown = false; gone?.let { gone = null; it() }; return }
        // Already on its way out: it's told once it's gone.
        if (!shown && animate) return
        shown = false
        if (!animate || waiting) { done(); return }
        glide.fadeTo(0f, TvMotion.DismissMs)
        when (way) {
            Way.Rise -> glide.shiftTo(view.height.toFloat(), TvMotion.DismissMs)
            Way.Drop -> glide.shiftTo(-view.height.toFloat(), TvMotion.DismissMs)
            Way.Zoom -> glide.zoomTo(TvMotion.DialogDismissScale, TvMotion.DismissMs)
        }
        glide.then { if (!shown) done() }
    }

    private fun glideIn() {
        if (way == Way.Zoom) glide.zoomTo(1f, TvMotion.PresentMs) else glide.shiftTo(0f, TvMotion.PresentMs)
        glide.fadeTo(1f, TvMotion.PresentFadeMs)
        glide.then(null)
    }

    private fun done() {
        waiting = false
        glide.stop()
        view.visibility = View.GONE
        view.alpha = 1f
        // Only what it moves: the scrubbing preview's own shift places it over the bar.
        if (way == Way.Zoom) { view.scaleX = 1f; view.scaleY = 1f } else view.translationY = 0f
        gone?.let { gone = null; it() }
    }
}
