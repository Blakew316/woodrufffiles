package com.woodruff.tvexplorer.phones

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Messages waiting for each phone's web app (finding 43), kept on the TV's disk, so a restart (each automatic update, a
 * crash, a low-memory kill) loses none, and read without being taken: a reply that never reached the phone (iOS
 * suspending the app mid-poll) is simply sent again. Every message gets the next number of this TV's inbox ([Book]);
 * the phone asks for those after the last one it has. Each phone keeps at most its last [KEEP] messages of the last
 * [KEEP_MS].
 *
 * Writes go to one background thread, in order, so a message sent from the TV's own Announce dialog never puts disk
 * work on the main thread; reads come from the server's threads.
 */
internal object PhoneInbox {
    /** The most messages kept for a phone, and for how long. */
    const val KEEP = 20
    const val KEEP_MS = 7 * 24 * 3_600_000L
    /** A message's words are cut to this many characters on the TV (a family announcement is a line or two). */
    const val MAX_TEXT = 2_000

    private const val FILE = "phone-inbox.json"
    private val lock = Any()
    private var book: Book? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-inbox").apply { isDaemon = true } }

    /** Queues [message] (title, body, from, at; maybe id and link) for each of [phones], and saves it, in the background. */
    fun add(c: Context, phones: Collection<String>, message: JSONObject) {
        if (phones.isEmpty()) return
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        writer.execute { synchronized(lock) { val b = book(app); phones.forEach { b.add(it, message, now) }; save(app, b) } }
    }

    /**
     * What [phone] hasn't had yet ([Book.since]): "messages" (each with its "seq"), "seq" (the newest number for it, to
     * ask after next time) and "epoch" (this inbox's name).
     */
    fun since(c: Context, phone: String, after: Long?, epoch: String?): JSONObject {
        val app = c.applicationContext
        synchronized(lock) {
            val b = book(app)
            val a = b.since(phone, after, epoch, System.currentTimeMillis())
            if (a.changed) writer.execute { synchronized(lock) { save(app, b) } }
            return JSONObject().put("messages", JSONArray(a.messages)).put("seq", a.seq).put("epoch", b.epoch)
        }
    }

    /** A phone that was forgotten: nothing waits for it any more. */
    fun remove(c: Context, phone: String) {
        val app = c.applicationContext
        writer.execute { synchronized(lock) { val b = book(app); if (b.remove(phone)) save(app, b) } }
    }

    /** Waits until everything queued has been written (tests). */
    internal fun settle() { writer.submit {}.get(5, TimeUnit.SECONDS) }

    /** Reads the inbox from disk again next time, as a restarted TV does (tests). */
    internal fun forgetLoaded() { settle(); synchronized(lock) { book = null } }

    /** The inbox, read from disk the first time (under [lock]). */
    private fun book(c: Context): Book = book ?: run {
        val f = AtomicFile(File(c.filesDir, FILE))
        val text = runCatching { String(f.readFully(), Charsets.UTF_8) }.getOrNull()
        (text?.let { runCatching { Book.fromJson(JSONObject(it)) }.getOrNull() } ?: Book(UUID.randomUUID().toString().take(8)))
            .also { book = it }
    }

    /** Writes [b] whole, so a power cut mid-write leaves the last good copy (under [lock], on [writer]). */
    private fun save(c: Context, b: Book) {
        val f = AtomicFile(File(c.filesDir, FILE))
        val out = runCatching { f.startWrite() }.getOrElse { Log.w("PhoneInbox", "Couldn't save the phones' messages: $it"); return }
        try {
            out.write(b.toJson().toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            Log.w("PhoneInbox", "Couldn't save the phones' messages: $e")
        }
    }

    /**
     * The phones' messages, numbered in turn across this TV ([next]), so a phone forgotten and named again never gets a
     * number it has seen. [epoch] names this inbox: a phone holding a number from another (this TV reinstalled, or its
     * data cleared) starts again from the first message. Pure: unit tested.
     */
    class Book(val epoch: String, private var next: Long = 0, private val boxes: MutableMap<String, Box> = LinkedHashMap()) {
        /** One phone's messages, oldest first, and the newest number given to a phone that asks without saying ([acked]). */
        class Box(var acked: Long = 0, val messages: MutableList<JSONObject> = mutableListOf())

        /** What [since] found: the [messages], the newest number for that phone ([seq]), and whether the book changed. */
        class Answer(val messages: List<JSONObject>, val seq: Long, val changed: Boolean)

        /** Queues a copy of [message] for [phone] with the next number; returns that number. */
        fun add(phone: String, message: JSONObject, now: Long): Long {
            val box = boxes.getOrPut(phone) { Box() }
            val m = JSONObject(message.toString()).put("seq", ++next)
            listOf("title", "body").forEach { k -> m.optString(k).takeIf { it.length > MAX_TEXT }?.let { m.put(k, it.take(MAX_TEXT - 1) + "…") } }
            box.messages += m
            prune(box, now)
            return next
        }

        /**
         * [phone]'s messages numbered after its last one: [after] when [epoch] is this book's; from the first when it
         * holds another book's; and where it was before when it says neither (the phone app from before numbers, which
         * took what it was given, or this version asking for the first time), so nothing it already had comes again.
         */
        fun since(phone: String, after: Long?, epoch: String?, now: Long): Answer {
            val box = boxes[phone] ?: return Answer(emptyList(), 0, false)
            val pruned = prune(box, now)
            val newest = box.messages.lastOrNull()?.optLong("seq") ?: box.acked
            val from = when {
                after == null || epoch.isNullOrEmpty() -> box.acked
                epoch != this.epoch -> 0L
                else -> after
            }
            val out = box.messages.filter { it.optLong("seq") > from }
            // What was given counts as had, for a phone that asks without saying (one with a number says it each time).
            val had = when {
                after == null -> newest
                epoch == this.epoch -> minOf(after, newest)
                else -> box.acked
            }
            val acked = maxOf(box.acked, had)
            val changed = pruned || acked != box.acked
            box.acked = acked
            return Answer(out, maxOf(newest, box.acked), changed)
        }

        /** Forgets [phone]'s messages; false when it had none. */
        fun remove(phone: String): Boolean = boxes.remove(phone) != null

        /** Drops what's older than [KEEP_MS] and all but the newest [KEEP]; true when something went. */
        private fun prune(box: Box, now: Long): Boolean {
            val before = box.messages.size
            box.messages.removeAll { now - it.optLong("at") > KEEP_MS }
            while (box.messages.size > KEEP) box.messages.removeAt(0)
            return box.messages.size != before
        }

        fun toJson(): JSONObject = JSONObject().put("epoch", epoch).put("next", next).put("phones", JSONObject().apply {
            boxes.forEach { (id, b) -> put(id, JSONObject().put("acked", b.acked).put("messages", JSONArray(b.messages))) }
        })

        companion object {
            fun fromJson(o: JSONObject): Book {
                val boxes = LinkedHashMap<String, Box>()
                val phones = o.optJSONObject("phones") ?: JSONObject()
                phones.keys().forEach { id ->
                    val b = phones.optJSONObject(id) ?: return@forEach
                    val list = b.optJSONArray("messages") ?: JSONArray()
                    boxes[id] = Box(b.optLong("acked"), (0 until list.length()).mapNotNull { list.optJSONObject(it) }.toMutableList())
                }
                return Book(o.optString("epoch").ifEmpty { UUID.randomUUID().toString().take(8) }, o.optLong("next"), boxes)
            }
        }
    }
}
