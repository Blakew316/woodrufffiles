package com.woodruff.tvexplorer.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.woodruff.tvexplorer.R

/**
 * How the player's own cards look, tvOS style: dark glass panels with a hairline edge, and white
 * pills for the one thing OK does. Inter stands in for San Francisco, as in the rest of the app.
 */
internal object PlayerStyle {
    /** Dark glass: Apple's secondary system background, a little see-through. */
    const val GLASS = 0xE61C1C1E.toInt()
    /** Text on a white pill or a focused row. */
    const val INK = 0xFF1C1C1E.toInt()
    const val SECONDARY = 0xB3FFFFFF.toInt()
    private const val HAIRLINE = 0x1FFFFFFF

    fun font(c: Context, id: Int): Typeface? = runCatching { ResourcesCompat.getFont(c, id) }.getOrNull()
    fun text(c: Context) = font(c, R.font.inter_medium)
    fun semibold(c: Context) = font(c, R.font.inter_semibold)
    fun bold(c: Context) = font(c, R.font.inter_bold)
    fun display(c: Context) = font(c, R.font.interdisplay_bold)

    fun dp(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    /** A dark glass panel with rounded corners. */
    fun glass(c: Context, radiusDp: Float, color: Int = GLASS): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(c, radiusDp)
        setStroke(dp(c, 1f).toInt().coerceAtLeast(1), HAIRLINE)
    }

    /** A rounded pill: white with dark text when it's what OK does ([lit]), glass otherwise. */
    fun pillBackground(c: Context, lit: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(c, 100f)
        if (lit) setColor(0xFFFFFFFF.toInt()) else { setColor(0x3DFFFFFF); setStroke(dp(c, 1f).toInt().coerceAtLeast(1), HAIRLINE) }
    }

    /** "Skip Intro", "Next Episode": the button OK presses, over the film at the bottom right. */
    fun pill(c: Context, label: String, icon: Int? = null): TextView = TextView(c).apply {
        text = label
        setTextColor(INK)
        textSize = 16f
        typeface = semibold(c)
        gravity = Gravity.CENTER_VERTICAL
        val h = dp(c, 22f).toInt()
        val v = dp(c, 11f).toInt()
        setPadding(if (icon != null) dp(c, 18f).toInt() else h, v, h, v)
        background = pillBackground(c, lit = true)
        elevation = dp(c, 10f)
        if (icon != null) {
            val dr = c.getDrawable(icon)?.mutate()?.apply {
                setTint(INK)
                val s = dp(c, 16f).toInt()
                setBounds(0, 0, s, s)
            }
            setCompoundDrawables(dr, null, null, null)
            compoundDrawablePadding = dp(c, 9f).toInt()
        }
    }

    /** A message or notice card over the film: glass, white text. */
    fun card(c: Context, sizeSp: Float = 17f): TextView = TextView(c).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = sizeSp
        typeface = text(c)
        setLineSpacing(dp(c, 3f), 1f)
        setPadding(dp(c, 22f).toInt(), dp(c, 15f).toInt(), dp(c, 22f).toInt(), dp(c, 15f).toInt())
        background = glass(c, 18f)
        elevation = dp(c, 12f)
    }
}
