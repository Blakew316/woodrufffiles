package com.woodruff.tvexplorer.mail

import androidx.core.text.HtmlCompat
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Read-only IMAP access: list the inbox, read a message, find new mail for notifications. While the
 * Mail screen is on show ([keepSignedIn]), listing and reading share one connection per account that
 * stays signed in, so a message opens without signing in first (1–2 s on iCloud).
 */
object MailClient {
    /** Where [MailBody.html] links the pictures sent inside a message (a name that never resolves). */
    const val PICTURE_HOST = "cid.invalid"

    // Every picture gets a link of its own, so a page is never shown another message's picture.
    private val pictureKeys = java.util.concurrent.atomic.AtomicLong()

    private val kept = KeptConnections(
        connect = ::signIn,
        close = Conn::close,
        // A message deleted elsewhere isn't a dropped connection: no point signing in again for it.
        mayHaveDropped = { it !is Gone },
        // Long enough to read a message and open the next; short enough to close before a router
        // or the server drops it without a word (then the next use would wait out the timeout).
        idleMs = 3 * 60_000L,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    suspend fun inbox(a: MailAccount, limit: Int = 60): List<MailSummary> = withKeptInbox(a, fresh = true) { f ->
        val total = f.messageCount
        if (total == 0) return@withKeptInbox emptyList()
        val msgs = f.getMessages((total - limit + 1).coerceAtLeast(1), total)
        f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
        msgs.reversed().map { summary(a, f, it) }
    }

    suspend fun read(a: MailAccount, uid: Long): MailBody {
        // Opening another message calls this one off: its pictures stop loading, so that one isn't kept waiting.
        val wanted = currentCoroutineContext()[Job]
        return withKeptInbox(a) { f ->
            val m = f.getMessageByUID(uid) ?: throw Gone()
            val pictures = HashMap<String, Pair<String, ByteArray>>()
            MailBody(
                from = who(m.from),
                to = who(m.getRecipients(Message.RecipientType.TO)),
                subject = m.subject ?: "(no subject)",
                date = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
                text = textOf(m).trim().ifEmpty { "(This message has no readable text.)" },
                // A huge HTML version (rare) is more than the web view can take: those show the text version.
                html = htmlOf(m)?.takeIf { it.length <= 2_000_000 }?.let { inlinePictures(m, it, pictures) { wanted?.isActive != false } },
                pictures = pictures,
            )
        }
    }

    /**
     * Messages with UID above [afterUid]; also returns the inbox's current highest UID. It signs in on
     * its own: the background check mustn't wait for (or hold up) the Mail screen's connection.
     */
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

    /**
     * [on] while the Mail screen is on show: each account stays signed in between uses, until unused
     * for a few minutes. Off (another screen, the TV asleep) closes the connections.
     */
    fun keepSignedIn(on: Boolean) = kept.keep(on)

    /** Signs in to [a] ahead of a message being opened. A problem is left for that to report. */
    suspend fun warmUp(a: MailAccount) {
        try {
            withKeptInbox(a) { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Opening a message signs in again, and says what's wrong.
        }
    }

    /** Closes the connections kept for accounts not in [accounts]: removed, or added again. */
    fun keepOnly(accounts: List<MailAccount>) = kept.keepOnly(accounts)

    /**
     * Runs [block] on [a]'s kept connection. [fresh]: the inbox is opened again first. A kept connection
     * hears of new mail, but not always of mail read or deleted elsewhere since.
     */
    private suspend fun <T> withKeptInbox(a: MailAccount, fresh: Boolean = false, block: (IMAPFolder) -> T): T = withContext(Dispatchers.IO) {
        kept.use(a) { conn, reused ->
            if (fresh && reused) conn.reopen()
            block(conn.inbox)
        }
    }

    /** Runs [block] on a connection of its own, closed after. */
    private suspend fun <T> withInbox(a: MailAccount, block: (IMAPFolder) -> T): T = withContext(Dispatchers.IO) {
        val conn = signIn(a)
        try { block(conn.inbox) } finally { conn.close() }
    }

    /** Signs in to [a] and opens its inbox to read. */
    private fun signIn(a: MailAccount): Conn {
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
            return Conn(store, folder)
        } catch (e: Throwable) {
            runCatching { store.close() }
            throw e
        }
    }

    /** A signed-in connection with the inbox open to read. */
    private class Conn(val store: Store, var inbox: IMAPFolder) {
        /** Opens the inbox again, as it is now: on the same connection, without signing in again. */
        fun reopen() {
            inbox.close(false)
            inbox = (store.getFolder("INBOX") as IMAPFolder).apply { open(Folder.READ_ONLY) }
        }

        fun close() {
            runCatching { inbox.close(false) }
            runCatching { store.close() }
        }
    }

    /** The message isn't in the inbox any more. */
    private class Gone : Exception("That message was deleted")

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
     * view copies several times over on the main thread: the app ran out of memory.) Once [wanted] says
     * no, the rest aren't loaded.
     */
    private fun inlinePictures(m: Part, html: String, pictures: MutableMap<String, Pair<String, ByteArray>>, wanted: () -> Boolean): String {
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
                if (!wanted()) return
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

/**
 * Each account's kept connection: [MailClient]'s bookkeeping, apart from JavaMail so it can be tested.
 * Uses of one account take turns, as a JavaMail connection serves one caller at a time. While [keep]
 * is on, a connection stays open between uses until unused for [idleMs]; otherwise it closes after
 * each use. [close] runs on [scope], so a slow goodbye to the server never holds up the next use.
 */
internal class KeptConnections<C : Any>(
    private val connect: (MailAccount) -> C,
    private val close: (C) -> Unit,
    private val mayHaveDropped: (Exception) -> Boolean,
    private val idleMs: Long,
    private val scope: CoroutineScope,
) {
    private class Slot<C : Any> {
        val lock = Mutex()
        // The rest only while holding lock.
        var account: MailAccount? = null
        var conn: C? = null
        var closing: Job? = null
    }

    private val slots = ConcurrentHashMap<String, Slot<C>>()
    @Volatile private var keeping = false

    /**
     * Runs [block] on [a]'s kept connection ([reused]), or a new one. When the kept one fails in a way
     * that may mean it dropped since its last use (the TV slept, the Wi-Fi changed, the server ended
     * it), it's closed and [block] runs once more on a new one. A caller that gives up while waiting
     * its turn never runs.
     */
    suspend fun <T> use(a: MailAccount, block: (conn: C, reused: Boolean) -> T): T {
        val slot = slots.getOrPut(a.id) { Slot() }
        return slot.lock.withLock {
            slot.closing?.cancel()
            try {
                attempt(slot, a, block)
            } finally {
                closeLater(slot)
            }
        }
    }

    /** [on] while connections should stay open between uses. Off closes each, once its use is done. */
    fun keep(on: Boolean) {
        keeping = on
        if (!on) closeWhere { true }
    }

    /** Closes the connections of accounts not in [accounts]. */
    fun keepOnly(accounts: Collection<MailAccount>) = closeWhere { it !in accounts }

    private suspend fun <T> attempt(slot: Slot<C>, a: MailAccount, block: (C, Boolean) -> T): T {
        // Changed since it signed in (a new password, say): sign in afresh.
        if (slot.account != a) drop(slot)
        val old = slot.conn
        if (old != null) {
            try {
                return block(old, true)
            } catch (e: Exception) {
                if (!mayHaveDropped(e)) throw e
                drop(slot)
                currentCoroutineContext().ensureActive()
            }
        }
        val conn = connect(a)
        slot.account = a
        slot.conn = conn
        return block(conn, false)
    }

    /** After a use: [slot]'s connection closes once unused for [idleMs], or straight away when not keeping. */
    private fun closeLater(slot: Slot<C>) {
        if (slot.conn == null) return
        slot.closing = scope.launch {
            if (keeping) delay(idleMs)
            slot.lock.withLock { drop(slot) }
        }
    }

    private fun closeWhere(test: (MailAccount) -> Boolean) {
        for (slot in slots.values) scope.launch {
            slot.lock.withLock {
                val account = slot.account
                if (account != null && test(account)) {
                    slot.closing?.cancel()
                    drop(slot)
                }
            }
        }
    }

    /** Lets go of [slot]'s connection (holding its lock) and closes it on [scope]. */
    private fun drop(slot: Slot<C>) {
        val conn = slot.conn ?: return
        slot.conn = null
        slot.account = null
        scope.launch { close(conn) }
    }
}
