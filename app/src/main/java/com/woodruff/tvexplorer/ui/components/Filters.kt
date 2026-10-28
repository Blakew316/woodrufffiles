package com.woodruff.tvexplorer.ui.components

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.ui.theme.Palette

/** A filter choice: tinted with a check mark when on, accent-filled when focused. */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        border = ButtonDefaults.border(border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Palette.rim), shape = com.woodruff.tvexplorer.ui.theme.Shapes.pill)),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Palette.text.copy(alpha = 0.18f) else Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Palette.background,
        ),
    ) {
        val shown = if (selected && icon == null) Sym.Check else icon
        if (shown != null) TvIcon(shown, size = 18.dp, modifier = Modifier.padding(end = 6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
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
            androidx.tv.material3.Surface(
                onClick = { onSelect(i) },
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(pill),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = if (chosen) Palette.text.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = Palette.text,
                    focusedContainerColor = Palette.text,
                    focusedContentColor = Palette.background,
                ),
                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                    border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.5.dp, Palette.text.copy(alpha = if (chosen) 0.85f else 0.4f)), shape = pill),
                    focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.5.dp, Palette.text), shape = pill),
                ),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            ) {
                Text(
                    label, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chosen) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp),
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
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(pill),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface, contentColor = Palette.text,
            focusedContainerColor = Palette.text, focusedContentColor = Palette.background,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.5.dp, Palette.rim), shape = pill),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(Sym.Search, size = 22.dp)
            Text(
                query.ifEmpty { hint }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = if (query.isEmpty()) 0.6f else 1f),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
