package com.woodruff.tvexplorer.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The focused row's highlight, shared by a list's rows so it glides from the row the focus leaves to
 * the row it lands on, as tvOS's does, instead of one row's fill fading out while another's fades in.
 * The list draws it ([focusPlatterHost]) behind its rows as it draws, following them exactly as the
 * list scrolls: moving it recomposes and lays out nothing. The rows report their focus and where they
 * are ([focusPlatterItem], or TvCard's `platter`), and draw their words and symbols over it, changing
 * colour with their own focus.
 *
 * Focus coming into the list from outside: the platter shows up on that row, a quick fade as it grows
 * into place (it never slides in from wherever it was last). Focus leaving the list: it fades out
 * where it is. Held arrows retarget it on every step and it keeps its speed, so it glides on rather
 * than stopping at each row.
 */
@Stable
class FocusPlatter internal constructor() {
    private var host: PlatterHostNode? = null

    // The row it's on or gliding to; once that row is gone (the list changed), the place it was.
    private var row: PlatterRowNode? = null
    private var stillRect: Rect? = null
    // Fading out where the focus left the list (until the focus comes back).
    private var leaving = false

    // Where the current glide started: held to the row it set off from ([fromRow], as laid out) so it
    // scrolls with the list; [fromRect] once that row's gone.
    private var fromRow: PlatterRowNode? = null
    private var fromRect: Rect? = null
    private var fromLeft = 0f
    private var fromTop = 0f
    private var fromRight = 0f
    private var fromBottom = 0f

    // Where it was last drawn (before and after its arrival growth), for when its rows go away.
    private var lastRaw: Rect? = null
    private var lastRect: Rect? = null

    // 0 at the start of a glide to 1 on its row; how much of it shows; its growth as it arrives.
    private val glide = SpringValue(1f)
    private val shown = SpringValue(0f)
    private val grow = SpringValue(1f)

    // A platter still fading out where the focus last left the list while the focus came back in at
    // another row: it finishes fading there instead of vanishing (or flying across).
    private var ghostRow: PlatterRowNode? = null
    private var ghostRect: Rect? = null
    private val ghost = SpringValue(0f)

    private var frames: Job? = null
    private var leaveCheck: Job? = null

    internal fun attach(node: PlatterHostNode) {
        host = node
    }

    internal fun detach(node: PlatterHostNode) {
        if (host !== node) return
        host = null
        frames?.cancel(); frames = null
        leaveCheck?.cancel(); leaveCheck = null
        settle()
    }

    /** [r] took the focus. */
    internal fun focusIn(r: PlatterRowNode) {
        leaveCheck?.cancel(); leaveCheck = null
        when (platterMove(row, leaving, shown.value, r)) {
            PlatterMove.Stay -> return
            PlatterMove.Return -> {
                leaving = false
                shown.animateTo(1f, 1f, TvMotion.PlatterInStiffness)
            }
            PlatterMove.Glide -> glideTo(r)
            PlatterMove.Appear -> appearOn(r)
        }
        animate()
    }

    /** [r] lost the focus. */
    internal fun focusOut(r: PlatterRowNode) {
        if (row !== r || leaving) return
        val h = host
        if (h == null || !h.isAttached) { leave(); animate(); return }
        // The rest of this focus change first: when another row of the list takes the focus (it's
        // told just after this one lets go), the platter glides there instead of fading out.
        leaveCheck?.cancel()
        leaveCheck = h.coroutineScope.launch {
            if (row === r && !r.focused && !leaving) { leave(); animate() }
        }
    }

    /** [r] was placed again (the list scrolled, or its rows moved): redrawn where it now is. */
    internal fun rowMoved(r: PlatterRowNode) {
        if (r === row || r === fromRow || r === ghostRow) host?.let { if (it.isAttached) it.invalidateDraw() }
    }

    /** [r] left the list (or is being reused for another row): the platter keeps the place it last had. */
    internal fun rowGone(r: PlatterRowNode) {
        if (r === fromRow) fromRow = null
        if (r === ghostRow) ghostRow = null
        if (r === row) {
            row = null
            stillRect = lastRaw
            if (!leaving) leave()
            animate()
        }
    }

    private fun glideTo(r: PlatterRowNode) {
        val hc = host?.coordinates()
        val old = row
        // Where it shows now (before its arrival growth, which keeps applying on top).
        val now = rawNow(hc)
        var speed = 0f
        if (hc != null && now != null && !glide.atRest) {
            val oldTo = old?.grownIn(hc) ?: stillRect
            val oldFrom = fromNow(hc)
            val newTo = r.grownIn(hc)
            if (oldTo != null && oldFrom != null && newTo != null) {
                speed = carriedGlideSpeed(
                    now, newTo,
                    (oldTo.center.x - oldFrom.center.x) * glide.velocity,
                    (oldTo.center.y - oldFrom.center.y) * glide.velocity,
                )
            }
        }
        fromRow = old
        fromRect = now
        val base = if (hc != null) old?.boundsIn(hc) else null
        if (now != null && base != null) {
            fromLeft = now.left - base.left; fromTop = now.top - base.top
            fromRight = now.right - base.right; fromBottom = now.bottom - base.bottom
        } else {
            fromRow = null
        }
        row = r
        stillRect = null
        glide.snapTo(0f, speed)
        glide.animateTo(1f, TvMotion.GlideDamping, TvMotion.GlideStiffness)
        shown.animateTo(1f, 1f, TvMotion.PlatterInStiffness)
    }

    private fun appearOn(r: PlatterRowNode) {
        if (shown.value > MinAlpha && (row != null || stillRect != null)) {
            ghostRow = row
            ghostRect = lastRect
            ghost.snapTo(shown.value, shown.velocity)
            ghost.animateTo(0f, 1f, TvMotion.PlatterOutStiffness)
        }
        row = r
        stillRect = null
        leaving = false
        fromRow = null
        fromRect = null
        // Nothing of where it was: until the row is laid out, it isn't drawn at all.
        lastRaw = null
        lastRect = null
        glide.snapTo(1f)
        glide.animateTo(1f, TvMotion.GlideDamping, TvMotion.GlideStiffness)
        grow.snapTo(TvMotion.PlatterAppearScale)
        grow.animateTo(1f, 1f, TvMotion.PlatterInStiffness)
        shown.snapTo(0f)
        shown.animateTo(1f, 1f, TvMotion.PlatterInStiffness)
    }

    private fun leave() {
        leaving = true
        shown.animateTo(0f, 1f, TvMotion.PlatterOutStiffness)
    }

    /** Where the glide set off from, following that row as the list scrolls. */
    private fun fromNow(hc: LayoutCoordinates): Rect? {
        val base = fromRow?.boundsIn(hc) ?: return fromRect
        return Rect(base.left + fromLeft, base.top + fromTop, base.right + fromRight, base.bottom + fromBottom)
    }

    /** Where it is now, before its arrival growth. */
    private fun rawNow(hc: LayoutCoordinates?): Rect? {
        if (hc == null) return lastRaw
        val to = row?.grownIn(hc) ?: stillRect ?: return lastRaw
        return glidingRect(if (glide.atRest) null else fromNow(hc), to, glide.value)
    }

    /** Draws the platter (and one still fading out) in [host]'s space; called as the list draws. */
    internal fun draw(scope: DrawScope, list: PlatterHostNode, hc: LayoutCoordinates) {
        if (ghost.value > MinAlpha) {
            val g = ghostRow?.grownIn(hc) ?: ghostRect
            if (g != null) {
                ghostRect = g
                list.drawPlatter(scope, g, ghost.value)
            }
        }
        val alpha = shown.value
        if (alpha <= MinAlpha) return
        val to = row?.grownIn(hc) ?: stillRect ?: lastRaw ?: return
        val from = if (glide.atRest) null else fromNow(hc)
        if (from != null) fromRect = from
        val raw = glidingRect(from, to, glide.value)
        lastRaw = raw
        val shownRect = raw.grownBy(grow.value)
        lastRect = shownRect
        list.drawPlatter(scope, shownRect, alpha)
    }

    private fun moving(): Boolean = !glide.atRest || !shown.atRest || !grow.atRest || !ghost.atRest

    private fun step(seconds: Float) {
        glide.step(seconds); shown.step(seconds); grow.step(seconds); ghost.step(seconds)
        if (ghost.atRest) { ghostRow = null; ghostRect = null }
        if (leaving && shown.atRest) { row = null; stillRect = null; fromRow = null }
    }

    /** Everything where it's heading, at once (nothing left to draw it with). */
    private fun settle() {
        glide.finish(); shown.finish(); grow.finish(); ghost.finish()
        step(0f)
    }

    /** Redraws the list now, and every frame while the platter moves (on the frame clock, while the list is attached). */
    private fun animate() {
        val h = host
        if (h == null || !h.isAttached) { settle(); return }
        h.invalidateDraw()
        if (!moving() || frames?.isActive == true) return
        frames = h.coroutineScope.launch {
            var last = -1L
            while (moving()) {
                withFrameNanos { now ->
                    if (last >= 0) step((now - last) / 1_000_000_000f)
                    last = now
                    host?.let { if (it.isAttached) it.invalidateDraw() }
                }
            }
        }
    }

    internal companion object {
        /** Less than this much of it shows nothing worth drawing. */
        const val MinAlpha = 0.002f
    }
}

/** A list's gliding focus highlight ([FocusPlatter]): one per list, shared by its rows. */
@Composable
fun rememberFocusPlatter(): FocusPlatter = remember { FocusPlatter() }

/**
 * Draws [platter] in [color] and [shape], behind this layout's content (a list's rows), as it draws.
 * Put it on the scrolling content itself (a Column's verticalScroll goes before it), so the platter
 * scrolls with the rows for free; on a lazy list, on the list (its rows tell it when they move).
 * [clip]: cut at this layout's top and bottom, as the list cuts its rows (so put it before the list's
 * padding when the rows grow into it); false for a plain column of rows, which a grown row's platter
 * may reach past. Behind the list's own background: after that, in the chain.
 */
fun Modifier.focusPlatterHost(platter: FocusPlatter, shape: Shape, color: Color, clip: Boolean = true): Modifier =
    this then PlatterHostElement(platter, shape, color, clip)

/**
 * This row on [platter]: the platter glides here when the row takes the focus. Before the row's focus
 * target (and inside its padding: the platter covers what's after it). [motion] and [focusedScale]:
 * the row's own focus growth ([tvFocusMotion]), which the platter grows with.
 */
fun Modifier.focusPlatterItem(platter: FocusPlatter, motion: FocusMotionState? = null, focusedScale: Float = 1f): Modifier =
    this then PlatterRowElement(platter, motion, focusedScale)

private data class PlatterHostElement(val platter: FocusPlatter, val shape: Shape, val color: Color, val clip: Boolean) :
    ModifierNodeElement<PlatterHostNode>() {
    override fun create() = PlatterHostNode(platter, shape, color, clip)
    override fun update(node: PlatterHostNode) {
        if (node.platter !== platter) {
            node.platter.detach(node)
            node.platter = platter
            if (node.isAttached) platter.attach(node)
        }
        if (node.shape != shape) node.forgetOutline()
        node.shape = shape
        node.color = color
        node.clip = clip
    }
}

internal class PlatterHostNode(var platter: FocusPlatter, var shape: Shape, var color: Color, var clip: Boolean) :
    Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    private var placed: LayoutCoordinates? = null
    private var outline: Outline? = null
    private var outlineSize = Size.Unspecified

    override fun onAttach() = platter.attach(this)
    override fun onDetach() = platter.detach(this)

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates
    }

    fun coordinates(): LayoutCoordinates? = placed?.takeIf { isAttached && it.isAttached }

    fun forgetOutline() {
        outline = null
        outlineSize = Size.Unspecified
    }

    override fun ContentDrawScope.draw() {
        val hc = coordinates()
        if (hc != null) {
            // Clipped top and bottom only, as a list clips its rows: a grown row's platter may reach
            // past the list's sides.
            if (clip) {
                clipRect(left = -ClipSlack, top = 0f, right = size.width + ClipSlack, bottom = size.height) {
                    platter.draw(this, this@PlatterHostNode, hc)
                }
            } else {
                platter.draw(this, this@PlatterHostNode, hc)
            }
        }
        drawContent()
    }

    fun drawPlatter(scope: DrawScope, rect: Rect, alpha: Float) {
        if (rect.width <= 0f || rect.height <= 0f) return
        // Made again only when its size changes (a glide between rows of one height keeps it).
        val o = outline?.takeIf { outlineSize == rect.size }
            ?: shape.createOutline(rect.size, scope.layoutDirection, scope).also { outline = it; outlineSize = rect.size }
        scope.translate(rect.left, rect.top) { drawOutline(o, color, alpha = alpha.coerceAtMost(1f)) }
    }

    private companion object {
        const val ClipSlack = 100_000f
    }
}

