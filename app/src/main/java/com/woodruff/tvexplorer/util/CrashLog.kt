package com.woodruff.tvexplorer.util

import android.content.Context
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.tvs.scrubbed
import java.io.File

/**
 * Remembers why the app last crashed and shows it once in Notifications on the next launch, so a
 * crash on the TV can be reported without a computer and adb. Only Java/Kotlin crashes are caught;
 * a crash inside native code (e.g. WebRTC) ends the process before any handler runs.
 */
object CrashLog {
    private fun file(c: Context) = File(c.applicationContext.filesDir, "last_crash.txt")
    /** The full report, kept after the notice so the phone app can show and copy it. */
    private fun reportFile(c: Context) = File(c.applicationContext.filesDir, "crash_report.txt")

    fun last(c: Context): String? = runCatching { reportFile(c).takeIf { it.isFile }?.readText() }.getOrNull()
    fun clear(c: Context) { reportFile(c).delete() }

    /**
     * The newest crash report, also one whose notice hasn't been shown yet (a crash while Woodruff
     * ran in the background is reported the next time it's opened): the TV health report's last crash.
     */
    fun latest(c: Context): String? = runCatching { file(c).takeIf { it.isFile }?.readText() }.getOrNull() ?: last(c)

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val text = runCatching {
                val stamp = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date())
                "Version ${BuildConfig.VERSION_NAME} · $stamp · thread ${thread.name}\n" + e.stackTraceToString().take(6_000)
            }.getOrNull()
            text?.let { runCatching { file(app).writeText(it) } }
            // Soon after Woodruff's screen opened: one more close while starting, for self-repair.
            runCatching { EarlyCrashes.crashed(app, text) }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Keeps the saved crash (if any) for the phone app's Settings → Last Crash on This TV, without a
     * notice: for the recovery screen, which says what happened itself. Returns it. Not on the main thread.
     */
    fun keep(c: Context): String? {
        val f = file(c)
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        runCatching { reportFile(c).writeText(text) }
        f.delete()
        return text
    }

    /** Posts the saved crash (if any) as a notice, then forgets it. */
    fun report(c: Context) {
        val text = keep(c) ?: return
        Notices.post(c, NoticeSource.SYSTEM, "Woodruff closed unexpectedly last time", noticeBody(text))
    }

    /**
     * The notice's words for the crash report [text]. The first lines say what failed and where, even when
     * that's inside a library; then Woodruff's own frames and any "Caused by" further down, so the trace stays
     * short enough to read. It's on the TV's screen, and an exception's message can carry what it failed on:
     * keys, tokens, addresses and emails are taken out ([scrubbed]); if that can't be done, only where the
     * full report is.
     */
    internal fun noticeBody(text: String): String {
        val lines = runCatching { scrubbed(text) }.getOrNull()?.lines() ?: return FULL_REPORT
        val top = lines.take(8)
        val ours = lines.drop(8).filter { it.contains("woodruff") || it.startsWith("Caused by") }.take(8)
        val shown = (top + ours).joinToString("\n") { it.trim() }
        return "$shown\n\n$FULL_REPORT"
    }

    /** Where the whole report is: the phone app's own row for it (Settings → Last Crash on This TV). */
    private const val FULL_REPORT = "The full report is in the phone app: Settings → Last Crash on This TV."
}
