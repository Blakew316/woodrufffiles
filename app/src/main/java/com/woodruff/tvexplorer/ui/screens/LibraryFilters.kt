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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.graphics.Color
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
import com.woodruff.tvexplorer.ui.components.SearchBar
import com.woodruff.tvexplorer.ui.components.TvIcon
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
    val genres = remember(all) { Library.genres(all) }
    val decades = remember(all) { Library.decades(all) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (switcher != null) switcher()
        else Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        // A field you can see and press OK on, not just a magnifier.
        SearchBar("", "Search $title", onSearch, Modifier.weight(1f).padding(start = 24.dp))
        // Says what the posters are narrowed to ("Action · Newest"), so nothing's missing without a reason.
        RoundButton(Sym.Tune, filterLabel(filter), { filtering = true }, active = !filter.overview)
    }
    if (filtering) FilterSheet(all, genres, decades, filter, onChange, more, onDismiss = { filtering = false })
}

/** "Filter" on the Overview; otherwise what the grid shows: "All", "Action · 1990s", "Unwatched · Newest". */
private fun filterLabel(f: LibraryFilter): String {
    if (f.overview) return "Filter"
    val shown = listOfNotNull("Unwatched".takeIf { f.unwatched }, f.genre, f.decade?.let { "${it}s" }).ifEmpty { listOf("All") }
    return (shown + listOfNotNull(f.sort.label.takeIf { f.sort != LibrarySort.TITLE })).joinToString(" · ")
}

/** A round glass icon button (a pill with its label when it has one); white with dark content when focused. */
@Composable
internal fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, onClick: () -> Unit, active: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(start = 12.dp),
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Palette.text.copy(alpha = 0.22f) else Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Palette.background,
        ),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Palette.rim), shape = Shapes.pill)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
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
    genres: List<String>,
    decades: List<Int>,
    filter: LibraryFilter,
    onChange: (LibraryFilter) -> Unit,
    more: List<MenuAction>,
    onDismiss: () -> Unit,
) {
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
    // Glides in from the right edge, as Apple TV's side panels do.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.tween(340, easing = com.woodruff.tvexplorer.ui.SmoothEase)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.woodruff.tvexplorer.ui.components.FocusWhenReady(first)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = enter.value
                        translationX = (1f - enter.value) * 64.dp.toPx()
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
                    .width(400.dp).fillMaxHeight().padding(vertical = 28.dp, horizontal = 28.dp)
                    .background(Palette.glass, RoundedCornerShape(30.dp))
                    .border(1.dp, Palette.rim, RoundedCornerShape(30.dp)),
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
                    SheetOption("Overview", filter.overview, Modifier.focusRequester(first)) { onChange(LibraryFilter(overview = true)) }
                }
                item(key = "all") {
                    SheetOption("All titles", !filter.overview && !filter.unwatched && filter.genre == null && filter.decade == null) {
                        onChange(filter.copy(overview = false, unwatched = false, genre = null, decade = null))
                    }
                }
                // Films without a ✓, shows not started; a genre or decade chosen with it still applies.
                item(key = "unwatched") {
                    SheetOption("Unwatched", !filter.overview && filter.unwatched) { onChange(filter.copy(overview = false, unwatched = true)) }
                }
                // A sort, genre or decade describes the grid: choosing one leaves the Overview for it.
                item { SheetHeading("Sort by") }
                items(sorts, key = { "s-" + it.name }) { s ->
                    SheetOption(s.label, !filter.overview && filter.sort == s) { onChange(filter.copy(sort = s, overview = false)) }
                }
                if (genres.isNotEmpty()) {
                    item { SheetHeading("Genre") }
                    item(key = "g-all") { SheetOption("All genres", !filter.overview && filter.genre == null) { onChange(filter.copy(genre = null, overview = false)) } }
                    items(genres, key = { "g-$it" }) { g -> SheetOption(g, filter.genre == g) { onChange(filter.copy(genre = g, overview = false)) } }
                }
                if (decades.isNotEmpty()) {
                    item { SheetHeading("Decade") }
                    item(key = "d-all") { SheetOption("Any decade", !filter.overview && filter.decade == null) { onChange(filter.copy(decade = null, overview = false)) } }
                    items(decades, key = { "d-$it" }) { d -> SheetOption("${d}s", filter.decade == d) { onChange(filter.copy(decade = d, overview = false)) } }
                }
                // Right after the filters it clears, and before the Drive actions: never reached through Sign out.
                if (!filter.overview && (filter.unwatched || filter.genre != null || filter.decade != null || filter.sort != LibrarySort.TITLE)) {
                    item { Spacer(Modifier.size(10.dp)) }
                    item(key = "clear") {
                        SheetOption("Clear filters", false, accent = true) {
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
                        SheetOption(a.label, false, destructive = a.destructive) { if (a.destructive) confirming = a else { onDismiss(); a.run() } }
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

/** [destructive]: red (Sign out), dark like the rest once focused on the white highlight. */
@Composable
internal fun SheetOption(
    label: String, checked: Boolean, modifier: Modifier = Modifier, accent: Boolean = false, destructive: Boolean = false, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (destructive) Palette.danger else if (accent) Palette.accent else Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Palette.background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (checked) TvIcon(Sym.Check, size = 22.dp)
        }
    }
}
