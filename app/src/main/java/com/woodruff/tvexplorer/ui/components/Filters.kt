package com.woodruff.tvexplorer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
            containerColor = if (selected) Palette.accent.copy(alpha = 0.22f) else Palette.surfaceHigh,
            contentColor = if (selected) Palette.accent else Palette.text,
            focusedContainerColor = Palette.accent,
            focusedContentColor = Palette.onAccent,
        ),
    ) {
        val shown = if (selected && icon == null) Icons.Rounded.Check else icon
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
