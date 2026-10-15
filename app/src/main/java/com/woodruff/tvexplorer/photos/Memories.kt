package com.woodruff.tvexplorer.photos

import android.content.Context
import java.io.File
import java.util.Calendar

/** "On this day": photos taken on today's date in earlier years, by their capture date. */
object Memories {
    /** [photos] taken on this day in earlier years, oldest year first. */
    fun today(c: Context, photos: List<File>, now: Calendar = Calendar.getInstance()): List<File> {
        if (photos.isEmpty()) return emptyList()
        val (dates, _) = MediaDates.quick(c, photos)
        return onThisDay(photos.map { it to (dates[it.absolutePath] ?: it.lastModified()) }, now).map { it }
    }

    /** Pure part, for tests: the files whose date is today's month and day in an earlier year. */
    fun <T> onThisDay(items: List<Pair<T, Long>>, now: Calendar): List<T> {
        val cal = now.clone() as Calendar
        val day = now.get(Calendar.DAY_OF_MONTH)
        val month = now.get(Calendar.MONTH)
        val year = now.get(Calendar.YEAR)
        return items.mapNotNull { (item, ms) ->
            cal.timeInMillis = ms
            if (cal.get(Calendar.MONTH) == month && cal.get(Calendar.DAY_OF_MONTH) == day && cal.get(Calendar.YEAR) < year) item to cal.get(Calendar.YEAR) else null
        }.sortedBy { it.second }.map { it.first }
    }
}
