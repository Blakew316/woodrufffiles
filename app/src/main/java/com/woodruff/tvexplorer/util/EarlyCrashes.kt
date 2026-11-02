package com.woodruff.tvexplorer.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.woodruff.tvexplorer.BuildConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Whether this version of Woodruff keeps closing as it starts, so it can repair itself. After one
 * such close, every start of Woodruff (the TV turning on, the phone connection, the screen) looks for
 * a fixed version at once ([com.woodruff.tvexplorer.update.Updater]); after two in a row, the main
 * screen opens as a plain recovery screen instead ([com.woodruff.tvexplorer.update.RecoveryView]),
 * built so it can't close the same way.
 *
 * "As it starts" is measured from Woodruff's screen, not from the process: the phone connection often
 * started the process hours before anyone opens Woodruff. A close counts when it comes within
 * [EARLY_MS] of the main screen opening ([opened]): a crash in Kotlin or Java, which [CrashLog]'s
 * handler hands to [crashed]; and on Android 11 and later also a crash in native code (the cameras'
 * WebRTC, a video decoder) or Woodruff freezing, which no handler sees: Android keeps why each process
 * ended, and it's asked the next time Woodruff starts.
 *
 * Closes are counted per version (a new version starts afresh) and in a row: the screen staying up for
 * [EARLY_MS] sets the count back to none.
 */
object EarlyCrashes {
    /** A close this soon after the main screen opened is a close while starting. */
    const val EARLY_MS = 45_000L

    /** After this many early closes in a row, the next start shows the recovery screen. */
    const val RECOVER_AT = 2

    /** How a process that had just opened the screen ended, as Android 11+ tells it. */
    enum class Exit { CRASH, NATIVE_CRASH, FROZE, OTHER }

    /**
     * What's kept between runs: [count] early closes in a row of [version]. While the screen that opened
     * last hasn't stayed up for [EARLY_MS] yet, also when it opened ([openedAt], wall clock) and in which
     * process ([pid]): if that process then ended without the handler counting it, Android is asked why
     * at the next start.
     */
    internal data class Record(val version: Int = 0, val count: Int = 0, val openedAt: Long = 0, val pid: Int = 0)

    // ---- The rules (pure, for tests) ------------------------------------------------------------------

    /** [r]'s count for version [current]: another version's doesn't carry over. */
    internal fun countFor(r: Record, current: Int): Int = if (r.version == current) r.count else 0

    /** A close while starting: one more in a row. */
    internal fun afterCrash(r: Record, current: Int) = Record(current, countFor(r, current) + 1)

    /** The screen opened at [at] in process [pid]; the count stands until it has stayed up. */
    internal fun afterOpen(r: Record, current: Int, at: Long, pid: Int) = Record(current, countFor(r, current), at, pid)

    /** The screen stayed up for [EARLY_MS]: Woodruff starts fine, so none in a row. */
    @Suppress("UNUSED_PARAMETER")
    internal fun afterSettle(r: Record, current: Int) = Record(current)

    /** "Try opening again": one, so one more close while starting brings the recovery screen straight back. */
    @Suppress("UNUSED_PARAMETER")
    internal fun afterTryAgain(r: Record, current: Int) = Record(current, 1)

    /**
     * At a start: the record as saved, with what Android said about the process that had opened the
     * screen ([exit]; null when it said nothing). A crash, a native crash or a freeze before the screen
     * had stayed up is one more close while starting; any other end (Android closing it for memory, the
     * TV turning off, an update) isn't, and isn't asked about again.
     */
    internal fun afterExit(r: Record, current: Int, exit: Exit?): Record = when {
        r.version != current -> Record(current)
        r.openedAt <= 0L -> r
        exit == Exit.CRASH || exit == Exit.NATIVE_CRASH || exit == Exit.FROZE -> Record(current, r.count + 1)
        else -> r.copy(openedAt = 0, pid = 0)
    }

