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

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                file(app).writeText("Version ${BuildConfig.VERSION_NAME} · thread ${thread.name}\n" + e.stackTraceToString().take(6_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Posts the saved crash (if any) as a notice, then forgets it. */
    fun report(c: Context) {
        val f = file(c)
        val text = runCatching { f.readText() }.getOrNull() ?: return
        f.delete()
        // The first lines say what failed and where; the full trace stays short enough to read.
        val lines = text.lines()
        val shown = (lines.take(2) + lines.drop(2).filter { it.contains("woodruff") || it.startsWith("Caused by") }.take(8)).joinToString("\n")
        Notices.post(c, NoticeSource.SYSTEM, "Woodruff closed unexpectedly last time", shown)
    }
}
