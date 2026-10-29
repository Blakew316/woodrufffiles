package com.woodruff.tvexplorer.ui.motion

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.Lifecycle

/**
 * How fast the app's animations play, whatever Android's own animation settings say. TVs are often set
 * to "Animator duration scale: off" (Developer options, or Accessibility's Remove animations), and
 * Compose then finishes every animation on its first frame: the focus growing, the gliding highlight,
 * scrolling to the focus, cross-fades all jump instead of gliding. The motion is part of the app's
 * design, so here it plays at normal speed, unless Settings → Appearance → Reduce motion is on.
 */
object AppMotion : MotionDurationScale {
    /** Reduce motion: every animation ends at once, as with Android's own setting off. */
    @Volatile var reduced = false
    override val scaleFactor: Float get() = if (reduced) 0f else 1f

    /**
     * Whether Android animates windows (dialogs, pop-ups, screens of their own). The app can't play
     * those itself, so with Android's window animation scale off, dialogs glide in from inside instead
     * (see DialogFrame). Read again each time the app comes to the front.
     */
    @Volatile var windowsAnimate = true
        private set

    fun refresh(c: Context) {
        windowsAnimate = runCatching {
            Settings.Global.getFloat(c.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
}

/**
 * Compose content in this window plays its animations at [AppMotion]'s speed: the window's recomposer is
 * made here, with that scale, before any content is set (left to itself, Compose makes one that follows
 * Android's animator duration scale). Dialogs and pop-ups opened from this content share it. Main
 * thread, before setContent.
 */
fun View.useAppMotion(lifecycle: Lifecycle) {
    compositionContext = createLifecycleAwareWindowRecomposer(AppMotion, lifecycle)
}