private data class PlatterRowElement(val platter: FocusPlatter, val motion: FocusMotionState?, val scale: Float) :
    ModifierNodeElement<PlatterRowNode>() {
    override fun create() = PlatterRowNode(platter, motion, scale)
    override fun update(node: PlatterRowNode) {
        if (node.platter !== platter) {
            node.platter.rowGone(node)
            node.platter = platter
        }
        node.motion = motion
        node.scale = scale
    }
}

internal class PlatterRowNode(var platter: FocusPlatter, var motion: FocusMotionState?, var scale: Float) :
    Modifier.Node(), FocusEventModifierNode, LayoutAwareModifierNode {
    var focused = false
        private set
    private var placed: LayoutCoordinates? = null

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.hasFocus
        if (now == focused) return
        focused = now
        if (!isAttached) return // a detached row still hears that it lost the focus
        if (now) platter.focusIn(this) else platter.focusOut(this)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates
        platter.rowMoved(this)
    }

    override fun onDetach() {
        platter.rowGone(this)
        placed = null
        focused = false
    }

    override fun onReset() {
        platter.rowGone(this)
        focused = false
    }

    /** This row in [host]'s space, as laid out. */
    fun boundsIn(host: LayoutCoordinates): Rect? {
        val c = placed ?: return null
        if (!isAttached || !c.isAttached || !host.isAttached) return null
        return host.localBoundingBoxOf(c, clipBounds = false)
    }

    /** ...and as it shows, grown with its focus (read while drawing: the platter grows with it). */
    fun grownIn(host: LayoutCoordinates): Rect? {
        val bounds = boundsIn(host) ?: return null
        val m = motion ?: return bounds
        return bounds.grownBy(1f + (scale - 1f) * m.fraction)
    }
}

