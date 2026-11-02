package com.woodruff.tvexplorer.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import com.woodruff.tvexplorer.R

/**
 * The darkening behind the player's controls ([R.drawable.player_scrim]), drawn under the subtitles.
 *
 * It sits in the controls' layout as Media3's `exo_controls_background`, so Media3 fades it in and out
 * with the controls (its alpha, and its visibility at either end), but it draws nothing there: the
 * controls are a layer over the whole picture, subtitles included, and a scrim drawn with them turned
 * the subtitles grey once they had risen above the controls. Instead it puts its [twin] into the
 * picture's own frame, just under the subtitles, and keeps the twin's alpha in step with its own. The
 * film darkens behind the controls; the subtitles above them stay white.
 */
internal class ControlsScrim @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    // Set once constructed: View's own constructor may already set an alpha.
    private var twinView: View? = View(context).apply {
        background = context.getDrawable(R.drawable.player_scrim)
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** What's drawn: the scrim, in the PlayerView's picture frame under its subtitles while this is attached. */
    internal val twin: View get() = twinView!!

    /** Whether this (and so the controls) shows now. */
    private var shown = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val player = generateSequence(parent) { it.parent }.filterIsInstance<PlayerView>().firstOrNull() ?: return
        val frame = player.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        (twin.parent as? ViewGroup)?.removeView(twin)
        // Just under the subtitles: over the film, its shutter and artwork.
        val under = player.subtitleView?.let(frame::indexOfChild)?.takeIf { it >= 0 } ?: frame.childCount
        frame.addView(twin, under, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mirror()
    }

    override fun onDetachedFromWindow() {
        (twin.parent as? ViewGroup)?.removeView(twin)
        super.onDetachedFromWindow()
    }

    /** Media3 fades the controls with this: the twin fades with them. */
    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        mirror()
    }

    /** This, or the controls around it, coming or going: the twin with them. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        mirror()
    }

    private fun mirror() {
        twinView?.alpha = if (shown) alpha else 0f
    }
}
