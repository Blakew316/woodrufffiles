package com.woodruff.tvexplorer.player

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where [BoundedDriveSource] tells what it asked of Google Drive and what came. */
internal interface DriveAskSink {
    /** One request made to Google: [bytes] asked for (its range, as far as the file goes). [key]: the film's ("drive:…"), or null (a subtitle file). */
    fun requested(key: String?, bytes: Long)

    /** [bytes] read of Google's answers, as a request ends. */
    fun received(key: String?, bytes: Long)

    /** Google turned a request down over one of its limits ([DriveAsks.isQuotaRefusal]): its answer [http] and its own word for it. */
    fun refused(key: String?, http: Int, reason: String?)
}

/**
 * How much this TV asks of Google Drive, for its diagnostics. One day Google's daily download
 * allowance ran out for the whole family ("downloadQuotaExceeded") and nothing said how much each
 * film had asked for.
 *
 * Counted here: each film's bytes asked of Google (the sum of its requests' ranges, as far as the
 * file goes), the bytes received and the number of requests, and the same for the day (UTC). When
 * a film closes, and every 10 minutes while it keeps asking, one line ("Drive: Dune: asked 3.2 GB in
 * 61 requests, received 3.1 GB"); and one whenever Google refuses over one of its limits, with the
 * day's totals so far. The lines go to the TV's log with its other playback lines (adb logcat -s
 * Player) and into its Smoothness Report, the TV's diagnostics the phones and Macs read (Settings ›
 * TVs › this TV), where the newest [KEPT] stay, with the day's totals, across restarts and updates.
 * Counters and a line now and then: nothing is written per request.
 */
internal object DriveAsks : DriveAskSink {
    const val KEPT = 40
    private const val FILE = "drive-asks.txt"
    /** After a film closes, its last reads are still coming in for a moment (the player lets go of its loads as it goes). */
    private const val CLOSE_GRACE_MS = 3_000L

    private val tally = DriveTally(say = ::say)
    /** The lines said, newest first, each with its time (UTC). Guarded by itself. */
    private val kept = ArrayDeque<String>()
    @Volatile private var file: File? = null
    private val worker by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "drive-asks").apply { isDaemon = true } } }

    /** Where the lines and the day's totals are kept on this TV, read back once. Disk: off the main thread. */
    fun attach(c: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            val f = File(c.applicationContext.filesDir, FILE)
            runCatching { load(f.readLines()) }
            file = f
        }
    }

    override fun requested(key: String?, bytes: Long) = tally.requested(key, bytes)
    override fun received(key: String?, bytes: Long) = tally.received(key, bytes)
    override fun refused(key: String?, http: Int, reason: String?) = tally.refused(key, http, reason)

    /** The player has [key] open, called [title]: its lines are named after it, and only its closing ends them. */
    fun playing(key: String, title: String?) = tally.playing(key, title)

    /** The player let go of [key]: its line, once its last reads are in. */
    fun closed(key: String) {
        tally.leave(key)
        runCatching { worker.schedule({ tally.finish(key) }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS) }
    }

    /** [key] played on a phone through this TV: its lines say so. */
    fun onPhone(key: String) = tally.named(key, "$key on a phone", replace = false)

    /** Google's refusals over one of its limits: a 429, or a 403 whose reason is a quota or limit ("downloadQuotaExceeded", "userRateLimitExceeded"…). */
    fun isQuotaRefusal(http: Int, reason: String?): Boolean =
        http == 429 || (http == 403 && reason != null && (reason.contains("quota", ignoreCase = true) || reason.contains("limit", ignoreCase = true)))

    /** The Smoothness Report's section; empty when nothing was ever asked of Drive here. */
    fun report(c: Context): String = runCatching { attach(c); section() }.getOrDefault("")

    internal fun section(): String {
        val now = tally.summary()
        val lines = synchronized(kept) { kept.toList() }
        if (now.isEmpty() && lines.isEmpty()) return ""
        return buildString {
            append('\n')
            append("== Google Drive ==\n")
            now.forEach { append(it).append('\n') }
            if (lines.isNotEmpty()) {
                append("Newest first (UTC):\n")
                lines.forEach { append(it).append('\n') }
            }
        }
    }

    private fun say(line: String) {
        runCatching { Log.i("Player", line) }
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        synchronized(kept) {
            kept.addFirst("$stamp $line")
            while (kept.size > KEPT) kept.removeLast()
        }
        save()
    }

    /** The day's totals and the kept lines, written in the background (a few KB, as a line is said). */
    private fun save() {
        val f = file ?: return
        val d = tally.today()
        val text = buildString {
            append("day ${d.number} ${d.asked} ${d.received} ${d.requests}\n")
            synchronized(kept) { kept.forEach { append(it).append('\n') } }
        }
        runCatching { worker.execute { runCatching { f.writeText(text) } } }
    }

    private fun load(lines: List<String>) {
        val head = lines.firstOrNull()?.split(' ')
        if (head != null && head.size == 5 && head[0] == "day") {
            val n = head.drop(1).map { it.toLongOrNull() ?: return@map null }
            if (n.none { it == null }) tally.restore(n[0]!!, n[1]!!, n[2]!!, n[3]!!.toInt())
        }
        // Lines said before the file was read back (a film played on a phone first) are newer: they stay first.
        synchronized(kept) {
            val said = kept.toList()
            kept.clear()
            (said + lines.drop(1).filter { it.isNotBlank() }).take(KEPT).forEach { kept.addLast(it) }
        }
    }
}

