package com.woodruff.tvexplorer.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters

/**
 * Where the player's seeks land.
 *
 * Media3 seeks exactly by default: for a spot between two keyframes it downloads from the keyframe
 * before the spot and decodes every frame up to it without showing them, which on a Drive film is
 * extra megabytes and decoder time on every scrub, Skip intro and ⏪ / ⏩ (seconds of it on 4K web
 * films, whose keyframes can be several seconds apart). The viewer's seeks go instead to the first
 * clean frame (a keyframe) at most [SNAP_MS] after the spot: Drive sends from that frame and the
 * picture is there as soon as it arrives. With no keyframe that close, the seek is exact.
 *
 * Never before the spot: a step back snapped to an earlier keyframe could land outside the seconds
 * the player keeps behind the film (backBufferMs), and the rewind that was instant would download
 * them again. And exact where exactness matters: Watch together lines TVs up on positions.
 */
@OptIn(UnstableApi::class)
internal object SeekAim {
    /** How far after the spot a seek may land to start on a clean frame. */
    const val SNAP_MS = 1_500L

    /** To the first keyframe up to [SNAP_MS] after the spot, else exactly on it; never before it. */
    val SNAP: SeekParameters = SeekParameters(0, SNAP_MS * 1_000)

    /**
     * How a seek to [targetMs] should land, with the film loaded up to [bufferedMs]. [exact]: Watch
     * together, where every TV must agree on the position (its leader reports the spot it asked for,
     * and a following TV lines up on it).
     *
     * A spot just short of the end of what's loaded is played exactly too: the keyframe after it may
     * not have arrived yet, and snapping to that would throw away what's loaded and ask Drive again,
     * where an exact seek plays on at once from memory.
     */
    fun parametersFor(targetMs: Long, bufferedMs: Long, exact: Boolean): SeekParameters = when {
        exact -> SeekParameters.EXACT
        targetMs <= bufferedMs && targetMs + SNAP_MS > bufferedMs -> SeekParameters.EXACT
        else -> SNAP
    }

    /**
     * The jump a seek makes, for the player's "Seek …: picture after N ms" log ("back 10 s, kept"),
     * so a rewind that should be instant can be checked on a TV. [kept]: the spot was inside what the
     * player holds in memory, behind the film or ahead of it.
     */
    fun describe(fromMs: Long, toMs: Long, kept: Boolean): String {
        val s = (toMs - fromMs) / 1_000
        val way = if (s < 0) "back ${-s} s" else "ahead $s s"
        return if (kept) "$way, kept" else way
    }

    /**
     * Whether a spot is inside what the player holds: from [backBufferMs] behind the film (at
     * [liveMs]) up to what's loaded ahead ([bufferedMs]). Only for the log: the player decides.
     */
    fun isKept(targetMs: Long, liveMs: Long, bufferedMs: Long, backBufferMs: Int): Boolean =
        targetMs >= liveMs - backBufferMs && targetMs <= bufferedMs
}