    private const val FORMAT = 1

    /** [r] as the file keeps it; null when there's nothing to keep (no file). */
    internal fun format(r: Record): String? =
        if (r.count <= 0 && r.openedAt <= 0) null else "$FORMAT ${r.version} ${r.count} ${r.openedAt} ${r.pid}"

    /** What [format] wrote; an empty record for no file or anything else. */
    internal fun parse(text: String?): Record {
        val p = text?.trim()?.split(Regex("\\s+")) ?: return Record()
        if (p.size < 5 || p[0] != "$FORMAT") return Record()
        return runCatching {
            Record(p[1].toInt(), p[2].toInt().coerceAtLeast(0), p[3].toLong().coerceAtLeast(0), p[4].toInt())
        }.getOrDefault(Record())
    }

    /** Android's reason code for how a process ended, as an [Exit]. */
    internal fun exitOf(reason: Int): Exit = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> Exit.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> Exit.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> Exit.FROZE
        else -> Exit.OTHER
    }

    // ---- This run ---------------------------------------------------------------------------------------

    /** This build's version; tests stand in another. */
    @Volatile internal var current: Int = BuildConfig.VERSION_CODE

    private val askAndroid: (Context, Int, Long) -> Exit? = { c, pid, at -> if (Build.VERSION.SDK_INT >= 30) androidExit(c, pid, at) else null }

    /** How process [pid], which opened the screen at the given time, ended (Android 11+). Tests put in their own. */
    @Volatile internal var lastExit: (Context, Int, Long) -> Exit? = askAndroid

    private val lock = Any()
    /** The record, read once per run (guarded by [lock]). */
    private var record: Record? = null
    /** Counted once per run: two threads failing together are one close (guarded by [lock]). */
    private var counted = false
    /** When this run's screen opened (elapsedRealtime), for the crash handler; 0 once it has stayed up. */
    @Volatile private var openedAtElapsed = 0L
    @Volatile private var app: Context? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Writes in the background, in order, each one what's in memory by then. */
    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "early-crashes").apply { isDaemon = true } }
    }
    private val settle = Runnable { settleNow() }

    private fun stateFile(c: Context) = File(c.filesDir, "early_crashes.txt")
    private fun reportFile(c: Context) = File(c.filesDir, "early_crash_report.txt")

    /**
     * This version has closed while starting [RECOVER_AT] times in a row: the main screen opens as the
     * recovery screen. Reads a small file the first time in a run (the main screen must know before
     * its first frame), and on Android 11+, only when the last screen to open never stayed up, asks
     * Android once how that process ended.
     */
    fun needsRecovery(c: Context): Boolean = count(c) >= RECOVER_AT

    /**
     * Early closes in a row of this version (0: none, or it has started fine since). Never fails: what
     * repairs Woodruff mustn't be what stops it opening.
     */
    fun count(c: Context): Int = runCatching { countFor(state(c), current) }.getOrDefault(0)

    /**
     * Woodruff's main screen is opening (MainActivity.onCreate, before anything that could fail): a close
     * in the next [EARLY_MS] is a close while starting. Main thread.
     */
    fun opened(c: Context) {
        val app = c.applicationContext
        this.app = app
        openedAtElapsed = SystemClock.elapsedRealtime()
        runCatching {
            // Kept on disk only where Android can say afterwards how a process ended: for a close no handler sees.
            if (Build.VERSION.SDK_INT >= 30) update(app) { afterOpen(it, current, System.currentTimeMillis(), Process.myPid()) }
            // On the main thread: a screen frozen from the start never counts as having stayed up.
            main.removeCallbacks(settle)
            main.postDelayed(settle, EARLY_MS)
        }
    }

    /**
     * From [CrashLog]'s handler, as Woodruff is about to close: within [EARLY_MS] of the screen opening,
     * one more close while starting, written down at once (the process ends right after), with its
     * [report] for the recovery screen. Any thread.
     */
    internal fun crashed(c: Context, report: String?) {
        val opened = openedAtElapsed
        if (opened == 0L || SystemClock.elapsedRealtime() - opened >= EARLY_MS) return
        val app = c.applicationContext
        synchronized(lock) {
            if (counted) return
            counted = true
            val r = afterCrash(state(app), current)
            record = r
            runCatching { write(app, r) }
            if (report != null) runCatching { reportFile(app).writeText(report) }
        }
    }

    /**
     * "Try opening again" on the recovery screen: the count goes to one, so Woodruff opens as usual, and
     * one more close while starting brings the recovery screen straight back.
     */
    fun tryAgain(c: Context) {
        runCatching { update(c.applicationContext) { afterTryAgain(it, current) } }
    }

    /**
     * The crash report of the last close while starting, when it's this version's and one was saved (a
     * native crash or a freeze leaves none). Not on the main thread.
     */
    fun report(c: Context): String? = runCatching { reportFile(c.applicationContext).takeIf { it.isFile }?.readText() }
        .getOrNull()?.takeIf { it.startsWith("Version ${BuildConfig.VERSION_NAME} ") }

    private fun state(c: Context): Record = synchronized(lock) {
        record ?: load(c.applicationContext).also { record = it }
    }

    private fun load(app: Context): Record {
        val saved = parse(runCatching { stateFile(app).takeIf { it.isFile }?.readText() }.getOrNull())
        val exit = if (saved.version == current && saved.openedAt > 0) runCatching { lastExit(app, saved.pid, saved.openedAt) }.getOrNull() else null
        val now = afterExit(saved, current, exit)
        // (Another version's record: its report goes too, as the count of none is written.)
        if (format(now) != format(saved)) persistLater(app)
        return now
    }

    /** The screen stayed up for [EARLY_MS]: Woodruff starts fine, so the count goes back to none. */
    private fun settleNow() {
        openedAtElapsed = 0
        val app = app ?: return
        update(app) { afterSettle(it, current) }
    }

    private fun update(app: Context, change: (Record) -> Record) {
        val changed = synchronized(lock) {
            val before = state(app)
            val after = change(before)
            record = after
            format(after) != format(before)
        }
        if (changed) persistLater(app)
    }

    /** Writes the record as it is by the time the writer gets to it. */
    private fun persistLater(app: Context) {
        runCatching { writer.execute { runCatching { synchronized(lock) { record?.let { write(app, it) } } } } }
    }

    /**
     * Writes [r] (with [lock] held); a record with nothing to keep is no file. With none in a row, the
     * last close's report goes too (under the same lock, so it never takes a report just written).
     */
    private fun write(app: Context, r: Record) {
        val f = stateFile(app)
        val text = format(r)
        if (r.count <= 0) reportFile(app).let { if (it.exists()) it.delete() }
        if (text == null) {
            if (f.exists()) f.delete()
            return
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    @RequiresApi(30)
    private fun androidExit(c: Context, pid: Int, openedAt: Long): Exit? {
        val am = c.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(c.packageName, pid, 1).firstOrNull() ?: return null
        // A process with the same number that ended before this screen opened isn't the one (the clock may
        // have been set meanwhile, hence the margin).
        if (info.pid != pid || info.timestamp < openedAt - CLOCK_SLACK_MS) return null
        return exitOf(info.reason)
    }

    private const val CLOCK_SLACK_MS = 60_000L

    /** Back to a fresh run, keeping what's on disk (tests: the next question reads it as a new run would). */
    internal fun resetForTest() {
        flushForTest()
        synchronized(lock) { record = null; counted = false }
        openedAtElapsed = 0
        main.removeCallbacks(settle)
        current = BuildConfig.VERSION_CODE
        lastExit = askAndroid
    }

    /** Waits for the writes queued so far (tests). */
    internal fun flushForTest() {
        runCatching { writer.submit {}.get(5, TimeUnit.SECONDS) }
    }
}
