package com.woodruff.tvexplorer.mail

import androidx.core.text.HtmlCompat
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress

data class MailSummary(
    val accountId: String,
    val uid: Long,
    val from: String,
    val subject: String,
    val date: Long,
    val seen: Boolean,
)

data class MailBody(val from: String, val to: String, val subject: String, val date: Long, val text: String)

/** Read-only IMAP access: list the inbox, read a message, find new mail for notifications. */
object MailClient {

    suspend fun inbox(a: MailAccount, limit: Int = 60): List<MailSummary> = withInbox(a) { f ->
        val total = f.messageCount
        if (total == 0) return@withInbox emptyList()
        val msgs = f.getMessages((total - limit + 1).coerceAtLeast(1), total)
        f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
        msgs.reversed().map { summary(a, f, it) }
    }

    suspend fun read(a: MailAccount, uid: Long): MailBody = withInbox(a) { f ->
        val m = f.getMessageByUID(uid) ?: error("That message was deleted")
        MailBody(
            from = who(m.from),
            to = who(m.getRecipients(Message.RecipientType.TO)),
            subject = m.subject ?: "(no subject)",
            date = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
            text = textOf(m).trim().ifEmpty { "(This message has no readable text.)" },
        )
    }

    /** Messages with UID above [afterUid]; also returns the inbox's current highest UID. */
    suspend fun newSince(a: MailAccount, afterUid: Long): Pair<List<MailSummary>, Long> = withInbox(a) { f ->
        val next = f.uidNext
        val highest = if (next > 0) next - 1 else 0L
        if (afterUid <= 0 || highest <= afterUid) return@withInbox emptyList<MailSummary>() to highest
        val msgs = f.getMessagesByUID(afterUid + 1, UIDFolder.LASTUID).filterNotNull().toTypedArray()
        f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
        msgs.map { summary(a, f, it) }.filter { it.uid > afterUid && !it.seen } to highest
    }

    /** Signs in and opens the inbox: used to check a newly added account. */
    suspend fun test(a: MailAccount) { withInbox(a) { it.messageCount } }

    private suspend fun <T> withInbox(a: MailAccount, block: (IMAPFolder) -> T): T = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", a.host)
            put("mail.imaps.port", a.port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val store: Store = Session.getInstance(props).getStore("imaps")
        try {
            try {
                store.connect(a.host, a.port, a.email, a.password)
            } catch (e: javax.mail.AuthenticationFailedException) {
                throw IllegalStateException("${a.provider.label} refused the sign-in. Use an app-specific password, not your normal password.")
            }
            val folder = store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try { block(folder) } finally { runCatching { folder.close(false) } }
        } finally {
            runCatching { store.close() }
        }
    }

    private fun summary(a: MailAccount, f: IMAPFolder, m: Message) = MailSummary(
        accountId = a.id,
        uid = f.getUID(m),
        from = who(m.from),
        subject = m.subject ?: "(no subject)",
        date = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
        seen = m.isSet(Flags.Flag.SEEN),
    )

    private fun who(addresses: Array<javax.mail.Address>?): String =
        addresses?.joinToString(", ") { (it as? InternetAddress)?.let { ia -> ia.personal ?: ia.address } ?: it.toString() } ?: ""

    /** Prefers text/plain; falls back to HTML converted to text. */
    private fun textOf(p: Part): String {
        if (p.isMimeType("text/plain")) return p.content as? String ?: ""
        if (p.isMimeType("text/html")) {
            return HtmlCompat.fromHtml(p.content as? String ?: "", HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        }
        if (p.isMimeType("multipart/alternative")) {
            val mp = p.content as Multipart
            val parts = (0 until mp.count).map { mp.getBodyPart(it) }
            parts.firstOrNull { it.isMimeType("text/plain") }?.let { return textOf(it) }
            parts.firstOrNull { it.isMimeType("text/html") }?.let { return textOf(it) }
        }
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            return (0 until mp.count).map { mp.getBodyPart(it) }
                .filter { Part.ATTACHMENT != it.disposition }
                .joinToString("\n\n") { textOf(it) }
                .trim()
        }
        return ""
    }
}