/**
 * The counting behind [DriveAsks], on [now]'s clock, its lines said through [say]. Visible for tests.
 */
internal class DriveTally(private val now: () -> Long = System::currentTimeMillis, private val say: (String) -> Unit) : DriveAskSink {
    class Film {
        var asked = 0L
        var received = 0L
        var requests = 0
        var lastAsk = 0L
        var lastLine = 0L
    }

    /** A day's totals (UTC; [number]: days since 1970). */
    class Day(val number: Long) {
        var asked = 0L
        var received = 0L
        var requests = 0
    }

    private val films = HashMap<String, Film>()
    private val titles = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }
    private val open = HashSet<String>()
    private val refusedAt = HashMap<String, Long>()
    private var day = Day(dayOf(now()))
    private var swept = 0L

    @Synchronized override fun requested(key: String?, bytes: Long) {
        val t = now()
        today(t).apply { asked += bytes.coerceAtLeast(0); requests++ }
        if (key != null) {
            val f = films.getOrPut(key) { Film().also { it.lastLine = t } }
            f.asked += bytes.coerceAtLeast(0)
            f.requests++
            f.lastAsk = t
            if (t - f.lastLine >= LINE_EVERY_MS) {
                f.lastLine = t
                say(filmLine(key, f, soFar = true))
            }
        }
        if (t - swept >= SWEEP_EVERY_MS) sweep(t)
    }

    @Synchronized override fun received(key: String?, bytes: Long) {
        if (bytes <= 0) return
        today(now()).received += bytes
        key?.let { films[it] }?.let { it.received += bytes }
    }

    @Synchronized override fun refused(key: String?, http: Int, reason: String?) {
        val t = now()
        val k = key ?: ""
        val last = refusedAt[k]
        if (last != null && t - last < REFUSAL_EVERY_MS) return
        refusedAt[k] = t
        val d = today(t)
        val f = key?.let { films[it] }
        say(
            "Drive: ${label(key)}: Google refused it ($http ${reason ?: "with no reason"})" +
                (f?.let { "; asked ${amount(it.asked)} for it in ${requests(it.requests)}" } ?: "") +
                "; asked of Google today (UTC): ${amount(d.asked)} in ${requests(d.requests)}, received ${amount(d.received)}",
        )
    }

    @Synchronized fun playing(key: String, title: String?) {
        if (!title.isNullOrBlank()) titles[key] = title
        open += key
    }

    @Synchronized fun named(key: String, title: String, replace: Boolean) {
        if (replace || key !in titles) titles[key] = title
    }

    /** The player let go of [key]; [finish] says its line. */
    @Synchronized fun leave(key: String) {
        open -= key
    }

    /** [key]'s line, and its counts let go, unless a player has it open again. */
    @Synchronized fun finish(key: String) {
        if (key in open) return
        val f = films.remove(key) ?: return
        if (f.requests > 0) say(filmLine(key, f, soFar = false))
    }

    @Synchronized fun today(): Day = today(now())

    /** The day's totals kept on the TV before this run, added to this run's: the same day's only. */
    @Synchronized fun restore(number: Long, asked: Long, received: Long, requests: Int) {
        if (number != day.number) return
        day.asked += asked
        day.received += received
        day.requests += requests
    }

    /** For the report: the day's totals, then each film asking now. */
    @Synchronized fun summary(): List<String> {
        val d = today(now())
        if (d.requests == 0 && films.isEmpty()) return emptyList()
        return listOf("Asked of Google today (UTC): ${amount(d.asked)} in ${requests(d.requests)}, received ${amount(d.received)}.") +
            films.map { (key, f) -> "Now, ${label(key)}: asked ${amount(f.asked)} in ${requests(f.requests)}, received ${amount(f.received)}." }
    }

    private fun today(t: Long): Day {
        val n = dayOf(t)
        if (n != day.number) day = Day(n)
        return day
    }

    /** Films no player has open that asked nothing for a while (a phone's, a frame for Up next): their line, and they're let go. */
    private fun sweep(t: Long) {
        swept = t
        films.entries.filter { it.key !in open && t - it.value.lastAsk >= IDLE_MS }.map { it.key }.forEach(::finish)
        refusedAt.entries.removeAll { t - it.value >= IDLE_MS }
    }

    private fun filmLine(key: String, f: Film, soFar: Boolean) =
        "Drive: ${label(key)}: asked ${amount(f.asked)} in ${requests(f.requests)}${if (soFar) " so far" else ""}, received ${amount(f.received)}"

    private fun label(key: String?): String = key?.let { titles[it] ?: it } ?: "a subtitle file"

    private fun requests(n: Int) = if (n == 1) "1 request" else "$n requests"

    companion object {
        const val LINE_EVERY_MS = 10 * 60_000L
        const val IDLE_MS = 10 * 60_000L
        const val SWEEP_EVERY_MS = 60_000L
        const val REFUSAL_EVERY_MS = 60_000L

        fun dayOf(t: Long): Long = Math.floorDiv(t, 86_400_000L)

        /** "0 B", "12 KB", "8.0 MB", "3.2 GB": the TV's binary units, written the same in every language. */
        fun amount(b: Long): String {
            if (b < 1024) return "$b B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = b / 1024.0
            var i = 0
            while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
            return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
        }
    }
}
