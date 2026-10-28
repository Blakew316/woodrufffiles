package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.Shapes

/*
 * Movies / TV Shows browsing, Apple TV style:
 *   - a header with the title and three round glass buttons: Search, Filter and More;
 *   - a row of category tabs under it (All + genres, or decades without TMDB genres), chosen
 *     with OK;
 *   - Filter opens a panel on the right with Sort, Decade and Genre as plain lists, applied
 *     instantly so the posters behind change as you go;
 *   - Search opens its own full screen (see LibrarySearch in DriveScreen.kt).
 */

/** Title, Search / Filter / More buttons, then the category tabs. */
@Composable
fun LibraryHeader(
    title: String,
    all: List<DriveFile>,
    filter: LibraryFilter,
    onChange: (LibraryFilter) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    /** In place of the title: a switch between libraries (Streaming's Movies / TV Shows). */
    switcher: (@Composable () -> Unit)? = null,
) {
    var filtering by remember { mutableStateOf(false) }
    val genres = remember(all) { Library.genres(all) }
    val decades = remember(all) { Library.decades(all) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (switcher != null) Box(Modifier.weight(1f)) { switcher() }
            else Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            // What the grid is narrowed to, so it's never a mystery why something's missing.
            val summary = listOfNotNull(filter.genre, filter.decade?.let { "${it}s" }, filter.sort.label.takeIf { filter.sort != LibrarySort.TITLE })
            if (summary.isNotEmpty()) Text(summary.joinToString(" · "), color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 18.dp))
            RoundButton(Icons.Rounded.Search, "Search", onSearch)
            RoundButton(Icons.Rounded.Tune, "Filter", { filtering = true }, active = filter.genre != null || filter.decade != null || filter.sort != LibrarySort.TITLE)
            RoundButton(Icons.Rounded.MoreHoriz, null, onMore)
        }
        // Tabs: Overview (featured title and rows), All (A–Z), then the genres when TMDB has tagged
        // the titles, otherwise the decades.
        val tabs = remember(genres, decades) {
            listOf(Tab("Overview", overview = true), Tab("All")) + (
                if (genres.isNotEmpty()) genres.map { Tab(it, genre = it) }
                else if (decades.size > 1) decades.map { Tab("${it}s", decade = it) }
                else emptyList()
            )
        }
        val selected = when {
            filter.overview -> tabs[0]
            else -> tabs.firstOrNull { !it.overview && it.genre == filter.genre && it.decade == filter.decade } ?: tabs[1]
        }
        CategoryTabs(
            tabs, selected = selected, label = { it.label },
            onSelect = { t -> onChange(filter.copy(overview = t.overview, genre = t.genre, decade = t.decade)) },
        )
    }
    // Choosing a sort or a category in the panel leaves the Overview for the grid it describes.
    if (filtering) FilterSheet(all, genres, decades, filter, { onChange(it.copy(overview = false)) }, onDismiss = { filtering = false })
}

/** A category tab: the Overview, All, or one genre / decade. */
private data class Tab(val label: String, val overview: Boolean = false, val genre: String? = null, val decade: Int? = null)

/** A round glass icon button (a pill with its label when it has one); white with dark content when focused. */
@Composable
internal fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, onClick: () -> Unit, active: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(start = 12.dp),
        shape = ClickableSurfaceDefaults.shape(Shapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Palette.accent.copy(alpha = 0.3f) else Palette.surfaceHigh,
            contentColor = Palette.text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B1220),
        ),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Palette.rim), shape = Shapes.pill)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Row(Modifier.padding(horizontal = if (label != null) 20.dp else 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIcon(icon, size = 22.dp)
            if (label != null) Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * Text tabs, Apple TV style: the chosen one sits in a glass capsule, the focused one turns white.
 * Only pressing OK on a tab chooses it; moving across them changes nothing.
 */
@Composable
private fun <T> CategoryTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val select by rememberUpdatedState(onSelect)
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        items(options, key = { it.toString() }) { o ->
            val isSelected = o == selected
            Surface(
                onClick = { select(o) },
                shape = ClickableSurfaceDefaults.shape(Shapes.pill),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) Palette.surfaceHigh else Color.Transparent,
                    contentColor = if (isSelected) Palette.text else Palette.muted,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color(0xFF0B1220),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            ) {
                Text(
                    label(o), style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Filter panel on the right: Sort, Decade and Genre as three short lists. Every choice applies at
 * once (the posters behind update), Back closes it.
 */
@Composable
private fun FilterSheet(
    all: List<DriveFile>,
    genres: List<String>,
    decades: List<Int>,
    filter: LibraryFilter,
    onChange: (LibraryFilter) -> Unit,
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
                item { SheetHeading("Sort by") }
                items(sorts, key = { "s-" + it.name }) { s ->
                    SheetOption(s.label, filter.sort == s, Modifier.then(if (s == sorts.first()) Modifier.focusRequester(first) else Modifier)) {
                        onChange(filter.copy(sort = s))
                    }
                }
                if (genres.isNotEmpty()) {
                    item { SheetHeading("Genre") }
                    item(key = "g-all") { SheetOption("All genres", filter.genre == null) { onChange(filter.copy(genre = null)) } }
                    items(genres, key = { "g-$it" }) { g -> SheetOption(g, filter.genre == g) { onChange(filter.copy(genre = g)) } }
                }
                if (decades.isNotEmpty()) {
                    item { SheetHeading("Decade") }
                    item(key = "d-all") { SheetOption("Any decade", filter.decade == null) { onChange(filter.copy(decade = null)) } }
                    items(decades, key = { "d-$it" }) { d -> SheetOption("${d}s", filter.decade == d) { onChange(filter.copy(decade = d)) } }
                }
                if (filter.genre != null || filter.decade != null || filter.sort != LibrarySort.TITLE) {
                    item { Spacer(Modifier.size(10.dp)) }
                    item(key = "clear") {
                        SheetOption("Clear filters", false, accent = true) {
                            onChange(LibraryFilter())
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
            }
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

@Composable
internal fun SheetOption(label: String, checked: Boolean, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (accent) Palette.accent else Palette.text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B1220),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (checked) TvIcon(Icons.Rounded.Check, size = 22.dp)
        }
    }
}
