package com.woodruff.tvexplorer.util

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Printer

/**
 * The detailed part of the smoothness report: for [RECORD_MS] after "OK starts counting again" (or
 * Record on the phone), where the main thread is whenever one piece of work keeps it busy for more
 * than [LONG_MS]. Android's Looper tells a Printer as each main-thread message starts and finishes; a
 * sampler thread looks every [SAMPLE_MS] and, once the current message (or a remote key, which
 * Android hands to the app outside any message) has run longer than [LONG_MS], takes the main
 * thread's stack. Garbage collection is counted over the same time. Outside a recording none of this
 * runs: no Printer, no sampler thread.
 */
object MainThreadWatch {
    const val RECORD_MS = 3 * 60_000L
    const val SAMPLE_MS = 5L
    const val LONG_MS = 12L
    private const val LONG_NS = LONG_MS * 1_000_000

    /** The long remote keys' kind in [longWork]. */
    const val KEY_KIND = "Remote key (MainActivity.dispatchKeyEvent)"

    val hotSpots = HotSpots()
    val longWork = LongWork()

    /** When the message the main thread is running started (System.nanoTime), 0 between messages. */
    @Volatile private var messageSince = 0L
    /** The Looper's ">>>>> Dispatching to …" line for that message (made by Android anyway). */
    @Volatile private var message: String? = null
    /** When the remote key being handled started (System.nanoTime), 0 when none is. */
    @Volatile private var keySince = 0L

    @Volatile private var recording = false
    @Volatile private var generation = 0
    // SystemClock.elapsedRealtime() of the recording's start, end (planned) and finish (0 until then).
    @Volatile private var startedAt = 0L
    @Volatile private var endsAt = 0L
    @Volatile private var finishedAt = 0L
    // Android's garbage-collection counters (ART's), at the start and at the finish.
    private val GC_STATS = arrayOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count", "art.gc.blocking-gc-time")
    private val gcAtStart = LongArray(GC_STATS.size)
    private val gcAtFinish = LongArray(GC_STATS.size)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Called on the main thread with each line Looper logs. Only notes the time and keeps Android's own
     * line (no allocation); a message that ran long is added up by kind when it finishes.
     */
    private val printer = Printer { line ->
        // Called from inside Android's message loop: anything thrown here would crash the app, so a
        // failure ends the recording instead.
        try {
            if (line.isEmpty()) return@Printer
            when (line[0]) {
                '>' -> {
                    message = line
                    messageSince = System.nanoTime()
                }
                '<' -> {
                    val since = messageSince
                    messageSince = 0
                    val started = message
                    if (since != 0L && started != null) {
                        val ns = System.nanoTime() - since
                        if (ns > LONG_NS) longWork.add(messageKind(started), ns)
                    }
                }
            }
        } catch (t: Throwable) {
            stopAfterError(t)
        }
    }

    /** Something in the recording failed: it stops (the app must never go down for a measurement). */
    private fun stopAfterError(t: Throwable) {
        android.util.Log.w("MainThreadWatch", "recording stopped", t)
        runCatching { Looper.getMainLooper().setMessageLogging(null) }
        messageSince = 0
        message = null
        runCatching { finish(generation) }
    }

    /** Starts a recording; one already running starts over. Any thread. */
    fun start() {
        val gen: Int
        synchronized(this) {
            gen = ++generation
            hotSpots.clear()
            longWork.clear()
            readGc(gcAtStart)
            startedAt = SystemClock.elapsedRealtime()
            endsAt = startedAt + RECORD_MS
            finishedAt = 0
            recording = true
        }
        main.post { if (recording) Looper.getMainLooper().setMessageLogging(printer) }
        Thread({ sample(gen) }, "smoothness-sampler").apply { isDaemon = true }.start()
    }

    /** A remote key reached the app at [startedNs] (System.nanoTime). Main thread. */
    fun keyStarted(startedNs: Long) {
        if (keySince == 0L) keySince = startedNs
    }

