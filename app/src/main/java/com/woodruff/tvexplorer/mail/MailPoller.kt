package com.woodruff.tvexplorer.mail

import android.content.Context
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices

/**
 * Checks every mail account for new, unread messages and posts a notification for each (up to
 * three, then a summary). The first check for an account only records where the inbox is, so
 * adding an account doesn't flood the TV with old mail.
 */
object MailPoller {
    suspend fun check(c: Context) {
        val prefs = c.getSharedPreferences("mail_poll", Context.MODE_PRIVATE)
        for (a in MailAccounts.list(c)) {
            val last = prefs.getLong(a.id, -1L)
            val result = runCatching { MailClient.newSince(a, last) }.getOrNull() ?: continue
            val (fresh, highest) = result
            prefs.edit().putLong(a.id, highest).apply()
            if (last < 0) continue
            fresh.take(3).forEach { Notices.post(c, NoticeSource.MAIL, it.subject, "From ${it.from} · ${a.email}") }
            if (fresh.size > 3) Notices.post(c, NoticeSource.MAIL, "${fresh.size - 3} more new messages", a.email)
        }
    }
}