/** What the platter does when a row takes the focus. */
internal enum class PlatterMove {
    /** It's already there. */
    Stay,
    /** From where it is (a row of the same list just had the focus) to the new row. */
    Glide,
    /** The focus came back to the row it's fading out on: it fades back in there. */
    Return,
    /** The focus came into the list: it shows up on that row, fading in as it grows. */
    Appear,
}

/**
 * What the platter does when [row] takes the focus, given the row it's on ([onRow], null for none),
 * whether it's fading out there because the focus left the list ([leaving]), and how much of it
 * still shows ([shown], 0 to 1).
 */
internal fun platterMove(onRow: Any?, leaving: Boolean, shown: Float, row: Any): PlatterMove = when {
    onRow == null || (leaving && shown <= 0f) -> PlatterMove.Appear
    leaving -> if (onRow === row) PlatterMove.Return else PlatterMove.Appear
    onRow === row -> PlatterMove.Stay
    else -> PlatterMove.Glide
}

/**
 * The speed (in glides a second) a new glide from [from] to [to] starts at, to carry on the
 * platter's motion: its velocity ([velocityX], [velocityY], px a second) along the new way. A held
 * arrow retargets it on every step: it carries on instead of stopping dead at each row (and a quick
 * reversal curves back, as a spring does).
 */
