package com.woodruff.tvexplorer.util

import java.util.Locale
import kotlin.math.ceil

/** A frame over 25 ms missed at least one refresh at 60 Hz (a visible hitch); over 50 ms is a stutter. */
internal const val SLOW_MS = 25L
internal const val STUTTER_MS = 50L

/** Frames drawn within a second of a remote key count as navigating; the rest are idle (video, animations). */
internal const val NAVIGATING_MS = 1_000L

/** Histograms count whole milliseconds up to this; anything longer shares the last bucket. */
internal const val HISTOGRAM_MS = 300

/** A screen needs this many navigating frames before the Settings row judges it. */
internal const val MIN_FRAMES = 60

/**
 * Where a frame's time goes, as Android's FrameMetrics splits it, in the order the report lists them.
 * The first eight add up to the frame's total time; the GPU's time (Android 12 and up) overlaps them.
 */
object Phase {
    /** UNKNOWN_DELAY: the frame was due but the main thread was still busy (with a remote key, say). */
    const val WAITING = 0
    /** INPUT_HANDLING: touch-style input batched into the frame (remote keys arrive outside frames). */
    const val INPUT = 1
    /** ANIMATION: animations, and Compose composing what changed. */
    const val ANIMATION = 2
    /** LAYOUT_MEASURE: measuring and placing. */
    const val LAYOUT = 3
    /** DRAW: recording what to draw. */
    const val DRAW = 4
    /** SYNC: new pictures uploaded to the graphics chip. */
    const val SYNC = 5
    /** COMMAND_ISSUE + SWAP_BUFFERS: the render thread handing the frame to the graphics chip. */
    const val GRAPHICS = 6
    /** What's left of the total: the frame queued behind the render thread's previous work. */
    const val RENDER_QUEUE = 7
    /** GPU_DURATION (Android 12 and up): the graphics chip's own time; not part of the sum above. */
    const val GPU = 8
    const val COUNT = 9

    /** The report's short names (explained once at its top). */
    val SHORT = arrayOf("wait", "input", "anim", "layout", "draw", "sync", "gfx", "RT", "GPU")
    /** What the Settings row says slow frames were "mostly" spent on. */
    val WORDS = arrayOf(
        "waiting to start", "input", "animation and composition", "layout", "drawing",
        "loading pictures", "graphics", "waiting for the render thread", "the GPU",
    )
}

/** How long something took, many times over: a count, a 1-ms histogram (for percentiles) and the longest. */
class Durations {
    var count = 0
        private set
    var maxNs = 0L
        private set
    /** By whole milliseconds; the last bucket holds [HISTOGRAM_MS] ms and longer. */
    val histogram = IntArray(HISTOGRAM_MS + 1)

    fun add(ns: Long) {
        count++
        histogram[msBucket(ns)]++
        if (ns > maxNs) maxNs = ns
    }

    fun percentileMs(p: Double): Int = percentile(histogram, count, p)

    /** How many took longer than [ms] whole milliseconds. */
    fun over(ms: Int): Int {
        var n = 0
        for (i in ms + 1 until histogram.size) n += histogram[i]
        return n
    }

    /** Makes [into] the same as this one. */
    fun copyInto(into: Durations) {
        into.count = count
        into.maxNs = maxNs
        histogram.copyInto(into.histogram)
    }

    fun copy(): Durations = Durations().also { copyInto(it) }
}

/**
 * One kind of frame (navigating or idle) on one screen: how many, how many were late (past their
 * deadline), slow or stutters, how long they took, and where the late and the slow ones spent their
 * time. Plain counters and arrays, so counting a frame allocates nothing.
 */
class FrameTally {
    val totals = Durations()
    val frames: Int get() = totals.count
    var late = 0
        private set
    var slow = 0
        private set
    var stutters = 0
        private set
    /** Each [Phase] summed over the late frames, and over the slow ones (ns). */
    val lateNs = LongArray(Phase.COUNT)
    val slowNs = LongArray(Phase.COUNT)
    /** Late frames that reported the GPU's time (Android 12 and up): its average is over these. */
    var lateWithGpu = 0
        private set

    /** A frame of [totalNs] due within [deadlineNs], its [phases] by [Phase] (GPU negative when unknown). */
    fun add(totalNs: Long, deadlineNs: Long, phases: LongArray) {
        totals.add(totalNs)
        if (totalNs > deadlineNs) {
            late++
            for (i in 0 until Phase.GPU) lateNs[i] += phases[i]
            if (phases[Phase.GPU] >= 0) {
                lateNs[Phase.GPU] += phases[Phase.GPU]
                lateWithGpu++
            }
        }
        // Whole milliseconds, as the Settings row has always counted them.
        val ms = totalNs / 1_000_000
        if (ms > SLOW_MS) {
            slow++
            for (i in 0 until Phase.GPU) slowNs[i] += phases[i]
        }
        if (ms > STUTTER_MS) stutters++
    }

