package com.woodruff.tvexplorer.ui.motion

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositing

/*
 * Every frame of a focus animation changes layers only: how big (a scale), where (a shift) and how see-through
 * (an alpha) each is. Nothing is drawn again while it plays. On Android, drawing anything again (a colour or a
 * size worked out as it draws) redraws the window's whole picture: on a TV's graphics chip that's 20 to 40 ms
 * a frame, where moving a layer repaints only what it covers. So what changes colour with the focus is drawn
 * twice, its rest look and its focused look, each in a layer, one fading out as the other fades in.
 */

/**
 * How focused an item is, 0 at rest to 1 focused (springing slightly past it). Read it only in graphicsLayer
 * blocks (and [focusFade]): then a frame of the focus animation changes layers alone, recomposing and
 * redrawing nothing.
 */
@Stable
class FocusMotionState {
    // 0.01 is about a third of a pixel on a grown poster: no frames are drawn for less than that.
    internal val progress = Animatable(0f, visibilityThreshold = 0.01f)
    val fraction: Float get() = progress.value
    /** OK held on it, 0 to 1: it sinks back from its focused size (a [TvCard] off a platter). */
    internal val press = Animatable(0f, visibilityThreshold = 0.01f)
}

@Composable
fun rememberFocusMotionState(): FocusMotionState = remember { FocusMotionState() }

/**
 * Shows this as far as [motion] is focused ([out]: as far as it isn't), as its layer's alpha: a frame of the
 * focus animation changes the layer alone. ModulateAlpha fades what it draws in place, with no offscreen buffer.
 */
fun Modifier.focusFade(motion: FocusMotionState, out: Boolean = false): Modifier = graphicsLayer {
    val f = motion.fraction.coerceIn(0f, 1f)
    alpha = if (out) 1f - f else f
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/**
 * A colour that follows an item's focus ([motion]): [rest] off it, [focused] on it. The pieces that know it
 * ([TintIcon], and the rows' text) draw both, [rest] fading out as [focused] fades in ([focusFade]), so the
 * focus animation redraws nothing; asked for a single colour, it's the one in between.
 */
@Stable
class FocusColor(val motion: FocusMotionState, val rest: Color, val focused: Color) : ColorProducer {
    override fun invoke(): Color = lerp(rest, focused, motion.fraction.coerceIn(0f, 1f))
}

/**
 * Grows (and lifts) an item when it takes the focus, on [TvMotion]'s springs, as a layer transform: no
 * recomposition, layout or drawing per frame. Put it BEFORE the item's focus target (focusable, or a
 * tv-material Surface's own), so it hears that target's focus. [shadow]: a soft oval under the focused
 * item, outside its growing layer, in a layer of its own that fades in and drops as it grows. [focusIn] /
 * [focusOut]: the springs it grows and shrinks on (a row on a [FocusPlatter] uses the platter's glide, so
 * it changes as it arrives).
 */
fun Modifier.tvFocusMotion(
    state: FocusMotionState,
    focusedScale: Float = TvMotion.CardScale,
    lift: Dp = 0.dp,
    shadow: Boolean = false,
    focusIn: AnimationSpec<Float> = TvMotion.FocusIn,
    focusOut: AnimationSpec<Float> = TvMotion.FocusOut,
): Modifier = this then FocusMotionElement(state, focusedScale, lift, shadow, focusIn, focusOut)

private data class FocusMotionElement(
    val state: FocusMotionState,
    val focusedScale: Float,
    val lift: Dp,
    val shadow: Boolean,
    val focusIn: AnimationSpec<Float>,
    val focusOut: AnimationSpec<Float>,
) : ModifierNodeElement<FocusMotionNode>() {
    override fun create() = FocusMotionNode(state, focusedScale, lift, shadow, focusIn, focusOut)
    override fun update(node: FocusMotionNode) {
        if (node.state !== state) node.reset()   // a lazy item reused for another key
        node.state = state; node.focusedScale = focusedScale; node.lift = lift; node.shadow = shadow
        node.focusIn = focusIn; node.focusOut = focusOut
    }
}

private class FocusMotionNode(
    var state: FocusMotionState,
    var focusedScale: Float,
    var lift: Dp,
    var shadow: Boolean,
    var focusIn: AnimationSpec<Float>,
    var focusOut: AnimationSpec<Float>,
) : Modifier.Node(), FocusEventModifierNode, LayoutModifierNode, DrawModifierNode {
    private var focused = false
    // Drawn above its neighbours while focused (and while shrinking back), so it grows over them.
    private var raised = false
    private var job: Job? = null
    // The shadow, drawn once (per size) into a layer of its own; the focus animation fades and drops that
    // layer along with the item's growth.
    private var shadowLayer: GraphicsLayer? = null
    private var shadowSize = Size.Unspecified

    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val f = state.progress.value   // read by the layer alone
        // OK held takes it back towards its resting size, as tv-material's press did.
        val s = (1f + (focusedScale - 1f) * f) * (1f + (1f / focusedScale - 1f) * state.press.value)
        scaleX = s; scaleY = s
        translationY = -lift.toPx() * f
        shadowLayer?.let { placeShadow(it, f, this) }
    }

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused
        if (now == focused) return
        focused = now
        if (!isAttached) return   // a detached node still hears that it lost the focus
        if (now && !raised) { raised = true; invalidatePlacement() }
        val s = state
        // Started at once (no hop to the next frame), and picking up the current speed: a quick
        // reversal curves back instead of snapping.
        job = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            s.progress.animateTo(if (now) 1f else 0f, if (now) focusIn else focusOut)
            if (!now && s === state && !focused && raised) { raised = false; invalidatePlacement() }
        }
        // The focus gone with OK still held (a long press opened a menu, which then has the key's release).
        if (!now && s.press.targetValue != 0f) coroutineScope.launch { s.press.animateTo(0f, TvMotion.PressOut) }
    }

    fun reset() {
        job?.cancel(); job = null
        focused = false
        if (raised) { raised = false; if (isAttached) invalidatePlacement() }
    }

    override fun onReset() = reset()

    override fun onDetach() {
        shadowLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        shadowLayer = null
        shadowSize = Size.Unspecified
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, zIndex = if (raised) 1f else 0f, layerBlock = layerBlock)
        }
    }

    // Reads nothing that animates: the focus animation never draws this again.
    override fun ContentDrawScope.draw() {
        if (shadow) drawShadow()
        drawContent()
    }

    // Outside the growing layer, so the shadow stays put while the item lifts off it: an oval drawn here once
    // per size, fully dropped, in its own layer, which [placeShadow] fades and lifts back with the focus.
    private fun ContentDrawScope.drawShadow() {
        val layer = shadowLayer ?: requireGraphicsContext().createGraphicsLayer().also {
            it.compositingStrategy = LayerCompositing.ModulateAlpha
            shadowLayer = it
        }
        if (shadowSize != size) {
            shadowSize = size
            val w = size.width
            val h = size.height
            val drop = ShadowDrop.toPx()
            val left = floor(-w * 0.22f)
            val top = floor(h * 0.08f)
            val brush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.55f), 0.75f to Color.Black.copy(alpha = 0.22f), 1f to Color.Transparent,
                center = Offset(w / 2f - left, h * 0.62f + drop - top), radius = w * 0.72f,
            )
            layer.topLeft = IntOffset(left.toInt(), top.toInt())
            layer.record(this, layoutDirection, IntSize(ceil(w * 1.44f).toInt() + 2, ceil(h * 1.08f + drop).toInt() + 2)) {
                drawOval(brush, topLeft = Offset(-w * 0.22f - left, h * 0.08f + drop - top), size = Size(w * 1.44f, h * 1.08f))
            }
        }
        // Where the focus has it now, without drawing this again as the focus moves.
        placeShadow(layer, Snapshot.withoutReadObservation { state.progress.value }, this)
        drawLayer(layer)
    }

    private fun placeShadow(layer: GraphicsLayer, focus: Float, density: Density) {
        val f = focus.coerceIn(0f, 1f)
        layer.alpha = f
        layer.translationY = -with(density) { ShadowDrop.toPx() } * (1f - f)
    }

    private companion object {
        /** How far below the item its shadow falls once it's focused. */
        val ShadowDrop = 14.dp
    }
}

