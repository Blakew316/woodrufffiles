package com.woodruff.tvexplorer.notify

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.woodruff.tvexplorer.R

/**
 * A small card in the top-right corner over any app (needs "Display over other apps"). Plain
 * Views rather than Compose, because an overlay window has no Activity lifecycle to host Compose.
 * It never takes focus, so the remote keeps controlling whatever is playing.
 */
object NoticeOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null

    fun show(context: Context, n: Notice) = main.post {
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        current?.let { runCatching { wm.removeView(it) } }
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
        val params = WindowManager.LayoutParams(
            dp(380), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(32); y = dp(32) }
        try {
            wm.addView(card, params)
            current = card
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(250).start()
            main.postDelayed({
                card.animate().alpha(0f).setDuration(300).withEndAction {
                    runCatching { wm.removeView(card) }
                    if (current === card) current = null
                }.start()
            }, 7000)
        } catch (e: Exception) {
            // Permission revoked between the check and now; the history list still has it.
        }
    }
}
