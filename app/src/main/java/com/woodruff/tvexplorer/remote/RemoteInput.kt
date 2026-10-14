package com.woodruff.tvexplorer.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Lets the phone type into whatever text box is open on the TV.
 *
 * Every on-screen text field registers itself while it's visible; the most recently opened or
 * focused one is the target. The phone reads [target] (`GET /api/type`) to show what it's typing
 * into and sends the whole text back (`POST /api/type`), which the field applies through [typed].
 * Sending the full text rather than keystrokes keeps the two sides in sync even if a request is lost.
 */
object RemoteInput {
    data class Field(val id: Long, val label: String, val secret: Boolean, val multiline: Boolean, val text: String)
    data class Typed(val id: Long, val text: String, val submit: Boolean)

    private val ids = AtomicLong()
    private val stack = MutableStateFlow<List<Field>>(emptyList())

    /** Typing from the phone; each field applies only events carrying its own id. */
    val typed = MutableSharedFlow<Typed>(extraBufferCapacity = 32)

    val fields: StateFlow<List<Field>> = stack
    val target: Field? get() = stack.value.lastOrNull()

    fun newId(): Long = ids.incrementAndGet()

    fun register(id: Long, label: String, secret: Boolean, multiline: Boolean, text: String) =
        stack.update { s -> s.filter { it.id != id } + Field(id, label, secret, multiline, text) }

    fun unregister(id: Long) = stack.update { s -> s.filter { it.id != id } }

    /** Called when a field gets remote focus, so the phone types into the one the viewer is on. */
    fun bringToFront(id: Long) = stack.update { s ->
        val f = s.firstOrNull { it.id == id } ?: return@update s
        s.filter { it.id != id } + f
    }

    fun updateText(id: Long, text: String) = stack.update { s -> s.map { if (it.id == id) it.copy(text = text) else it } }

    /** Returns false when no text box is open on the TV. */
    fun type(id: Long?, text: String, submit: Boolean): Boolean {
        val f = (if (id != null) stack.value.firstOrNull { it.id == id } else null) ?: target ?: return false
        updateText(f.id, text)
        return typed.tryEmit(Typed(f.id, text, submit))
    }
}
