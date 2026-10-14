package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.woodruff.tvexplorer.photos.PhotoFilter
import com.woodruff.tvexplorer.ui.components.FilterPill
import com.woodruff.tvexplorer.ui.components.FilterRow

/** Photos / Videos filters: newest or oldest first, then a year, then a month of that year. */
@Composable
fun PhotoFilters(dates: Collection<Long>, filter: PhotoFilter, onChange: (PhotoFilter) -> Unit, modifier: Modifier = Modifier) {
    val years = remember(dates) { PhotoFilter.years(dates) }
    Column(modifier) {
        FilterRow {
            FilterPill(if (filter.newestFirst) "Newest first" else "Oldest first", false, {
                onChange(filter.copy(newestFirst = !filter.newestFirst))
            }, icon = Icons.Rounded.Sort)
            if (years.size > 1 || filter.year != null) {
                FilterPill("All dates", filter.year == null, { onChange(filter.copy(year = null, month = null)) })
                years.forEach { y ->
                    FilterPill("$y", filter.year == y, { onChange(filter.copy(year = if (filter.year == y) null else y, month = null)) })
                }
            }
        }
        val year = filter.year
        if (year != null) {
            val months = remember(dates, year) { PhotoFilter.months(dates, year) }
            if (months.size > 1 || filter.month != null) FilterRow {
                FilterPill("All of $year", filter.month == null, { onChange(filter.copy(month = null)) })
                months.forEach { m ->
                    FilterPill(PhotoFilter.monthName(m), filter.month == m, { onChange(filter.copy(month = if (filter.month == m) null else m)) })
                }
            }
        }
    }
}
