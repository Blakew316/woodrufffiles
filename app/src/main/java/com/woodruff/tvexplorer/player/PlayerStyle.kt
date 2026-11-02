package com.woodruff.tvexplorer.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
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

    /**
     * The notice at the top right over the film (the doorbell's): the same compact card as the app's
     * notices elsewhere ([com.woodruff.tvexplorer.notify.NoticeLook]). Its second line is set smaller.
     */
    fun notice(c: Context): TextView = card(c, com.woodruff.tvexplorer.notify.NoticeLook.TITLE_SP).apply {
        val look = com.woodruff.tvexplorer.notify.NoticeLook
        setLineSpacing(dp(c, look.TITLE_LINE_SP - look.TITLE_SP), 1f)
        setPadding(dp(c, look.PAD_H_DP.toFloat()).toInt(), dp(c, look.PAD_V_DP.toFloat()).toInt(), dp(c, look.PAD_H_DP.toFloat()).toInt(), dp(c, look.PAD_V_DP.toFloat()).toInt())
        background = glass(c, look.CORNER_DP.toFloat())
        // As wide as its words (it's laid out wrap-content), up to the app's notices' widest.
        maxWidth = dp(c, look.MAX_WIDTH_DP.toFloat()).toInt()
        elevation = dp(c, 8f)
    }

    /** A message or notice card over the film: glass, white text. */
    fun card(c: Context, sizeSp: Float = 17f): TextView = asCard(c, TextView(c), sizeSp)

    private fun <T : TextView> asCard(c: Context, view: T, sizeSp: Float): T = view.apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = sizeSp
        typeface = text(c)
        setLineSpacing(dp(c, 3f), 1f)
        setPadding(dp(c, 22f).toInt(), dp(c, 15f).toInt(), dp(c, 22f).toInt(), dp(c, 15f).toInt())
        background = glass(c, 18f)
        elevation = dp(c, 12f)
    }

    /**
     * The player's own messages ("Sleeping in 30 minutes", a film's sound this TV can't play): a
     * card, centred, as wide as its words up to 3/5 of the screen; longer ones wrap into even lines
     * ([MessageText]). Placed by [messageParams].
     */
    fun message(c: Context): TextView = asCard(c, MessageText(c), 16f).apply {
        gravity = Gravity.CENTER
        maxWidth = c.resources.displayMetrics.widthPixels * 3 / 5
    }

    /** Where [message] sits over the film: at the bottom in the middle, 40 dp up. */
    fun messageParams(c: Context): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { setMargins(0, 0, 0, dp(c, 40f).toInt()) }
}

/**
 * A message's words: wrapped into lines of even length, the card only as wide as the longest line,
 * so a message that needs two lines reads as one centred block, not a full line and a stray word.
 * Never hyphenated (Android 9 hyphenated by default; later versions don't).
 */
internal class MessageText(c: Context) : TextView(c) {
    init {
        breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val lines = layout ?: return
        if (lines.lineCount < 2) return
        var widest = 0f
        for (i in 0 until lines.lineCount) widest = maxOf(widest, lines.getLineWidth(i))
        val tight = kotlin.math.ceil(widest).toInt() + compoundPaddingLeft + compoundPaddingRight
        if (tight < measuredWidth) super.onMeasure(MeasureSpec.makeMeasureSpec(tight, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
