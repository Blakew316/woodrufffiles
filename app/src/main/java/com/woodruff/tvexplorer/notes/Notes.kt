package com.woodruff.tvexplorer.notes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Note(val id: String, val title: String, val body: String, val updated: Long, val pinned: Boolean = false)

/**
 * Notes shared by the TV and the phone, newest first. Stored as JSON in app storage (written to a
 * temp file and renamed, so a power cut mid-save can't truncate it). Not encrypted: notes aren't
 * secrets, and this keeps them readable if the user backs up the app's files.
 */
object Notes {
    private const val MAX_LEN = 20_000
    private val _items = MutableStateFlow<List<Note>>(emptyList())
    val items: StateFlow<List<Note>> = _items
    @Volatile private var loaded = false

    private fun file(c: Context) = File(c.applicationContext.filesDir, "notes.json")

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _items.value = runCatching {
            val arr = JSONArray(file(c).readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Note(o.getString("id"), o.optString("title"), o.optString("body"), o.optLong("updated"), o.optBoolean("pinned"))
            }
        }.getOrDefault(emptyList()).let(::sorted)
    }

    /** Creates a note when [id] is null or unknown; returns the saved note. Blank notes are deleted. */
    @Synchronized
    fun save(c: Context, id: String?, title: String, body: String, pinned: Boolean? = null): Note? {
        load(c)
        val existing = _items.value.firstOrNull { it.id == id }
        if (title.isBlank() && body.isBlank()) {
            if (existing != null) delete(c, existing.id)
            return null
        }
        val note = Note(
            existing?.id ?: UUID.randomUUID().toString(),
            title.trim().take(200), body.take(MAX_LEN), System.currentTimeMillis(),
            pinned ?: existing?.pinned ?: false,
        )
        write(c, sorted(_items.value.filter { it.id != note.id } + note))
        return note
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        load(c)
        write(c, _items.value.filter { it.id != id })
    }

    private fun sorted(list: List<Note>) = list.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })

    private fun write(c: Context, list: List<Note>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("updated", it.updated).put("pinned", it.pinned))
        }
        val f = file(c)
        val tmp = File(f.parentFile, "notes.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        _items.value = list
    }

    fun toJson(n: Note): JSONObject =
        JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("updated", n.updated).put("pinned", n.pinned)
}