internal fun carriedGlideSpeed(from: Rect, to: Rect, velocityX: Float, velocityY: Float): Float {
    val dx = to.center.x - from.center.x
    val dy = to.center.y - from.center.y
    val d2 = dx * dx + dy * dy
    if (d2 < 1f) return 0f
    return ((velocityX * dx + velocityY * dy) / d2).coerceIn(-MaxCarriedSpeed, MaxCarriedSpeed)
}

private const val MaxCarriedSpeed = 20f

/**
 * Where the platter shows on its way from [from] to [to] ([glide] of the way: 0 to 1, a little past
 * it as the spring overshoots); null [from]: it's there. Both ends are where those rows are now, so
 * it follows the list as it scrolls.
 */
internal fun glidingRect(from: Rect?, to: Rect, glide: Float): Rect = if (from == null) to else lerp(from, to, glide)

/** This rect grown (or shrunk) by [scale] about its centre, as a scaled layer shows it. */
internal fun Rect.grownBy(scale: Float): Rect {
    if (scale == 1f) return this
    val dw = width * (scale - 1f) / 2f
    val dh = height * (scale - 1f) / 2f
    return Rect(left - dw, top - dh, right + dw, bottom + dh)
}

/**
 * A value on a damped spring heading for its target, stepped frame by frame with the spring's exact
 * solution: a slow frame lands it where it would have been anyway, and retargeting it mid-way keeps
 * its speed (a spring curves into the new target instead of jerking). The same spring as Compose's
 * `spring()` for the same damping ratio and stiffness.
 */
