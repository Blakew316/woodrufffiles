package com.woodruff.tvexplorer.gallery

import android.content.Context
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.notify.Notices

/**
 * Empties the notes and notices before a gallery class seeds its own. Both are kept for the whole test
 * run (one process, each loaded once), so every class that seeded them added another copy: the Notes
 * shot showed the same "Groceries" note four times, and Home's At a glance picked up notices another
 * class had posted (a sign-in notice in Recently added's shot).
 */
internal fun freshSamples(c: Context) {
    Notes.load(c)
    Notes.items.value.forEach { Notes.delete(c, it.id) }
    Notices.load(c)
    Notices.clear(c)
    Notices.dismissLatest()
}
