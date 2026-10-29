package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.ui.components.ConfirmDialog
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.RowText
import com.woodruff.tvexplorer.ui.components.SearchBar
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.rememberRowTint
import com.woodruff.tvexplorer.ui.motion.DialogWindowMotion
import com.woodruff.tvexplorer.ui.motion.FocusPlatter
import com.woodruff.tvexplorer.ui.motion.TintIcon
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.motion.WindowMotion
import com.woodruff.tvexplorer.ui.motion.dpadGate
import com.woodruff.tvexplorer.ui.motion.focusPlatterHost
import com.woodruff.tvexplorer.ui.motion.rememberFocusMotionState
import com.woodruff.tvexplorer.ui.motion.rememberFocusPlatter
import com.woodruff.tvexplorer.ui.motion.tvFocusMotion
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes

/*
 * Movies / TV Shows browsing, Apple TV style:
 *   - one row: Movies / TV Shows (or the title), a search field you can see, and Filter;
 *   - Filter opens a panel on the right with everything else as plain lists: Overview, All or
 *     Unwatched, Sort, Genre and Decade (applied instantly, so the posters behind change as you
 *     go), then the Drive actions (Refresh, My Drive, Sign out…);
 *   - OK on the search field opens its own full screen (see LibrarySearch in DriveScreen.kt).
 */

/** The library's one row: Movies / TV Shows (or the title), the search field, and Filter. */
@Composable
fun LibraryHeader(
    title: String,
    all: List<DriveFile>,
    filter: LibraryFilter,
    onChange: (LibraryFilter) -> Unit,
    onSearch: () -> Unit,
    /** The library's other actions (Refresh, My Drive, Sign out…): at the end of the Filter panel. */
    more: List<MenuAction> = emptyList(),
    /** In place of the title: a switch between libraries (Streaming's Movies / TV Shows). */
    switcher: (@Composable () -> Unit)? = null,
) {
    var filtering by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (switcher != null) switcher()
        else Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        // A field you can see and press OK on, not just a magnifier.
        SearchBar("", "Search $title", onSearch, Modifier.weight(1f).padding(start = 24.dp))
        // Says what the posters are narrowed to ("Action · Newest"), so nothing's missing without a reason.
        RoundButton(Sym.Tune, filterLabel(filter), { filtering = true }, active = !filter.overview)
    }
    if (filtering) FilterSheet(all, filter, onChange, more, onDismiss = { filtering = false })
}

/** "Filter" on the Overview; otherwise what the grid shows: "All", "Action · 1990s", "Unwatched · Newest". */
private fun filterLabel(f: LibraryFilter): String {
    if (f.overview) return "Filter"
    val shown = listOfNotNull("Unwatched".takeIf { f.unwatched }, f.genre, f.decade?.let { "${it}s" }).ifEmpty { listOf("All") }
    return (shown + listOfNotNull(f.sort.label.takeIf { f.sort != LibrarySort.TITLE })).joinToString(" · ")
}

/**
 * tv-material's scale for a Surface that [tvFocusMotion] grows by [focused]: none of its own on focus
 * (its growth was read while composing, so it re-ran the composition on every frame), and back to
 * normal size while OK is held, as its press always did.
 */
internal fun surfacePressScale(focused: Float) = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f / focused)

/** A round glass icon button (a pill with its label when it has one); white with dark content when focused. */
@Composable
internal fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, onClick: () -> Unit, active: Boolean = false) {
    Surface(
        onClick = onClick,
        // Grows on focus as a layer (tv-material's own growth recomposed it every frame).
        modifier = Modifier.padding(start = 12.dp).tvFocusMotion(rememberFocusMotionState(), focusedScale = 1.08f),
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Palette.text.copy(alpha = 0.22f) else Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Palette.background,
        ),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Palette.rim), shape = Shapes.pill)),
        scale = surfacePressScale(1.08f),
    ) {
        Row(Modifier.padding(horizontal = if (label != null) 20.dp else 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 22.dp)
            if (label != null) Text(
                label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 260.dp),
            )
        }
    }
}

/**
 * Filter panel on the right: Overview, All or Unwatched, then Sort, Genre and Decade as short lists,
 * every choice applied at once (the posters behind update); then the library's other actions [more].
 * Back closes it.
 */
