package com.woodruff.tvexplorer.util

import android.content.Context
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import java.io.File

/**
 * Remembers why the app last crashed and shows it once in Notifications on the next launch, so a
 * crash on the TV can be reported without a computer and adb. Only Java/Kotlin crashes are caught;
 * a crash inside native code (e.g. WebRTC) ends the process before any handler runs.
 */
object CrashLog {
    private fun file(c: Context) = File(c.applicationContext.filesDir, "last_crash.txt")
    /** The full report, kept after the notice so the phone app can show and copy it. */
    private fun report(c: Context) = File(c.applicationContext.filesDir, "crash_report.txt")

    fun last(c: Context): String? = runCatching { report(c).takeIf { it.isFile }?.readText() }.getOrNull()
    fun clear(c: Context) { report(c).delete() }

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val stamp = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date())
                file(app).writeText("Version ${BuildConfig.VERSION_NAME} · $stamp · thread ${thread.name}\n" + e.stackTraceToString().take(6_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Posts the saved crash (if any) as a notice, then forgets it. */
    fun report(c: Context) {
        val f = file(c)
        val text = runCatching { f.readText() }.getOrNull() ?: return
        runCatching { report(c).writeText(text) }
        f.delete()
        // The first lines say what failed and where; the full trace stays short enough to read.
        val lines = text.lines()
        // The top frames say where it failed even when that's inside a library; then our own
        // frames and any "Caused by" further down.
        val top = lines.take(8)
        val ours = lines.drop(8).filter { it.contains("woodruff") || it.startsWith("Caused by") }.take(8)
        val shown = (top + ours).joinToString("\n") { it.trim() }
        Notices.post(c, NoticeSource.SYSTEM, "Woodruff closed unexpectedly last time", "$shown\n\nThe full report is in the phone app: Settings → Last crash.")
    }
}