    /** That key was handled, in [ns]. Main thread. */
    fun keyFinished(startedNs: Long, ns: Long) {
        if (keySince == startedNs) keySince = 0
        if (recording && ns > LONG_NS) longWork.add(KEY_KIND, ns)
    }

    /** Every [SAMPLE_MS], while the recording [gen] lasts: the main thread's stack, if it's been busy long. */
    private fun sample(gen: Int) {
        val thread = Looper.getMainLooper().thread
        while (generation == gen) {
            if (SystemClock.elapsedRealtime() >= endsAt) {
                finish(gen)
                return
            }
            try {
                Thread.sleep(SAMPLE_MS)
            } catch (e: InterruptedException) {
                return
            }
            try {
                val since = busySince()
                if (since == 0L || System.nanoTime() - since < LONG_NS) continue
                val stack = thread.stackTrace
                // Only if that same work is still going: the stack may already be the next message's.
                if (busySince() == since && generation == gen) hotSpots.add(stack)
            } catch (t: Throwable) {
                // An uncaught failure on this thread would crash the app: the recording stops instead.
                stopAfterError(t)
                return
            }
        }
    }

    /** Since when the main thread has been on its current message or key (System.nanoTime); 0 if neither. */
    private fun busySince(): Long {
        val m = messageSince
        val k = keySince
        return if (m == 0L) k else if (k == 0L) m else minOf(m, k)
    }

    private fun finish(gen: Int) {
        synchronized(this) {
            if (generation != gen || !recording) return
            recording = false
            readGc(gcAtFinish)
            finishedAt = SystemClock.elapsedRealtime()
        }
        // Unless a new recording has started meanwhile, which keeps the Printer.
        main.post {
            if (!recording) {
                Looper.getMainLooper().setMessageLogging(null)
                messageSince = 0
                message = null
            }
        }
        FrameStats.recordingFinished()
    }

    /** Where the recording stands, [now] being SystemClock.elapsedRealtime(). */
    fun state(now: Long = SystemClock.elapsedRealtime()): Recording = synchronized(this) {
        when {
            recording -> Recording(Recording.RUNNING, leftMs = (endsAt - now).coerceAtLeast(0), agoMs = 0, lengthMs = now - startedAt)
            finishedAt > 0 -> Recording(Recording.FINISHED, leftMs = 0, agoMs = now - finishedAt, lengthMs = finishedAt - startedAt)
            else -> Recording(Recording.NONE, 0, 0, 0)
        }
    }

    /**
     * Garbage collection during the recording so far (or the finished one): collections, their ms, the
     * blocking ones and their ms; without a recording, since the app started. Null where Android
     * doesn't say. The second value: whether it's the recording's.
     */
    fun gc(): Pair<LongArray?, Boolean> = synchronized(this) {
        val during = startedAt > 0
        val end = if (recording || finishedAt == 0L) LongArray(GC_STATS.size).also { readGc(it) } else gcAtFinish
        val known = end.all { it >= 0 } && (!during || gcAtStart.all { it >= 0 })
        val counts = if (!known) null else LongArray(GC_STATS.size) { i -> if (during) end[i] - gcAtStart[i] else end[i] }
        counts to during
    }

    private fun readGc(into: LongArray) {
        for (i in GC_STATS.indices) into[i] = runCatching { Debug.getRuntimeStat(GC_STATS[i])?.toLongOrNull() }.getOrNull() ?: -1L
    }
}

/** Where a recording stands: [state] is [NONE], [RUNNING] or [FINISHED]; times in ms. */
class Recording(val state: Int, val leftMs: Long, val agoMs: Long, val lengthMs: Long) {
    val running: Boolean get() = state == RUNNING
    val finished: Boolean get() = state == FINISHED

    companion object {
        const val NONE = 0
        const val RUNNING = 1
        const val FINISHED = 2
    }
}