    /** The phase the slow frames spent most on (never the GPU, which overlaps the rest); -1 without any. */
    fun mostlySlow(): Int {
        if (slow == 0) return -1
        var best = 0
        for (i in 1 until Phase.GPU) if (slowNs[i] > slowNs[best]) best = i
        return best
    }

    /** The average milliseconds of [phase] in a late frame (the GPU's: in those that reported it); NaN without any. */
    fun lateAverageMs(phase: Int): Double {
        val n = if (phase == Phase.GPU) lateWithGpu else late
        return if (n == 0) Double.NaN else lateNs[phase] / 1e6 / n
    }

    /** Makes [into] the same as this one. */
    fun copyInto(into: FrameTally) {
        totals.copyInto(into.totals)
        into.late = late
        into.slow = slow
        into.stutters = stutters
        into.lateWithGpu = lateWithGpu
        lateNs.copyInto(into.lateNs)
        slowNs.copyInto(into.slowNs)
    }
}

/** One screen's frames, split by whether a remote key had just been pressed. */
class ScreenFrames(val name: String) {
    val navigating = FrameTally()
    val idle = FrameTally()

    fun copy(): ScreenFrames = ScreenFrames(name).also { s ->
        navigating.copyInto(s.navigating)
        idle.copyInto(s.idle)
    }
}

/**
 * The frames the report shows: each screen's, the remote keys' handling times, and how long they were
 * counted for; [fromRecording] when they're the last finished recording's.
 */
class CountedFrames(val screens: List<ScreenFrames>, val keys: Durations, val countedMs: Long, val fromRecording: Boolean)

internal fun msBucket(ns: Long): Int = when {
    ns <= 0 -> 0
    ns >= HISTOGRAM_MS * 1_000_000L -> HISTOGRAM_MS
    else -> (ns / 1_000_000).toInt()
}

/**
 * The [p] percentile (0 to 1) of [count] values counted in a 1-ms [histogram]: the fewest whole
 * milliseconds that at least that share of them took no more than. 0 when there are none.
 */
internal fun percentile(histogram: IntArray, count: Int, p: Double): Int {
    if (count <= 0) return 0
    val rank = ceil(p * count).toLong().coerceIn(1L, count.toLong())
    var seen = 0L
    for (ms in histogram.indices) {
        seen += histogram[ms]
        if (seen >= rank) return ms
    }
    return histogram.size - 1
}

/**
 * Whether a frame whose work began at [startMs] was drawn while navigating: a remote key in [keys]
 * (uptime ms, 0 for an empty slot) came at most [NAVIGATING_MS] before it. A key that came after the
 * frame began can't be what it drew.
 */
internal fun isNavigating(startMs: Long, keys: LongArray): Boolean {
    for (k in keys) {
        if (k <= 0) continue
        val after = startMs - k
        if (after >= 0 && after <= NAVIGATING_MS) return true
    }
    return false
}

/**
 * The Settings row: "While navigating: Movies 12% slow (9 stutters, mostly loading pictures) · Home 4% slow",
 * worst first. Only frames drawn within a second of a remote key count, so video playing by itself
 * neither waters it down nor adds to it; screens with too few such frames to judge are left out.
 */
internal fun navigatingSummary(screens: List<ScreenFrames>): String {
    val shown = screens.filter { it.navigating.frames >= MIN_FRAMES }
        .sortedByDescending { it.navigating.slow.toDouble() / it.navigating.frames }
    if (shown.isEmpty()) return "Not enough yet: use the app a little, then look again"
    return "While navigating: " + shown.joinToString(" · ") { s ->
        val t = s.navigating
        val detail = listOfNotNull(
            when (t.stutters) { 0 -> null; 1 -> "1 stutter"; else -> "${t.stutters} stutters" },
            t.mostlySlow().takeIf { it >= 0 }?.let { "mostly ${Phase.WORDS[it]}" },
        )
        "${s.name} ${t.slow * 100 / t.frames}% slow" + if (detail.isEmpty()) "" else " (${detail.joinToString(", ")})"
    }
}

/** "2:05" for [ms], rounded up to the second, so a countdown never shows 0:00 while it's running. */
internal fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "${s / 60}:" + String.format(Locale.US, "%02d", s % 60)
}