/**
 * A focusable card without tv-material's Surface: no offscreen drawing, no per-frame recomposition or
 * redrawing, and no coroutines until it's focused. Grows on focus ([focusedScale], [lift], [shadow]); where
 * its fill and border change with the focus, the focused ones fade in over the rest ones fading out, as
 * layers. OK clicks; holding OK (or Menu) is the long click.
 * [platter]: a row of a list whose focus highlight glides from row to row ([FocusPlatter]); it then
 * grows (and its colours change) on the platter's glide, and [focusedColor] is usually see-through.
 */
@Composable
fun TvCard(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    motion: FocusMotionState = rememberFocusMotionState(),
    focusedScale: Float = TvMotion.CardScale,
    lift: Dp = 0.dp,
    shadow: Boolean = false,
    color: Color = Color.Transparent,
    focusedColor: Color = color,
    borderWidth: Dp = 0.dp,
    borderColor: Color = Color.Transparent,
    focusedBorderColor: Color = borderColor,
    platter: FocusPlatter? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val longPressed = remember { BooleanArray(1) }
    // OK held sinks it back to its resting size (not a row on a platter: the platter doesn't sink with it).
    val scope = rememberCoroutineScope()
    val press: ((Boolean) -> Unit)? = if (platter != null) null else { down ->
        scope.launch { motion.press.animateTo(if (down) 1f else 0f, if (down) TvMotion.PressIn else TvMotion.PressOut) }
    }
    // What the focus changes, each look in a layer of its own that fades (the card itself never draws again).
    val fills = color != focusedColor
    val borders = borderWidth > 0.dp && borderColor != focusedBorderColor
    Box(
        modifier
            // Inside the caller's padding and before the focus target: it hears the focus, and the
            // platter covers the card itself.
            .then(if (platter != null) Modifier.focusPlatterItem(platter, motion, focusedScale) else Modifier)
            .tvFocusMotion(
                motion, focusedScale, lift, shadow,
                focusIn = if (platter != null) TvMotion.Glide else TvMotion.FocusIn,
                focusOut = if (platter != null) TvMotion.Glide else TvMotion.FocusOut,
            )
            .onKeyEvent { okKey(it, longPressed, onClick, onLongClick, press) }
            .focusable()
            .semantics(mergeDescendants = true) {
                this.onClick(label = null, action = { onClick(); true })
                if (onLongClick != null) this.onLongClick(label = null, action = { onLongClick(); true })
            }
            .clip(shape)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                // Twice as wide: the outer half falls outside the clip, so the line sits just inside the edge.
                val stroke = if (borderWidth > 0.dp && !borders && borderColor.alpha > 0f) Stroke(width = borderWidth.toPx() * 2f) else null
                onDrawWithContent {
                    if (!fills && color.alpha > 0f) drawOutline(outline, color)
                    drawContent()
                    if (stroke != null) drawOutline(outline, borderColor, style = stroke)
                }
            },
    ) {
        if (fills) {
            if (color.alpha > 0f) Box(Modifier.matchParentSize().focusFade(motion, out = true).background(color))
            if (focusedColor.alpha > 0f) Box(Modifier.matchParentSize().focusFade(motion).background(focusedColor))
        }
        content()
        if (borders) {
            if (borderColor.alpha > 0f) CardBorder(shape, borderWidth, borderColor, Modifier.matchParentSize().focusFade(motion, out = true))
            if (focusedBorderColor.alpha > 0f) CardBorder(shape, borderWidth, focusedBorderColor, Modifier.matchParentSize().focusFade(motion))
        }
    }
}

