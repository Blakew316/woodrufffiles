package com.woodruff.tvexplorer.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When a film that stalled is tried again: after [delayMs], or the moment the network comes back
 * ([comebacks] ticking, as util.NetworkWatch's does when the Wi-Fi rejoins and again when it passes
 * Android's internet check), whichever is first. It used to be a fixed 10 s timer, so a film waited
 * up to 10 s more after the Wi-Fi was back.
 *
 * One try per [arm]: a try that fails stalls again, and arming again starts the wait afresh. The
 * network is noted just before each try, so one that came back while the try was failing (Wi-Fi
 * rejoined, then passed Android's check a moment later) still counts: the next try is at once,
 * rather than after the whole wait. [tryAgain] runs on [scope]'s thread.
 */
internal class RetryWait(
    private val scope: CoroutineScope,
    private val comebacks: StateFlow<Int>,
    private val delayMs: Long,
    private val tryAgain: () -> Unit,
) {
    private var job: Job? = null
    /** [comebacks] as it was just before the last try: what a comeback is counted from. */
    private var beforeTry: Int? = null

    /** A try is coming (armed, not yet tried or stopped). */
    val waiting: Boolean get() = job?.isActive == true

    /** Waits for the next try ([wait] at most), in place of any wait under way. */
    fun arm(wait: Long = delayMs) {
        job?.cancel()
        val mark = beforeTry ?: comebacks.value
        job = scope.launch {
            withTimeoutOrNull(wait) { comebacks.first { it != mark } }
            beforeTry = comebacks.value
            tryAgain()
        }
    }

    /** No try after all: the film plays again, it can't be helped (a sign-in that ended), or the player stopped. */
    fun stop() {
        job?.cancel()
        job = null
        beforeTry = null
    }
}