@Composable
private fun FilterSheet(
    all: List<DriveFile>,
    filter: LibraryFilter,
    onChange: (LibraryFilter) -> Unit,
    more: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    // Worked out when the panel opens, not with every recomposition of the screen behind it.
    val genres = remember(all) { Library.genres(all) }
    val decades = remember(all) { Library.decades(all) }
    val sorts = remember(all) {
        LibrarySort.entries.filter { s ->
            when (s) {
                LibrarySort.RATING -> all.any { Library.info(it).rating != null }
                LibrarySort.YEAR -> decades.isNotEmpty()
                else -> true
            }
        }
    }
    val first = remember { FocusRequester() }
    var confirming by remember { mutableStateOf<MenuAction?>(null) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // One white highlight gliding from choice to choice.
    val platter = rememberFocusPlatter()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Glides in from the right edge, as Apple TV's side panels do, and back out: the system moves
        // the window (smooth even while the panel is still being built), so the panel doesn't move itself.
        DialogWindowMotion(WindowMotion.SideSheet)
        com.woodruff.tvexplorer.ui.components.FocusWhenReady(first)
        // A dialog's keys never pass the activity's repeat gate: held arrows are gated here.
        Box(Modifier.fillMaxSize().dpadGate(), contentAlignment = Alignment.CenterEnd) {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .width(400.dp).fillMaxHeight().padding(vertical = 28.dp, horizontal = 28.dp)
                    .background(Palette.glass, RoundedCornerShape(30.dp))
                    .border(1.dp, Palette.rim, RoundedCornerShape(30.dp))
                    // Over the panel's glass, under its choices.
                    .focusPlatterHost(platter, SheetOptionShape, Palette.text),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp)) {
                        Text("Filter", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    }
                }
                // The Overview (a featured title and rows), or the library as a grid: all of it, or
                // what hasn't been watched.
                item { SheetHeading("Show") }
                item(key = "overview") {
                    // How the library opens, nothing narrowed or re-sorted behind it (the rows don't show a sort).
                    SheetOption(platter, "Overview", filter.overview, Modifier.focusRequester(first)) { onChange(LibraryFilter(overview = true)) }
                }
                item(key = "all") {
                    SheetOption(platter, "All titles", !filter.overview && !filter.unwatched && filter.genre == null && filter.decade == null) {
                        onChange(filter.copy(overview = false, unwatched = false, genre = null, decade = null))
                    }
                }
                // Films without a ✓, shows not started; a genre or decade chosen with it still applies.
                item(key = "unwatched") {
                    SheetOption(platter, "Unwatched", !filter.overview && filter.unwatched) { onChange(filter.copy(overview = false, unwatched = true)) }
                }
                // A sort, genre or decade describes the grid: choosing one leaves the Overview for it.
                item { SheetHeading("Sort by") }
                items(sorts, key = { "s-" + it.name }) { s ->
                    SheetOption(platter, s.label, !filter.overview && filter.sort == s) { onChange(filter.copy(sort = s, overview = false)) }
                }
                if (genres.isNotEmpty()) {
                    item { SheetHeading("Genre") }
                    item(key = "g-all") { SheetOption(platter, "All genres", !filter.overview && filter.genre == null) { onChange(filter.copy(genre = null, overview = false)) } }
                    items(genres, key = { "g-$it" }) { g -> SheetOption(platter, g, filter.genre == g) { onChange(filter.copy(genre = g, overview = false)) } }
                }
                if (decades.isNotEmpty()) {
                    item { SheetHeading("Decade") }
                    item(key = "d-all") { SheetOption(platter, "Any decade", !filter.overview && filter.decade == null) { onChange(filter.copy(decade = null, overview = false)) } }
                    items(decades, key = { "d-$it" }) { d -> SheetOption(platter, "${d}s", filter.decade == d) { onChange(filter.copy(decade = d, overview = false)) } }
                }
                // Right after the filters it clears, and before the Drive actions: never reached through Sign out.
                if (!filter.overview && (filter.unwatched || filter.genre != null || filter.decade != null || filter.sort != LibrarySort.TITLE)) {
                    item { Spacer(Modifier.size(10.dp)) }
                    item(key = "clear") {
                        SheetOption(platter, "Clear filters", false, accent = true) {
                            // Back to how the library opens: the Overview.
                            onChange(LibraryFilter(overview = true))
                            // This option goes away with the filters: put the focus back at the top
                            // rather than leaving it nowhere.
                            scope.launch {
                                list.scrollToItem(0)
                                androidx.compose.runtime.withFrameNanos {}
                                runCatching { first.requestFocus() }
                            }
                        }
                    }
                }
                if (more.isNotEmpty()) {
                    item { SheetHeading("Google Drive") }
                    items(more, key = { "a-" + it.label }) { a ->
                        // Sign out asks first: it's one row past the filters, and one stray OK would undo the sign-in.
                        SheetOption(platter, a.label, false, destructive = a.destructive) { if (a.destructive) confirming = a else { onDismiss(); a.run() } }
                    }
                }
            }
        }
        confirming?.let { a ->
            ConfirmDialog(
                "${a.label}?", "Movies and TV Shows will need Google sign-in again: one QR scan on your phone.", "Sign out",
                onConfirm = { onDismiss(); a.run() }, onDismiss = { confirming = null },
            )
        }
    }
}

@Composable
internal fun SheetHeading(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.muted,
        modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** A choice's corners, and the sheet's white highlight's. */
internal val SheetOptionShape = RoundedCornerShape(16.dp)

/**
 * A choice in a side sheet: plain until focused, then white with dark words, the sheet's white
 * [platter] gliding over from the choice the focus left while its words turn dark as they draw.
 * [destructive]: red (Sign out), dark like the rest once focused on the white highlight.
 */
@Composable
internal fun SheetOption(
    platter: FocusPlatter, label: String, checked: Boolean, modifier: Modifier = Modifier, accent: Boolean = false, destructive: Boolean = false, onClick: () -> Unit,
) {
    val motion = rememberFocusMotionState()
    val tint = rememberRowTint(motion, if (destructive) Palette.danger else if (accent) Palette.accent else Palette.text, Palette.background)
    TvCard(
        onClick = onClick,
        shape = SheetOptionShape,
        modifier = modifier.fillMaxWidth(),
        motion = motion,
        focusedScale = 1.02f,
        platter = platter,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            RowText(label, tint.color, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (checked) TintIcon(Sym.Check, tint.color, size = 22.dp)
        }
    }
}
