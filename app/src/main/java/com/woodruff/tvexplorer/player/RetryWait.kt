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
 * One try per [arm]: a try that fails stalls again, and arming again starts the wait afresh, from
 * the network as it is then. A comeback while a try is already under way isn't waited for (Media3
 * itself retries that try within a few seconds). [tryAgain] runs on [scope]'s thread.
 */
internal class RetryWait(
    private val scope: CoroutineScope,
    private val comebacks: StateFlow<Int>,
    private val delayMs: Long,
    private val tryAgain: () -> Unit,
) {
    private var job: Job? = null

    /** A try is coming (armed, not yet tried or stopped). */
    val waiting: Boolean get() = job?.isActive == true

    /** Waits for the next try, in place of any wait under way. */
    fun arm() {
        job?.cancel()
        val mark = comebacks.value
        job = scope.launch {
            withTimeoutOrNull(delayMs) { comebacks.first { it != mark } }
            tryAgain()
        }
    }

    /** No try after all: the film plays again, it can't be helped (a sign-in that ended), or the player stopped. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
