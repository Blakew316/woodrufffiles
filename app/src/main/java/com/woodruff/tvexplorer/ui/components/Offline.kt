package com.woodruff.tvexplorer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.ui.icons.Sym
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes

/** What a [StatusNote] says: a symbol, a short [title] and, after it, a quieter [detail]. [alert]: something to fix, not to wait out. */
@Immutable
data class HeaderStatus(val icon: ImageVector, val title: String, val detail: String? = null, val alert: Boolean = false)

/**
 * A quiet note in a screen's header saying why what's on screen may be out of date ("Offline ·
 * Showing your saved library"). It isn't a control, so it doesn't look like one: no glass, no rim,
 * just a small symbol and words beside the header's buttons, which can't take the focus. It fades
 * in and out in its drawing layer as [status] comes and goes (null: nothing to say), keeping its
 * last words while it fades away.
 */
@Composable
fun StatusNote(status: HeaderStatus?, modifier: Modifier = Modifier) {
    val kept = remember { arrayOfNulls<HeaderStatus>(1) }
    status?.let { kept[0] = it }
    AnimatedVisibility(visible = status != null, enter = EnterTransition.None, exit = ExitTransition.None, modifier = modifier) {
        val shown = transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) TvMotion.BannerIn else TvMotion.BannerOut },
            label = "status",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        val s = status ?: kept[0] ?: return@AnimatedVisibility
        Row(
            Modifier
                // Faded as it draws (ModulateAlpha): no offscreen copy, nothing laid out per frame.
                .graphicsLayer { alpha = shown.value; compositingStrategy = CompositingStrategy.ModulateAlpha }
                .widthIn(max = 420.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIcon(s.icon, size = 20.dp, tint = if (s.alert) Palette.danger else Palette.muted)
            Text(
                s.title, color = Palette.text, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                modifier = Modifier.padding(start = 10.dp),
            )
            s.detail?.let {
                Text(
                    "  ·  $it", color = Palette.muted, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * In place of a list that couldn't load with nothing saved to show instead: a symbol, what
 * happened, what happens next, and one button ([action], usually Try again). Centred and calm, in an
 * empty screen's muted words, as tvOS shows content that isn't available. The button doesn't take
 * the focus itself; Down from the header reaches it.
 */
@Composable
fun OfflinePage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Sym.CloudOff,
    action: String = "Try again",
    actionIcon: ImageVector = Sym.Refresh,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A little above the middle, where the eye rests on a TV.
        Column(Modifier.widthIn(max = 560.dp).padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TvIcon(icon, size = 64.dp, tint = Palette.muted)
            Text(
                title, style = MaterialTheme.typography.headlineSmall, color = Palette.text, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                body, style = MaterialTheme.typography.bodyLarge, color = Palette.muted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (onAction != null) PillButton(actionIcon, action, onAction, Modifier.padding(top = 28.dp))
        }
    }
}

/** A glass pill button with a symbol and a label, white with dark words when focused (the Filter button's look). */
@Composable
fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusButton(
        onClick = onClick,
        shape = Shapes.pill,
        color = Palette.surfaceHigh, focusedColor = Palette.text,
        content = Palette.text, focusedContent = Palette.background,
        modifier = modifier,
        focusedScale = 1.08f,
    ) { tint ->
        Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TintIcon(icon, tint.color, size = 22.dp)
            RowText(label, tint.color, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        // The lit rim, focused or not.
        Spacer(Modifier.matchParentSize().border(1.dp, Palette.rim, Shapes.pill))
    }
}

/**
 * The words for something that can't reach Google just now. "Offline" when the TV has no network
 * at all ([networkUp] false); with a network, it's Google that can't be reached, and it's trying
 * again by itself: "Reconnecting to Google Drive…". Never "sign in again": the sign-in is fine.
 */
object OfflineWords {
    /** A [StatusNote]'s title. */
    fun note(networkUp: Boolean, service: String) = if (networkUp) "Reconnecting to $service…" else "Offline"

    /** A page's title. */
    fun title(networkUp: Boolean, service: String) = if (networkUp) "Reconnecting to $service…" else "You're offline"

    /** A page's line: [what] ("Your library") comes back by itself. */
    fun body(networkUp: Boolean, what: String, service: String) =
        if (networkUp) "$what will appear as soon as $service answers." else "$what will appear as soon as this TV is back online."
}
