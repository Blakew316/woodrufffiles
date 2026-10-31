package com.woodruff.tvexplorer.player

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.ui.motion.TvMotion

/**
 * A tvOS-style menu over the film: a dark rounded panel that pops up just above the button that
 * opened it (Subtitles, Audio, Speed, the "…" options), or in the middle of the screen. A white
 * platter glides from row to row behind them, and a ✓ marks what's chosen now. Up / Down, OK,
 * Back; it never takes focus, so the button that opened it keeps it.
 */
internal class TvMenu(private val context: Context, private val root: FrameLayout) {
    /** A row: what it says, whether it's the current choice, and what OK on it does. */
    class Row(val label: String, val checked: Boolean = false, val action: () -> Unit)

    private val rowHeight = dp(50f).toInt()
    private val header = TextView(context).apply {
        setTextColor(0x99FFFFFF.toInt())
        textSize = 15f
        typeface = PlayerStyle.semibold(context)
        letterSpacing = 0.02f
        setPadding(dp(18f).toInt(), dp(6f).toInt(), dp(18f).toInt(), dp(10f).toInt())
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val highlight = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(14f) }
    }
    private val highlightGlide = ViewGlide(highlight)
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(FrameLayout(context).apply {
            addView(highlight, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rowHeight))
            addView(rows, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        })
    }
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(10f).toInt()
        setPadding(pad, dp(14f).toInt(), pad, pad)
        background = PlayerStyle.glass(context, 24f, 0xF51C1C1E.toInt())
        elevation = dp(24f)
        minimumWidth = dp(300f).toInt()
        addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private val card = GlidingCard(panel, GlidingCard.Way.Zoom)

    private var items: List<Row> = emptyList()
    private var at = 0

    /** Up: Up / Down, OK and Back are its own. A menu on its way out no longer is. */
    val shown get() = card.shown

    /** Told when it opens (true) and closes (false): the player's controls stay up meanwhile. */
    var onShownChanged: ((Boolean) -> Unit)? = null

    init {
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Opens the menu with [rows], above [anchor] (right edges lined up), or centred without one. */
    fun show(title: String, rows: List<Row>, anchor: View? = null) {
        if (rows.isEmpty()) return
        items = rows
        header.text = title
        val checks = rows.any { it.checked }
        this.rows.removeAllViews()
        for (row in rows) this.rows.addView(rowView(row, checks), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight))
        at = rows.indexOfFirst { it.checked }.coerceAtLeast(0)

        // Its size: as wide as its longest row (within limits), as tall as fits on screen.
        val maxW = dp(560f).toInt()
        val margin = dp(24f).toInt()
        val spaceAbove = anchor?.let { locationIn(it)[1] - dp(16f).toInt() - margin } ?: (root.height - 2 * margin)
        val listH = rows.size * rowHeight
        header.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val chrome = panel.paddingTop + panel.paddingBottom + header.measuredHeight
        val maxList = (spaceAbove.coerceAtLeast(dp(200f).toInt()) - chrome).coerceAtLeast(rowHeight * 3)
        scroll.layoutParams = scroll.layoutParams.apply { height = if (listH > maxList) (maxList / rowHeight) * rowHeight - rowHeight / 2 else ViewGroup.LayoutParams.WRAP_CONTENT }
        // As wide as its longest row (rows fill the panel, so they're measured on their own).
        this.rows.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val natural = maxOf(this.rows.measuredWidth, header.measuredWidth) + panel.paddingLeft + panel.paddingRight
        val w = natural.coerceIn(panel.minimumWidth, maxW)
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        if (anchor != null && anchor.isShown) {
            val (ax, ay) = locationIn(anchor).let { it[0] to it[1] }
            val h = panel.measuredHeight.coerceAtMost(chrome + (scroll.layoutParams.height.takeIf { it > 0 } ?: listH))
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (ax + anchor.width - w).coerceIn(margin, (root.width - w - margin).coerceAtLeast(margin))
            lp.topMargin = (ay - dp(16f).toInt() - h).coerceAtLeast(margin)
            // It grows from the corner nearest the button.
            panel.pivotX = w.toFloat()
            panel.pivotY = h.toFloat()
        } else {
            lp.gravity = Gravity.CENTER
            lp.leftMargin = 0; lp.topMargin = 0
            panel.pivotX = w / 2f
            panel.pivotY = panel.measuredHeight / 2f
        }
        panel.layoutParams = lp
        mark(glide = false)
        val was = card.shown
        card.show()
        if (!was) onShownChanged?.invoke(true)
    }

    fun hide(animate: Boolean = true) {
        val was = card.shown
        card.hide(animate)
        if (was) onShownChanged?.invoke(false)
    }

    /** The keys it acts on while it's up; other keys (play, pause) go on to the player. */
    fun handleKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val action = items.getOrNull(at)?.action
                hide()
                action?.invoke()
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> hide()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit // nowhere to go sideways
            else -> return false
        }
        return true
    }

    private fun move(by: Int) {
        val to = (at + by).coerceIn(0, items.lastIndex.coerceAtLeast(0))
        if (to == at) return
        at = to
        mark(glide = true)
    }

    /** The platter onto row [at] (gliding, or there at once as the menu opens), its text dark, and the row in view. */
    private fun mark(glide: Boolean) {
        val y = (at * rowHeight).toFloat()
        if (glide) highlightGlide.shiftTo(y, TvMotion.ListGlideMs)
        else { highlightGlide.stop(); highlight.translationY = y }
        for (i in 0 until rows.childCount) {
            val lit = i == at
            val row = rows.getChildAt(i) as ViewGroup
            (row.getChildAt(row.childCount - 1) as TextView).setTextColor(if (lit) PlayerStyle.INK else 0xFFFFFFFF.toInt())
            if (row.childCount > 1) (row.getChildAt(0) as ImageView).setColorFilter(if (lit) PlayerStyle.INK else 0xFFFFFFFF.toInt())
        }
        val visible = scroll.layoutParams.height
        if (visible > 0) {
            val target = (y.toInt() - (visible - rowHeight) / 2).coerceAtLeast(0)
            if (glide) scroll.smoothScrollTo(0, target) else scroll.post { scroll.scrollTo(0, target) }
        }
    }

    private fun rowView(row: Row, checks: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18f).toInt(), 0, dp(22f).toInt(), 0)
        if (checks) addView(ImageView(context).apply {
            setImageResource(R.drawable.tv_ic_check)
            visibility = if (row.checked) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()).apply { marginEnd = dp(14f).toInt() })
        addView(TextView(context).apply {
            text = row.label
            textSize = 20f
            typeface = PlayerStyle.text(context)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun locationIn(v: View): IntArray {
        val a = IntArray(2).also(v::getLocationInWindow)
        val r = IntArray(2).also(root::getLocationInWindow)
        return intArrayOf(a[0] - r[0], a[1] - r[1])
    }

    private fun dp(v: Float) = PlayerStyle.dp(context, v)
}
