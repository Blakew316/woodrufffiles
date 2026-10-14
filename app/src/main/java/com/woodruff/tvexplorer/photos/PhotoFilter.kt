package com.woodruff.tvexplorer.photos

import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** Photos / Videos filter: order by when each was taken, optionally one year or one month. */
data class PhotoFilter(val newestFirst: Boolean = true, val year: Int? = null, /** 1–12 */ val month: Int? = null) {
    val narrowed: Boolean get() = year != null

    companion object {
        /** Years that have something, newest first. */
        fun years(dates: Collection<Long>, zone: TimeZone = TimeZone.getDefault()): List<Int> =
            dates.map { ym(it, zone).first }.distinct().sortedDescending()

        /** Months (1–12) of [year] that have something, in calendar order. */
        fun months(dates: Collection<Long>, year: Int, zone: TimeZone = TimeZone.getDefault()): List<Int> =
            dates.map { ym(it, zone) }.filter { it.first == year }.map { it.second }.distinct().sorted()

        fun monthName(month: Int, locale: Locale = Locale.getDefault()): String = DateFormatSymbols(locale).shortMonths[month - 1]

        /**
         * Folders stay first, in their usual order; files are filtered and sorted by [dates]
         * (path → ms). Files missing from [dates] count as oldest.
         */
        fun <T> apply(
            items: List<T>, path: (T) -> String, isFolder: (T) -> Boolean, dates: Map<String, Long>,
            filter: PhotoFilter, zone: TimeZone = TimeZone.getDefault(),
        ): List<T> {
            val (folders, files) = items.partition(isFolder)
            val kept = files.filter { f ->
                val d = dates[path(f)] ?: return@filter filter.year == null
                val (y, m) = ym(d, zone)
                (filter.year == null || y == filter.year) && (filter.month == null || m == filter.month)
            }
            val byDate = compareBy<T> { dates[path(it)] ?: Long.MIN_VALUE }.thenBy { path(it) }
            return folders + kept.sortedWith(if (filter.newestFirst) byDate.reversed() else byDate)
        }

        private fun ym(ms: Long, zone: TimeZone): Pair<Int, Int> =
            GregorianCalendar(zone).apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) to it.get(Calendar.MONTH) + 1 }
    }
}
