package com.woodruff.tvexplorer.ui.components

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.woodruff.tvexplorer.ui.motion.FocusColor
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.theme.Palette

/**
 * A filter choice: tinted with a check mark when on, white with dark words when focused. Laid out as
 * tv-material's Button was (at least 58 x 40 dp, centred, its padding), with CardStyle's lit rim.
 */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val pill = com.woodruff.tvexplorer.ui.theme.Shapes.pill
    FocusButton(
        onClick = onClick,
        shape = pill,
        color = if (selected) Palette.text.copy(alpha = 0.18f) else Palette.surfaceHigh, focusedColor = Palette.text,
        content = Palette.text, focusedContent = Palette.background,
        modifier = modifier,
        focusedScale = 1.06f,
    ) { tint ->
        Row(
            Modifier.defaultMinSize(minWidth = 58.dp, minHeight = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = if (selected && icon == null) Sym.Check else icon
            if (shown != null) TintIcon(shown, tint.color, size = 18.dp, modifier = Modifier.padding(end = 6.dp))
            RowText(label, tint.color, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.matchParentSize().border(1.dp, Palette.rim, pill))
    }
}

/** One horizontally scrolling row of pills. */
@Composable
fun FilterRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Netflix-style switch across the top of a tab: outlined pills, the chosen one filled; the focused
 * one turns solid. OK chooses (moving across changes nothing). Streaming (Movies / TV Shows),
 * Family Memories (Photos / Videos) and the Command Center (Cameras / Mail / …) use it.
 */
@Composable
fun TopTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { i, label ->
            val chosen = i == selected
            val pill = com.woodruff.tvexplorer.ui.theme.Shapes.pill
            FocusButton(
                onClick = { onSelect(i) },
                shape = pill,
                color = if (chosen) Palette.text.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                focusedColor = Palette.text,
                content = Palette.text, focusedContent = Palette.background,
                focusedScale = 1.06f,
                borderWidth = 1.5.dp,
                borderColor = Palette.text.copy(alpha = if (chosen) 0.85f else 0.4f),
                focusedBorderColor = Palette.text,
            ) { tint ->
                RowText(
                    label, tint.color, Modifier.padding(horizontal = 22.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chosen) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * A search field you can see: a wide pill with a magnifier and the words searched for (or a hint).
 * OK opens the keyboard; a field that took typing on focus would pop the TV's keyboard up just from
 * passing over it.
 */
@Composable
fun SearchBar(query: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pill = com.woodruff.tvexplorer.ui.theme.Shapes.pill
    FocusButton(
        onClick = onClick,
        shape = pill,
        color = Palette.surface, focusedColor = Palette.text,
        content = Palette.text, focusedContent = Palette.background,
        modifier = modifier,
        focusedScale = 1.03f,
    ) { tint ->
        // A hint is the words at 60 %, focused or not.
        val a = if (query.isEmpty()) 0.6f else 1f
        val words = remember(tint, a) { FocusColor(tint.motion, tint.rest.copy(alpha = tint.rest.alpha * a), tint.focused.copy(alpha = tint.focused.alpha * a)) }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TintIcon(Sym.Search, tint.color, size = 22.dp)
            RowText(
                query.ifEmpty { hint }, words, Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        // The lit rim, focused or not.
        Spacer(Modifier.matchParentSize().border(1.5.dp, Palette.rim, pill))
    }
}
