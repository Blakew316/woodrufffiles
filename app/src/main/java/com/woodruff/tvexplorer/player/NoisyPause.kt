package com.woodruff.tvexplorer.player

/**
 * A film Android paused because a sound output went away ("becoming noisy"): it plays again by itself when what
 * went away is back.
 *
 * Android pauses a player when the output it's playing through leaves, so the sound doesn't come out of something
 * else all of a sudden: headphones unplugged or switched off, a Bluetooth speaker out of reach. But a streamer's only
 * output is HDMI, and that leaves for a moment whenever the TV, an AVR or a soundbar reads the connection again (a
 * soundbar waking late, the TV switching sound output or input and back), and a Bluetooth soundbar can drop and
 * reconnect by itself. The film then sat paused with its controls up until someone pressed Play, and since a paused
 * film doesn't keep the screen on, the screensaver came over it: a film that stopped by itself.
 *
 * So a pause of that kind is looked at each second for [WITHIN_MS]. Once every output that was there before it is
 * there again, the film plays. Headphones that stay off, or a TV that was switched off, leave it paused, as before;
 * so does anyone using the remote or the phone meanwhile (they've taken over).
 */
internal object NoisyPause {
    /** How long after the pause what went away may still come back and start the film again. */
    const val WITHIN_MS = 15_000L
    /** The outputs aren't looked at for this long after the pause: one on its way out can still be listed. */
    const val SETTLE_MS = 1_500L

    /**
     * The pause: when ([at], elapsedRealtime), what [usedAt] read then, the kinds of output there just before it
     * ([outputs], `AudioDeviceInfo.type`s) and the [item] that was playing.
     */
    data class Paused(val at: Long, val usedAt: Long, val outputs: Set<Int>, val item: String?)

    enum class Step { WAIT, PLAY, LEAVE }

    /**
     * What to do about [pause] now: [sinceMs] after it, with the outputs [now], and [takenOver] true once someone
     * has used the remote or the phone, played it themselves, or another item is on.
     */
    fun step(pause: Paused, now: Set<Int>, sinceMs: Long, takenOver: Boolean): Step = when {
        takenOver -> Step.LEAVE
        sinceMs < SETTLE_MS -> Step.WAIT
        // Nothing known of what was there before (the film had only just started): never started by a guess.
        pause.outputs.isEmpty() -> Step.LEAVE
        now.containsAll(pause.outputs) -> Step.PLAY
        sinceMs > WITHIN_MS -> Step.LEAVE
        else -> Step.WAIT
    }
}
