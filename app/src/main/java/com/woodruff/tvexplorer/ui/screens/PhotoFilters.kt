package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.photos.PhotoFilter
import com.woodruff.tvexplorer.ui.components.FocusWhenReady
import com.woodruff.tvexplorer.ui.motion.DialogWindowMotion
import com.woodruff.tvexplorer.ui.motion.WindowMotion
import com.woodruff.tvexplorer.ui.motion.dpadGate
import com.woodruff.tvexplorer.ui.motion.focusPlatterHost
import com.woodruff.tvexplorer.ui.motion.rememberFocusPlatter
import com.woodruff.tvexplorer.ui.theme.Palette

/**
 * Photos / Videos filters in a side panel, like Movies': newest or oldest first, then a year, then a
 * month of that year. (They used to sit in rows above the pictures, crowding the top.)
 */
@Composable
fun PhotoFilterSheet(dates: Collection<Long>, filter: PhotoFilter, onChange: (PhotoFilter) -> Unit, onDismiss: () -> Unit) {
    val years = remember(dates) { PhotoFilter.years(dates) }
    val year = filter.year
    val months = remember(dates, year) { if (year == null) emptyList() else PhotoFilter.months(dates, year) }
    val first = remember { FocusRequester() }
    // One white highlight gliding from choice to choice.
    val platter = rememberFocusPlatter()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Glides in from the right edge, as Apple TV's side panels do, and back out: the system moves
        // the window (smooth even while the panel is still being built), so the panel doesn't move itself.
        DialogWindowMotion(WindowMotion.SideSheet)
        FocusWhenReady(first)
        // A dialog's keys never pass the activity's repeat gate: held arrows are gated here.
        Box(Modifier.fillMaxSize().dpadGate(), contentAlignment = Alignment.CenterEnd) {
            LazyColumn(
                modifier = Modifier
                    .width(400.dp).fillMaxHeight().padding(vertical = 28.dp, horizontal = 28.dp)
                    .background(Palette.glass, RoundedCornerShape(30.dp))
                    .border(1.dp, Palette.rim, RoundedCornerShape(30.dp))
                    // Over the panel's glass, under its choices.
                    .focusPlatterHost(platter, SheetOptionShape, Palette.text),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item { Text("Filter", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp)) }
                item { SheetHeading("Order") }
                item(key = "new") {
                    SheetOption(platter, "Newest first", filter.newestFirst, Modifier.focusRequester(first)) { onChange(filter.copy(newestFirst = true)) }
                }
                item(key = "old") { SheetOption(platter, "Oldest first", !filter.newestFirst) { onChange(filter.copy(newestFirst = false)) } }
                if (years.size > 1 || year != null) {
                    item { SheetHeading("Year") }
                    item(key = "y-all") { SheetOption(platter, "All dates", year == null) { onChange(filter.copy(year = null, month = null)) } }
                    items(years, key = { "y$it" }) { y -> SheetOption(platter, "$y", year == y) { onChange(filter.copy(year = y, month = null)) } }
                }
                if (year != null && (months.size > 1 || filter.month != null)) {
                    item { SheetHeading("Month") }
                    item(key = "m-all") { SheetOption(platter, "All of $year", filter.month == null) { onChange(filter.copy(month = null)) } }
                    items(months, key = { "m$it" }) { m -> SheetOption(platter, PhotoFilter.monthName(m), filter.month == m) { onChange(filter.copy(month = m)) } }
                }
            }
        }
    }
}
