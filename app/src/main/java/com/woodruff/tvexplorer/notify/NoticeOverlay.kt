package com.woodruff.tvexplorer.notify

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.player.GlidingCard
import com.woodruff.tvexplorer.ui.motion.AppMotion
import com.woodruff.tvexplorer.ui.motion.TvMotion

/**
 * A small card in the top-right corner over any app (needs "Display over other apps"). Plain
 * Views rather than Compose, because an overlay window has no Activity lifecycle to host Compose.
 * It never takes focus, so the remote keeps controlling whatever is playing.
 *
 * It glides down from the screen's top edge as it fades in, and back up when it goes, as the in-app
 * notice does. That's its window's animation ([R.style.Animation_TVExplorer_Notice]): WindowManager
 * runs it on the system's own thread, so it stays smooth while the TV is busy with whatever is
 * playing (this app is in the background then), and it keeps a removed card on screen until its exit
 * has run, as it does for toasts. With the TV's window animations off, WindowManager would just show
 * it and take it away: then the card glides in its window itself ([GlidingCard]), and the window goes
 * once the card has glided out.
 */
object NoticeOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var current: Shown? = null
    /** Takes [current] away once its time is up. */
    private var dismiss: Runnable? = null

    /** A notice on screen: its window's view, and its card's own glide when the window doesn't animate. */
    private class Shown(val root: View, val glide: GlidingCard?)

    fun show(context: Context, n: Notice) = main.post {
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        // A newer notice replaces the one up: that one glides away as this one comes in over it.
        dismiss?.let { main.removeCallbacks(it) }
        dismiss = null
        current?.let { takeAway(wm, it) }
        current = null
        val density = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val font = runCatching { ResourcesCompat.getFont(app, R.font.inter_semibold) }.getOrNull()
        val fontBody = runCatching { ResourcesCompat.getFont(app, R.font.inter_regular) }.getOrNull()

        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF20E1726.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            addView(TextView(app).apply {
                text = when (n.source) { NoticeSource.PHONE -> "From your phone"; NoticeSource.MAIL -> "New mail"; NoticeSource.SYSTEM -> "Woodruff Family" }
                setTextColor(0xFF6FB3E6.toInt()); textSize = 12f; typeface = font ?: Typeface.DEFAULT_BOLD
            })
            addView(TextView(app).apply {
                text = n.title; setTextColor(0xFFF2F4F7.toInt()); textSize = 17f; maxLines = 2
                typeface = font ?: Typeface.DEFAULT_BOLD
            })
            if (n.body.isNotBlank()) addView(TextView(app).apply {
                text = n.body; setTextColor(0xFFB8C2CF.toInt()); textSize = 14f; maxLines = 3
                typeface = fontBody ?: Typeface.DEFAULT
            })
        }
        // Its window reaches up to the screen's top edge (see-through above the card, which still rests
        // 32 dp down), so the card glides down from that edge, whichever of the two moves it.
        val root = FrameLayout(app).apply {
            setPadding(0, dp(32), 0, 0)
            // Cut off at the window's edge, not at the padding (32 dp down) as a FrameLayout would.
            clipToPadding = false
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        // Read now: the app may not have been on screen since the TV's settings last changed.
        AppMotion.refresh(app)
        val glide = if (AppMotion.windowsAnimate || AppMotion.reduced) null else GlidingCard(card, GlidingCard.Way.Drop)
        val params = WindowManager.LayoutParams(
            dp(380), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(32); y = 0
            windowAnimations = R.style.Animation_TVExplorer_Notice
        }
        try {
            // Already on its way in (from its first frame) when it glides itself.
            glide?.show()
            wm.addView(root, params)
            val shown = Shown(root, glide)
            current = shown
            // Its time up, it glides away.
            val away = Runnable {
                if (current === shown) { current = null; dismiss = null }
                takeAway(wm, shown)
            }
            dismiss = away
            main.postDelayed(away, n.showMs)
        } catch (e: Exception) {
            // Permission revoked between the check and now, or a TV that refuses pop-ups: a regular
            // notification instead (the history list has it either way).
            Notices.systemNotification(app, n)
        }
    }

    /** Off the screen: its window goes (and its animation glides it away), or its card glides out first. */
    private fun takeAway(wm: WindowManager, s: Shown) {
        val glide = s.glide
        if (glide == null) { runCatching { wm.removeView(s.root) }; return }
        glide.hide { runCatching { wm.removeView(s.root) } }
        // Whatever happens to the glide, the window never stays (removing it twice does nothing).
        main.postDelayed({ runCatching { wm.removeView(s.root) } }, TvMotion.DismissMs + 1_000L)
    }

    /**
     * The window type for drawing over other apps. TYPE_APPLICATION_OVERLAY only exists from
     * Android 8; Android 7 TVs (some older Fire TV sticks) need the older TYPE_PHONE, or the card
     * never shows.
     */
    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
}
