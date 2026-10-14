package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.ui.components.FilterPill
import com.woodruff.tvexplorer.ui.components.FilterRow

/**
 * Movies / TV Shows filters: sort, search, decades, and genres (genres need a TMDB key; the
 * row only appears when the titles have some). [all] is the unfiltered listing.
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
    Column(modifier) {
        FilterRow {
            FilterPill(filter.sort.label, false, {
                onChange(filter.copy(sort = sorts[(sorts.indexOf(filter.sort) + 1) % sorts.size]))
            }, icon = Icons.Rounded.Sort)
            FilterPill(filter.query.ifBlank { "Search" }, filter.query.isNotBlank(), onSearch, icon = Icons.Rounded.Search)
            if (filter.narrowed) FilterPill("Clear", false, { onChange(LibraryFilter(sort = filter.sort)) }, icon = Icons.Rounded.Close)
            decades.forEach { d ->
                FilterPill("${d}s", filter.decade == d, { onChange(filter.copy(decade = if (filter.decade == d) null else d)) })
            }
        }
        if (genres.isNotEmpty()) FilterRow {
            FilterPill("All genres", filter.genre == null, { onChange(filter.copy(genre = null)) })
            genres.forEach { g ->
                FilterPill(g, filter.genre == g, { onChange(filter.copy(genre = if (filter.genre == g) null else g)) })
            }
        }
    }
}
