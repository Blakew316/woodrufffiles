package com.woodruff.tvexplorer.ui.motion

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How focused an item is, 0 at rest to 1 focused (springing slightly past it). Read it only in draw or
 * graphicsLayer blocks: then the focus animation redraws the item without recomposing anything.
 */
@Stable
class FocusMotionState {
    // 0.01 is about a third of a pixel on a grown poster: no frames are drawn for less than that.
    internal val progress = Animatable(0f, visibilityThreshold = 0.01f)
    val fraction: Float get() = progress.value
}

@Composable
fun rememberFocusMotionState(): FocusMotionState = remember { FocusMotionState() }

/**
 * Grows (and lifts) an item when it takes the focus, on [TvMotion]'s springs, as a layer transform: no
 * recomposition and no layout per frame. Put it BEFORE the item's focus target (focusable, or a
 * tv-material Surface's own), so it hears that target's focus. [shadow]: a soft oval under the focused
 * item, outside its growing layer (drawn only while focused). [focusIn] / [focusOut]: the springs it
 * grows and shrinks on (a row on a [FocusPlatter] uses the platter's glide, so it changes as it arrives).
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
    private var shadowSize = Size.Unspecified
    private var shadowBrush: Brush? = null

    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val f = state.progress.value   // read by the layer alone
        val s = 1f + (focusedScale - 1f) * f
        scaleX = s; scaleY = s
        translationY = -lift.toPx() * f
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
    }

    fun reset() {
        job?.cancel(); job = null
        focused = false
        if (raised) { raised = false; if (isAttached) invalidatePlacement() }
    }

    override fun onReset() = reset()

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, zIndex = if (raised) 1f else 0f, layerBlock = layerBlock)
        }
    }

    // Outside the growing layer, so the shadow stays put while the item lifts off it.
    override fun ContentDrawScope.draw() {
        val f = state.progress.value.coerceIn(0f, 1f)
        if (shadow && f > 0.01f) {
            if (shadowSize != size || shadowBrush == null) {
                shadowSize = size
                shadowBrush = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.55f), 0.75f to Color.Black.copy(alpha = 0.22f), 1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height * 0.62f + 14.dp.toPx()), radius = size.width * 0.72f,
                )
            }
            drawOval(
                brush = shadowBrush!!, alpha = f,
                topLeft = Offset(-size.width * 0.22f, size.height * 0.08f + 14.dp.toPx() * f),
                size = Size(size.width * 1.44f, size.height * 1.08f),
            )
        }
        drawContent()
    }
}

/**
 * A focusable card without tv-material's Surface: no offscreen drawing, no per-frame recomposition and
 * no coroutines until it's focused. Grows on focus ([focusedScale], [lift], [shadow]); its colour and
 * border follow the focus in the draw phase. OK clicks; holding OK (or Menu) is the long click.
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
            .onKeyEvent { okKey(it, longPressed, onClick, onLongClick) }
            .focusable()
            .semantics(mergeDescendants = true) {
                this.onClick(label = null, action = { onClick(); true })
                if (onLongClick != null) this.onLongClick(label = null, action = { onLongClick(); true })
            }
            .clip(shape)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                // Twice as wide: the outer half falls outside the clip, so the line sits just inside the edge.
                val stroke = if (borderWidth > 0.dp) Stroke(width = borderWidth.toPx() * 2f) else null
                onDrawWithContent {
                    val f = motion.fraction.coerceIn(0f, 1f)   // read while drawing, not composing
                    val bg = lerp(color, focusedColor, f)
                    if (bg.alpha > 0f) drawOutline(outline, bg)
                    drawContent()
                    if (stroke != null) drawOutline(outline, lerp(borderColor, focusedBorderColor, f), style = stroke)
                }
            },
        content = content,
    )
}

/** OK/Enter: a click when let go; held (its first repeat): the long click; Menu: the long click. */
private fun okKey(e: KeyEvent, longPressed: BooleanArray, onClick: () -> Unit, onLongClick: (() -> Unit)?): Boolean {
    val n = e.nativeKeyEvent
    if (n.keyCode == NativeKeyEvent.KEYCODE_MENU) {
        if (onLongClick == null) return false
        if (n.action == NativeKeyEvent.ACTION_UP) onLongClick()
        return true
    }
    if (n.keyCode != NativeKeyEvent.KEYCODE_DPAD_CENTER && n.keyCode != NativeKeyEvent.KEYCODE_ENTER &&
        n.keyCode != NativeKeyEvent.KEYCODE_NUMPAD_ENTER) return false
    when (n.action) {
        NativeKeyEvent.ACTION_DOWN -> if (n.repeatCount == 1 && onLongClick != null) { longPressed[0] = true; onLongClick() }
        NativeKeyEvent.ACTION_UP -> if (longPressed[0]) longPressed[0] = false else onClick()
    }
    return true   // also keeps Compose from taking OK as "enter this group"
}

/** An icon whose tint follows the focus animation while drawing (no recomposition). */
@Composable
fun FocusTintIcon(icon: ImageVector, motion: FocusMotionState, rest: Color, focused: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(com.woodruff.tvexplorer.ui.icons.symbolAt(icon, size))
    Box(
        modifier.size(size).drawBehind {
            val tint = lerp(rest, focused, motion.fraction.coerceIn(0f, 1f))
            with(painter) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint)) }
        },
    )
}

/** An icon tinted by [tint], asked while drawing (a row's colour following its focus): no recomposition. */
@Composable
fun TintIcon(icon: ImageVector, tint: ColorProducer, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(com.woodruff.tvexplorer.ui.icons.symbolAt(icon, size))
    Box(
        modifier.size(size).drawBehind {
            with(painter) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint())) }
        },
    )
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