/** A card's border, just inside its edge: drawn twice as wide, and the card's clip takes the outer half. */
@Composable
private fun CardBorder(shape: Shape, width: Dp, color: Color, modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val stroke = Stroke(width = width.toPx() * 2f)
            onDrawBehind { drawOutline(outline, color, style = stroke) }
        },
    )
}

/**
 * OK/Enter: a click when let go; held (its first repeat): the long click; Menu: the long click. [press]: told
 * when OK goes down and comes back up.
 */
private fun okKey(e: KeyEvent, longPressed: BooleanArray, onClick: () -> Unit, onLongClick: (() -> Unit)?, press: ((Boolean) -> Unit)?): Boolean {
    val n = e.nativeKeyEvent
    if (n.keyCode == NativeKeyEvent.KEYCODE_MENU) {
        if (onLongClick == null) return false
        if (n.action == NativeKeyEvent.ACTION_UP) onLongClick()
        return true
    }
    if (n.keyCode != NativeKeyEvent.KEYCODE_DPAD_CENTER && n.keyCode != NativeKeyEvent.KEYCODE_ENTER &&
        n.keyCode != NativeKeyEvent.KEYCODE_NUMPAD_ENTER) return false
    when (n.action) {
        NativeKeyEvent.ACTION_DOWN -> {
            if (n.repeatCount == 0) press?.invoke(true)
            if (n.repeatCount == 1 && onLongClick != null) { longPressed[0] = true; onLongClick() }
        }
        NativeKeyEvent.ACTION_UP -> {
            press?.invoke(false)
            if (longPressed[0]) longPressed[0] = false else onClick()
        }
    }
    return true   // also keeps Compose from taking OK as "enter this group"
}

/** An icon in [rest] off the focus and [focused] on it, cross-fading with the focus animation ([motion]). */
@Composable
fun FocusTintIcon(icon: ImageVector, motion: FocusMotionState, rest: Color, focused: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    TintIcon(icon, remember(motion, rest, focused) { FocusColor(motion, rest, focused) }, size, modifier)

/**
 * An icon tinted by [tint]. A [FocusColor] (a row's colour following its focus) is drawn twice, in its rest
 * and focused colours, cross-fading as layers: the focus animation redraws nothing. Any other [tint] is asked
 * while drawing.
 */
@Composable
fun TintIcon(icon: ImageVector, tint: ColorProducer, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(com.woodruff.tvexplorer.ui.icons.symbolAt(icon, size))
    if (tint is FocusColor) {
        Box(modifier.size(size)) {
            Box(
                Modifier.matchParentSize().focusFade(tint.motion, out = true).drawBehind {
                    with(painter) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint.rest)) }
                },
            )
            Box(
                Modifier.matchParentSize().focusFade(tint.motion).drawBehind {
                    with(painter) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint.focused)) }
                },
            )
        }
    } else {
        Box(
            modifier.size(size).drawBehind {
                with(painter) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint())) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class WholeElementResponder : BringIntoViewResponder {
    var size: Size = Size.Zero
    override fun calculateRectForParent(localRect: Rect): Rect = Rect(Offset.Zero, size)
    override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
}

/**
 * Focus anywhere inside asks the scrolling parents to show this whole element (a banner, a page's
 * header), in one scroll, instead of just the focused button and then a second scroll for the rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bringWholeIntoView(): Modifier {
    val responder = remember { WholeElementResponder() }
    return this.onSizeChanged { responder.size = it.toSize() }.bringIntoViewResponder(responder)
}
