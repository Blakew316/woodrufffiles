package com.woodruff.tvexplorer.nest

import com.woodruff.tvexplorer.util.isNetworkFailure

// A camera stream's rules, kept apart from WebRTC and the network so they can be unit-tested: when
// a picture counts as live, when a stream is stuck and is made again, how long it waits between
// tries, and how long it waits for the TV's network addresses before asking Google for a stream.
// Times are SystemClock.elapsedRealtime() milliseconds (a TV's wall clock can jump after standby).

/** No frame for this long: the picture holds and its views say "Reconnecting…". */
internal const val STALL_AFTER_MS = 3_000L

/** No frame for this long after frames came: the connection is dropped and made again. */
internal const val DROP_AFTER_MS = 15_000L

/**
 * No first frame this long after Google answered: the connection is dropped and made again. Longer
 * than [DROP_AFTER_MS]: a battery doorbell wakes up before it sends its first picture.
 */
internal const val FIRST_FRAME_LIMIT_MS = 20_000L

/** Frames for this long without a stall: the stream is well again, and its next problem waits only 5 s. */
internal const val HEALTHY_AFTER_MS = 60_000L

/** Each failure in a row adds this to the wait before the next try... */
internal const val RETRY_STEP_MS = 5_000L

/** ...up to this, so a camera that's off doesn't ask Google for a stream every few seconds. */
internal const val RETRY_MAX_MS = 120_000L

/** How long a stream's views say "Reconnecting…" before "Can’t reach this camera right now". */
internal const val CAMERA_TROUBLE_MS = 30_000L

/**
 * One camera stream's health, judged from its frames: what its views show, when a connection is
 * dropped, and how long the next try waits. A connection that's up counts for nothing until its
 * frames come: it's [CameraStream.Status.Live] only while they do. A stall or a dropped connection
 * counts as a failure, and the wait before the next try only shortens again once frames have flowed
 * for [HEALTHY_AFTER_MS], so a camera that connects but never sends a picture (a weak doorbell
 * uplink) can't make the TV ask Google for a new stream every few seconds and trip the rate limit
 * every camera shares. Not thread-safe: the stream's own coroutine alone uses it.
 */
internal class StreamHealth {
    /** Failures in a row since frames last flowed for [HEALTHY_AFTER_MS]. */
    var failures = 0
        private set

    /** When the current trouble began (the frozen picture's last frame, or the first failure); 0: none. */
    private var troubleSince = 0L

    /** Since when frames have come without a stall; 0: they aren't coming. */
    private var flowingSince = 0L

    /** What the views show while a connection is being made: the first time, or again after trouble. */
    fun connecting(): CameraStream.Status =
        if (troubleSince == 0L) CameraStream.Status.Connecting else CameraStream.Status.Reconnecting(troubleSince)

    /**
     * The connection Google answered at [answeredAt], its first frame at [firstFrame] and its latest at
     * [lastFrame] (0: none yet), at [now]: what its views show, or null when it should be dropped (no
     * picture for too long). Then call [failed].
     */
    fun judge(now: Long, answeredAt: Long, firstFrame: Long, lastFrame: Long): CameraStream.Status? {
        if (firstFrame == 0L || lastFrame == 0L) return if (now - answeredAt >= FIRST_FRAME_LIMIT_MS) null else connecting()
        val gap = now - lastFrame
        return when {
            gap < STALL_AFTER_MS -> {
                troubleSince = 0L
                if (flowingSince == 0L) flowingSince = now
                if (now - flowingSince >= HEALTHY_AFTER_MS) failures = 0
                CameraStream.Status.Live
            }
            gap < DROP_AFTER_MS -> {
                flowingSince = 0L
                if (troubleSince == 0L) troubleSince = lastFrame
                CameraStream.Status.Stalled(troubleSince)
            }
            else -> null
        }
    }

    /** The connection failed, or was dropped, at [now]: what the views show until the next try. */
    fun failed(now: Long): CameraStream.Status {
        failures++
        flowingSince = 0L
        if (troubleSince == 0L) troubleSince = now
        return CameraStream.Status.Reconnecting(troubleSince)
    }

    /** The wait before the next try: 5 s more for each failure in a row, at most 2 minutes. */
    fun pauseMs(): Long = minOf(RETRY_MAX_MS, RETRY_STEP_MS * failures.coerceAtLeast(1))
}

