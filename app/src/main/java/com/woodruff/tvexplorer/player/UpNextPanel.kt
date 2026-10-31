package com.woodruff.tvexplorer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What comes up when an episode ends and another is queued, as on Apple TV: the last frame
 * darkens, and at the bottom left a picture from the next episode, "UP NEXT", its show and name,
 * and two buttons: Play Now (a ring around its count runs down to it playing by itself) and Done.
 * Left / Right choose between them; the activity does what they say (OK, Back, the countdown).
 */
internal class UpNextPanel(private val context: Context, root: FrameLayout) {
    private val scrim = View(context).apply {
        background = LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF0000000.toInt(), 0xA6000000.toInt(), 0x26000000)),
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000)),
        ))
    }
    private val art = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF2C2C2E.toInt())
    }
    private val artFrame = FrameLayout(context).apply {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(18f)) }
        }
        elevation = dp(20f)
        foreground = GradientDrawable().apply { cornerRadius = dp(18f); setStroke(dp(1f).toInt().coerceAtLeast(1), 0x26FFFFFF) }
        addView(art, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private val eyebrow = TextView(context).apply {
        text = "UP NEXT"
        setTextColor(0x99FFFFFF.toInt())
        textSize = 15f
        typeface = PlayerStyle.bold(context)
        letterSpacing = 0.14f
    }
    private val show = TextView(context).apply {
        setTextColor(PlayerStyle.SECONDARY)
        textSize = 20f
        typeface = PlayerStyle.semibold(context)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val title = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 40f
        typeface = PlayerStyle.display(context)
        letterSpacing = -0.018f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val ring = CountdownRing(context)
    private val playLabel = TextView(context).apply {
        text = "Play Now"
        textSize = 20f
        typeface = PlayerStyle.semibold(context)
    }
    private val play = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(30f).toInt(), dp(12f).toInt())
        addView(ring, LinearLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt()).apply { marginEnd = dp(12f).toInt() })
        addView(playLabel)
    }
    private val done = TextView(context).apply {
        text = "Done"
        textSize = 20f
        typeface = PlayerStyle.semibold(context)
        gravity = Gravity.CENTER
        setPadding(dp(30f).toInt(), dp(12f).toInt(), dp(30f).toInt(), dp(12f).toInt())
        minHeight = dp(58f).toInt()
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        clipChildren = false
        clipToPadding = false
        addView(artFrame, LinearLayout.LayoutParams(dp(400f).toInt(), dp(225f).toInt()))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(eyebrow)
            addView(show, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f).toInt() })
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f).toInt() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                // Room for the chosen pill to grow and cast its shadow.
                setPadding(0, 0, 0, dp(16f).toInt())
                addView(play)
                addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16f).toInt() })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26f).toInt() })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(44f).toInt() })
    }
    private val scrimCard = GlidingCard(scrim, GlidingCard.Way.Fade)
    private val contentCard = GlidingCard(content, GlidingCard.Way.Rise)

    /** Done is chosen (Right), not Play Now. */
    var doneChosen = false
        private set

    val shown get() = contentCard.shown

    init {
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(80f).toInt(), 0, dp(80f).toInt(), dp(48f).toInt())
        })
        choose(done = false, animate = false)
    }

    /** Up, for the episode [next], counting down [seconds] (Play Now chosen). Its picture comes later ([setArt]). */
    fun show(next: EpisodeLabel, seconds: Int) {
        show.text = next.eyebrow.orEmpty()
        show.visibility = if (next.eyebrow.isNullOrEmpty()) View.GONE else View.VISIBLE
        title.text = next.title
        art.setImageDrawable(null)
        choose(done = false, animate = false)
        ring.start(seconds * 1_000L)
        scrimCard.show()
        contentCard.show()
    }

    fun setArt(frame: Bitmap?) {
        if (frame == null) return
        art.setImageBitmap(frame)
        art.alpha = 0f
        art.animate().alpha(1f).setDuration(250).start()
    }

    /** [animate] false: gone at once (the player stopping). */
    fun hide(animate: Boolean = true) {
        ring.stop()
        scrimCard.hide(animate)
        contentCard.hide(animate)
    }

    /** Left / Right: Play Now or Done. */
    fun choose(done: Boolean, animate: Boolean = true) {
        doneChosen = done
        style(play, lit = !done, animate)
        style(this.done, lit = done, animate)
        playLabel.setTextColor(if (!done) PlayerStyle.INK else 0xFFFFFFFF.toInt())
        this.done.setTextColor(if (done) PlayerStyle.INK else 0xFFFFFFFF.toInt())
        ring.dark = !done
    }

    private fun style(v: View, lit: Boolean, animate: Boolean) {
        v.background = PlayerStyle.pillBackground(context, lit)
        v.elevation = if (lit) dp(12f) else 0f
        val s = if (lit) 1.06f else 1f
        if (animate) v.animate().scaleX(s).scaleY(s).setDuration(160).start() else { v.scaleX = s; v.scaleY = s }
    }

    private fun dp(v: Float) = PlayerStyle.dp(context, v)
}

/**
 * The count on Play Now: the seconds left in the middle, and a ring around them that empties
 * smoothly (not a tick a second) until the next episode plays. [dark]: on a white pill.
 */
internal class CountdownRing(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var startedAt = 0L
    private var totalMs = 0L
    private var running = false
    var dark = true
        set(value) { field = value; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15 * resources.displayMetrics.scaledDensity
        typeface = PlayerStyle.bold(context)
        fontFeatureSettings = "tnum"
    }
    private val oval = RectF()

    fun start(ms: Long) {
        totalMs = ms
        startedAt = SystemClock.uptimeMillis()
        running = true
        postInvalidateOnAnimation()
    }

    fun stop() { running = false }

    /** What's left, 1 down to 0. */
    private fun left(): Float = if (totalMs <= 0) 0f else (1f - (SystemClock.uptimeMillis() - startedAt).toFloat() / totalMs).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val ink = if (dark) PlayerStyle.INK else 0xFFFFFFFF.toInt()
        track.color = (ink and 0x00FFFFFF) or 0x33000000
        arc.color = ink
        number.color = ink
        val inset = track.strokeWidth / 2 + d
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, track)
        val f = if (running || totalMs > 0) left() else 1f
        canvas.drawArc(oval, -90f, 360f * f, false, arc)
        val secs = kotlin.math.ceil(f * totalMs / 1000.0).toInt()
        val y = height / 2f - (number.descent() + number.ascent()) / 2
        canvas.drawText(secs.toString(), width / 2f, y, number)
        if (running && f > 0f) postInvalidateOnAnimation()
    }
}
