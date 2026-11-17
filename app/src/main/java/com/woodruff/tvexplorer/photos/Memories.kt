package com.woodruff.tvexplorer.photos

import android.content.Context
import com.woodruff.tvexplorer.screensaver.Appearance
import java.io.File
import java.util.Calendar

/** "On this day": photos taken on today's date in earlier years, by their capture date. */
object Memories {
    /** Today's memories as last worked out: on [day], from the slideshow's [folder] (null: its usual ones), at [at]. */
    private class Cached(val day: Int, val folder: String?, val at: Long, val list: List<File>)
    @Volatile private var cached: Cached? = null

    /**
     * Today's memories from the slideshow folders, worked out at most once an hour, and again when the day or the
     * slideshow's folder changes or Family Memories does ([changed]): walking the photo folders is too slow to repeat
     * on every visit to Home. Photos deleted since are left out, so the slideshow never waits on one that's gone.
     */
    fun forToday(c: Context, photos: () -> List<File>): List<File> {
        val now = Calendar.getInstance()
        val day = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
        val folder = Appearance.slideshowFolder
        cached?.let { k -> if (fresh(k.day, k.folder, k.at, day, folder, System.currentTimeMillis())) return k.list.filter { it.isFile } }
        return today(c, photos(), now).also { cached = Cached(day, folder, System.currentTimeMillis(), it) }
    }

    /** Family Memories changed (a Google Photos pick or Remove all, a phone adding or deleting): worked out again. */
    fun changed() {
        cached = null
    }

    /** Whether memories worked out on [day] from [folder] at [at] still stand on [nowDay] for [nowFolder] at [nowMs]. */
    internal fun fresh(day: Int, folder: String?, at: Long, nowDay: Int, nowFolder: String?, nowMs: Long): Boolean =
        day == nowDay && folder == nowFolder && nowMs - at in 0 until HOUR_MS

    private const val HOUR_MS = 3_600_000L

    /** [photos] taken on this day in earlier years, oldest year first. */
    fun today(c: Context, photos: List<File>, now: Calendar = Calendar.getInstance()): List<File> {
        if (photos.isEmpty()) return emptyList()
        // Dates from the index / file names first; only when some are unknown, read them from the photos (cached after).
        val (quick, complete) = MediaDates.quick(c, photos)
        val dates = if (complete) quick else MediaDates.of(c, photos)
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
