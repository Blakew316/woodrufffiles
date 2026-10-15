package com.woodruff.tvexplorer.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Watch together, kept in step: the TV that started it (the leader) sends its position and
 * play/pause to the linked TVs on every change and every 10 s; the others follow, seeking when
 * they drift more than 2 s.
 */
object WatchSync {
    data class Update(val key: String, val positionMs: Long, val playing: Boolean, val sentAt: Long)

    private val _updates = MutableSharedFlow<Update>(extraBufferCapacity = 8)
    val updates: SharedFlow<Update> = _updates

    fun receive(u: Update) { _updates.tryEmit(u) }
}
