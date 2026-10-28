package com.woodruff.tvexplorer.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    /** "1h 05m" / "12m" / "45s" for Continue watching. */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> String.format(Locale.getDefault(), "%dh %02dm", h, m)
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    // One formatter, made once: building one per list row showed up while scrolling. Only used on
    // the main thread (DateFormat isn't thread-safe).
    private val dateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    fun date(millis: Long): String = dateFormat.format(Date(millis))
}
