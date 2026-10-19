package com.woodruff.tvexplorer.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.FilterPill
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.FilterRow

/**
 * Movies / TV Shows filters in one short row: sort, search, a decade picker and a genre picker
 * (genres need a TMDB key; that pill only appears when the titles have some). Decades and genres
 * open a list instead of spreading every option across the screen, so the posters get the room.
 * [all] is the unfiltered listing.
 */
@Composable
fun LibraryFilters(all: List<DriveFile>, filter: LibraryFilter, onChange: (LibraryFilter) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val genres = remember(all) { Library.genres(all) }
    val decades = remember(all) { Library.decades(all) }
    val sorts = remember(all) {
        // "Top rated" only when there are ratings to sort by; "Newest" only with years.
        LibrarySort.entries.filter { s ->
            when (s) {
                LibrarySort.RATING -> all.any { Library.info(it).rating != null }
                LibrarySort.YEAR -> decades.isNotEmpty()
                else -> true
            }
        }
    }
    var picking by remember { mutableStateOf<String?>(null) }
    FilterRow(modifier) {
        FilterPill(filter.sort.label, false, {
            onChange(filter.copy(sort = sorts[(sorts.indexOf(filter.sort) + 1) % sorts.size]))
        }, icon = Icons.Rounded.Sort)
        FilterPill(filter.query.ifBlank { "Search" }, filter.query.isNotBlank(), onSearch, icon = Icons.Rounded.Search)
        if (decades.isNotEmpty()) FilterPill(filter.decade?.let { "${it}s" } ?: "Decade", filter.decade != null, { picking = "decade" }, icon = Icons.Rounded.CalendarMonth)
        if (genres.isNotEmpty()) FilterPill(filter.genre ?: "Genre", filter.genre != null, { picking = "genre" }, icon = Icons.Rounded.Category)
        if (filter.narrowed) FilterPill("Clear", false, { onChange(LibraryFilter(sort = filter.sort)) }, icon = Icons.Rounded.Close)
    }
    when (picking) {
        "decade" -> ActionMenu("Decade", null, onDismiss = { picking = null }, actions = buildList {
            add(MenuAction("Any decade", Icons.Rounded.CalendarMonth) { onChange(filter.copy(decade = null)) })
            decades.forEach { d -> add(MenuAction("${d}s", if (filter.decade == d) Icons.Rounded.Check else Icons.Rounded.CalendarMonth) { onChange(filter.copy(decade = d)) }) }
        })
        "genre" -> ActionMenu("Genre", null, onDismiss = { picking = null }, actions = buildList {
            add(MenuAction("All genres", Icons.Rounded.Category) { onChange(filter.copy(genre = null)) })
            genres.forEach { g -> add(MenuAction(g, if (filter.genre == g) Icons.Rounded.Check else Icons.Rounded.Category) { onChange(filter.copy(genre = g)) }) }
        })
    }
}
