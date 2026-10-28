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

/**
 * [html]: the message as mail apps show it, when it has an HTML version. It links the pictures sent
 * inside it at https://[MailClient.PICTURE_HOST]/<key>; [pictures] holds each one's type and bytes by key.
 */
data class MailBody(val from: String, val to: String, val subject: String, val date: Long, val text: String, val html: String? = null, val pictures: Map<String, Pair<String, ByteArray>> = emptyMap())

/** Read-only IMAP access: list the inbox, read a message, find new mail for notifications. */
object MailClient {
    /** Where [MailBody.html] links the pictures sent inside a message (a name that never resolves). */
    const val PICTURE_HOST = "cid.invalid"

    // Every picture gets a link of its own, so a page is never shown another message's picture.
    private val pictureKeys = java.util.concurrent.atomic.AtomicLong()

    suspend fun inbox(a: MailAccount, limit: Int = 60): List<MailSummary> = withInbox(a) { f ->
        val total = f.messageCount
        if (total == 0) return@withInbox emptyList()
        val msgs = f.getMessages((total - limit + 1).coerceAtLeast(1), total)
        f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
        msgs.reversed().map { summary(a, f, it) }
    }

    suspend fun read(a: MailAccount, uid: Long): MailBody = withInbox(a) { f ->
        val m = f.getMessageByUID(uid) ?: error("That message was deleted")
        val pictures = HashMap<String, Pair<String, ByteArray>>()
        MailBody(
            from = who(m.from),
            to = who(m.getRecipients(Message.RecipientType.TO)),
            subject = m.subject ?: "(no subject)",
            date = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
            text = textOf(m).trim().ifEmpty { "(This message has no readable text.)" },
            // A huge HTML version (rare) is more than the web view can take: those show the text version.
            html = htmlOf(m)?.takeIf { it.length <= 2_000_000 }?.let { inlinePictures(m, it, pictures) },
            pictures = pictures,
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
            // Each part in one request: by default it comes 16 KB per round trip, so photos took half a minute.
            put("mail.imaps.partialfetch", "false")
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

    /**
     * The HTML version, if any. Often the plain-text version is only a stub ("this is the text
     * version of this email…"), so the TV shows this one.
     */
    private fun htmlOf(p: Part): String? {
        if (p.isMimeType("text/html")) return p.content as? String
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            // Apple Mail splits the HTML into pieces around an attachment placed in the text: show them all, in order.
            if (p.isMimeType("multipart/mixed")) {
                val pieces = (0 until mp.count).map { mp.getBodyPart(it) }
                    .filter { it.isMimeType("text/html") && !Part.ATTACHMENT.equals(it.disposition, ignoreCase = true) }
                if (pieces.size > 1) return pieces.joinToString("\n") { it.content as? String ?: "" }
            }
            for (i in 0 until mp.count) {
                val part = mp.getBodyPart(i)
                if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) continue
                htmlOf(part)?.let { return it }
            }
        }
        return null
    }

    /**
     * Pictures sent inside the message (linked as "cid:…"), so they show too: up to 8 MB in all go into
     * [pictures], and the page links them at [PICTURE_HOST] for the reader to serve. (Pasted into the
     * page as data URIs, a third bigger and once per link, they made pages of tens of MB, which the web
     * view copies several times over on the main thread: the app ran out of memory.)
     */
    private fun inlinePictures(m: Part, html: String, pictures: MutableMap<String, Pair<String, ByteArray>>): String {
        if (!html.contains("cid:", ignoreCase = true)) return html
        var out = html
        var budget = 8 * 1024 * 1024
        fun walk(p: Part) {
            if (p.isMimeType("multipart/*")) {
                val mp = p.content as Multipart
                for (i in 0 until mp.count) walk(mp.getBodyPart(i))
            } else if (p.isMimeType("image/*")) {
                val id = (p as? javax.mail.internet.MimeBodyPart)?.contentID?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() } ?: return
                if (p.size > budget) return
                val key = pictureKeys.incrementAndGet().toString()
                val linked = linkPicture(out, id, "https://$PICTURE_HOST/$key") ?: return // not in the page
                val bytes = p.inputStream.use { it.readBytes() }
                if (bytes.size > budget) return
                budget -= bytes.size
                pictures[key] = p.contentType.substringBefore(';').trim().lowercase() to bytes
                out = linked
            }
        }
        runCatching { walk(m) }
        return out
    }

    /** [html] with its links to the picture [id] ("cid:<id>") changed to [url]; null when it has none. */
    internal fun linkPicture(html: String, id: String, url: String): String? {
        // Whole ids only: the link to "image1" must not take the start of "cid:image10".
        val link = Regex("cid:" + Regex.escape(id) + "(?=[\"'\\s)>&]|$)", RegexOption.IGNORE_CASE)
        return if (link.containsMatchIn(html)) link.replace(html) { url } else null
    }

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