/** When the trouble a stream's views show began ([CameraStream.Status.Stalled], [CameraStream.Status.Reconnecting]); 0 for none. */
internal fun troubleSince(status: CameraStream.Status): Long = when (status) {
    is CameraStream.Status.Stalled -> status.since
    is CameraStream.Status.Reconnecting -> status.since
    else -> 0L
}

/**
 * Whether renewing Google's stream failing with [e] is worth telling Google to stop the old session
 * before a new one is asked for. Not after Google said "too many requests" (another command would add
 * to it), nor when the network failed (it can't reach Google either, and would only hold up the next
 * try); and a dropped connection or a stall never stops the session (it ends by itself in a few minutes).
 */
internal fun stopsOldSession(e: Throwable): Boolean = e !is RateLimited && !isNetworkFailure(e)

// ---- The TV's network addresses -----------------------------------------------------------------

/** How long to wait for a public address after the first local one is found. */
internal const val LOCAL_GRACE_MS = 500L

/** Never wait longer than this for the TV's network addresses. */
internal const val ADDRESS_LIMIT_MS = 3_000L

/** Why the wait for the TV's network addresses ended (the smoothness report says). */
internal enum class AddressesEnd(val words: String) {
    /** A public address (seen from outside the home network), the one Google needs, for every part of the offer. */
    PUBLIC("public"),
    /** Every network was asked and answered. */
    ALL("all"),
    /** Only local addresses after [LOCAL_GRACE_MS]: sent as they are. */
    LOCAL("local only"),
    /** [ADDRESS_LIMIT_MS] passed. */
    LIMIT("3 s limit"),
}

/**
 * Waiting for this TV's network addresses (WebRTC's ICE candidates) before the offer goes to Google,
 * which takes none found later. Waiting for every network to answer could take the full 3 s where
 * one never does (broken IPv6, some VPN set-ups): so it ends once there's a public address for every
 * part of the offer, or [LOCAL_GRACE_MS] after the first local one, or once every network has
 * answered, and never later than [ADDRESS_LIMIT_MS] after it [begin]s. (Until Google's answer joins
 * them into one, the sound, the picture and the data channel each ask for addresses of their own, a
 * few milliseconds apart: the first public address alone would leave the others' out.) No network is
 * left out (a VPN on the TV may be the only way out). Its callbacks come on WebRTC's signaling
 * thread, its checks on the stream's own: synchronized.
 */
internal class AddressWait {
    private var startedAt = -1L
    private var firstLocalAt = 0L
    /** The parts of the offer (by their index) that have a local address, and those with a public one. */
    private val local = HashSet<Int>()
    private val public = HashSet<Int>()

    /** Why it ended; null while it goes on. */
    var end: AddressesEnd? = null
        @Synchronized get
        private set

    /** Asking for the addresses starts now ([now]). */
    @Synchronized fun begin(now: Long) {
        if (startedAt < 0) startedAt = now
    }

    /**
     * An address of [type] ("host", "srflx"…, from [candidateType]) was found at [now], for the part
     * of the offer at index [part] (WebRTC's sdpMLineIndex).
     */
    @Synchronized fun found(type: String?, now: Long, part: Int = 0) {
        if (end != null) return
        when (type) {
            "srflx" -> public += part
            "host" -> {
                local += part
                if (firstLocalAt == 0L) firstLocalAt = now
            }
        }
        if (public.isNotEmpty() && public.containsAll(local)) end = AddressesEnd.PUBLIC
    }

    /** Every network has answered. */
    @Synchronized fun complete() {
        if (end == null) end = AddressesEnd.ALL
    }

    /** How much longer to wait at [now] (0 or less: it's over, and [end] says why). */
    @Synchronized fun left(now: Long): Long {
        if (end != null) return 0L
        val start = if (startedAt < 0) now else startedAt
        val limit = start + ADDRESS_LIMIT_MS
        val local = if (firstLocalAt > 0) firstLocalAt + LOCAL_GRACE_MS else Long.MAX_VALUE
        val left = minOf(limit, local) - now
        if (left <= 0) end = if (local <= limit) AddressesEnd.LOCAL else AddressesEnd.LIMIT
        return left
    }
}

private val CANDIDATE_TYPE = Regex(" typ ([a-z]+)")

/** The kind of a network address WebRTC found ("host", "srflx", "prflx", "relay"), from its SDP line; null if it doesn't say. */
internal fun candidateType(sdp: String?): String? = sdp?.let { CANDIDATE_TYPE.find(it)?.groupValues?.get(1) }