internal class SpringValue(initial: Float) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var dampingRatio = 1f
    private var stiffness = 1000f

    val atRest: Boolean get() = value == target && velocity == 0f

    /** Heads for [to] from where it is, at the speed it has, on a spring of [dampingRatio] and [stiffness]. */
    fun animateTo(to: Float, dampingRatio: Float, stiffness: Float) {
        target = to
        this.dampingRatio = dampingRatio
        this.stiffness = stiffness
    }

    /** Puts it at [to], moving at [speed] (a second). Its target stays. */
    fun snapTo(to: Float, speed: Float = 0f) {
        value = to
        velocity = speed
    }

    /** Puts it at its target, still. */
    fun finish() {
        value = target
        velocity = 0f
    }

    fun step(seconds: Float) {
        if (atRest || seconds <= 0f) return
        val x0 = value - target
        val v0 = velocity
        val w = sqrt(stiffness)
        val z = dampingRatio
        val x: Float
        val v: Float
        if (z < 1f) {
            val wd = w * sqrt(1f - z * z)
            val a = z * w
            val e = exp(-a * seconds)
            val c = cos(wd * seconds)
            val s = sin(wd * seconds)
            x = e * (x0 * c + (v0 + a * x0) / wd * s)
            v = e * (v0 * c - (w * w * x0 + a * v0) / wd * s)
        } else if (z == 1f) {
            val e = exp(-w * seconds)
            val k = v0 + w * x0
            x = e * (x0 + k * seconds)
            v = e * (v0 - w * k * seconds)
        } else {
            val root = w * sqrt(z * z - 1f)
            val r1 = -z * w + root
            val r2 = -z * w - root
            val c2 = (v0 - r1 * x0) / (r2 - r1)
            val c1 = x0 - c2
            val e1 = exp(r1 * seconds)
            val e2 = exp(r2 * seconds)
            x = c1 * e1 + c2 * e2
            v = c1 * r1 * e1 + c2 * r2 * e2
        }
        if (abs(x) < RestDistance && abs(v) < RestSpeed) {
            value = target
            velocity = 0f
        } else {
            value = target + x
            velocity = v
        }
    }

    private companion object {
        // In the value's own units (a glide, an alpha, a scale): a fraction of a pixel, still.
        const val RestDistance = 0.0005f
        const val RestSpeed = 0.005f
    }
}
