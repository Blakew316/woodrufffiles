package com.woodruff.tvexplorer.nest

import android.content.Context
import java.util.Date
import java.util.Locale

/**
 * How long each camera start took, step by step, for the smoothness report (Settings › TVs on the
 * phone): finding this TV's network addresses, Google answering, the connection to the camera, and
 * its first picture. Kept in memory for this run of the app: the last [KEPT] starts, and how many
 * starts, stalls and reconnects there were. So a slow camera on one TV (a VPN holding up the
 * addresses, a doorbell slow to wake) shows where its time goes.
 */
internal object CameraTimings {
    const val KEPT = 8

    /**
     * One camera start: [camera]'s name, when it began ([at], this TV's clock), then each step's ms
     * (null: not reached). [outcome]: why there was no picture; null when the picture came.
     */
    data class Start(
        val camera: String,
        val at: Long,
        val addressesMs: Long? = null,
        val addresses: AddressesEnd? = null,
        val googleMs: Long? = null,
        val linkMs: Long? = null,
        val pictureMs: Long? = null,
        val outcome: String? = null,
    )

    private val starts = ArrayDeque<Start>()
    private var count = 0
    private var stalls = 0
    private var reconnects = 0

    /** A start finished, with its picture or without (its [Start.outcome]). */
    @Synchronized fun add(start: Start) {
        starts.addFirst(start)
        while (starts.size > KEPT) starts.removeLast()
        count++
    }

    /** A picture froze (no frame for a few seconds). */
    @Synchronized fun stalled() { stalls++ }

    /** A connection was dropped or failed, and is being made again. */
    @Synchronized fun reconnected() { reconnects++ }

    /** The smoothness report's section, times in the TV's own 12- or 24-hour format; empty when no camera started in this run. */
    fun report(c: Context): String = runCatching {
        val clock = android.text.format.DateFormat.getTimeFormat(c)
        section { clock.format(Date(it)) }
    }.getOrDefault("")

    /** The report's section, with times of day written by [time]; empty when no camera started in this run. */
    fun section(time: (Long) -> String): String {
        val (list, totals) = synchronized(this) { starts.toList() to Triple(count, stalls, reconnects) }
        return timingsSection(list, totals.first, totals.second, totals.third, time)
    }

    /** For tests: forgets everything. */
    @Synchronized internal fun clear() {
        starts.clear()
        count = 0
        stalls = 0
        reconnects = 0
    }
}

/**
 * The smoothness report's camera section from [starts] (newest first) and this run's totals, each
 * start as a short line and its steps under it, as the report's other lists are ("7:42 PM · Front
 * Door", then " addresses 118 (public) · Google 912 · link 240 · picture 410"): short lines read on a
 * phone. Empty when nothing started. [time] writes a time of day.
 */
internal fun timingsSection(starts: List<CameraTimings.Start>, count: Int, stalls: Int, reconnects: Int, time: (Long) -> String): String {
    if (starts.isEmpty() && count == 0) return ""
    return buildString {
        append('\n')
        append("== Camera starts ==\n")
        append("$count ${if (count == 1) "start" else "starts"} · $stalls ${if (stalls == 1) "stall" else "stalls"} · ")
        append("$reconnects ${if (reconnects == 1) "reconnect" else "reconnects"} since Woodruff started.\n")
        append("Newest first, each step in ms: addresses (this TV's, for\n")
        append("the offer) · Google (its answer) · link (connected to the\n")
        append("camera) · picture (its first frame).\n")
        for (s in starts) {
            val steps = buildList {
                s.addressesMs?.let { add("addresses ${ms(it)}" + (s.addresses?.let { e -> " (${e.words})" } ?: "")) }
                s.googleMs?.let { add("Google ${ms(it)}") }
                s.linkMs?.let { add("link ${ms(it)}") }
                s.pictureMs?.let { add("picture ${ms(it)}") }
                s.outcome?.let { add(it) }
            }
            append("${time(s.at)} · ${s.camera.take(40)}\n")
            append(" ${steps.joinToString(" · ").ifEmpty { "no steps" }}\n")
        }
    }
}

private fun ms(v: Long): String = String.format(Locale.US, "%,d", v)
