package com.woodruff.tvexplorer.server

import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.data.StorageRoot
import com.woodruff.tvexplorer.util.Format
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LAN file server behind the "Wireless transfer" screen.
 *
 * Security model: the TV shows a URL containing the pairing [token] (also as a QR code). Every request
 * must carry it, either as `?k=` on first visit or the cookie set in response. Paths are resolved
 * canonically and must stay inside one of [roots], so `..` or symlinks can't reach app-private data.
 * Uploads stream straight to the destination with no temp copy, because TV internal storage is
 * often too small to stage a multi-gigabyte movie.
 */
class TransferServer(
    port: Int,
    /** Pairing key; persistent (see [PairingKey]) so a bookmarked phone stays connected. */
    val token: String,
    /** The single-page web UI, loaded from assets/web/index.html. */
    private val page: String,
    private val roots: () -> List<StorageRoot>,
    private val onActivity: (String) -> Unit,
    /** Extra authenticated routes (notifications, settings, mail, Google sign-in); null = not handled. */
    private val extra: (IHTTPSession) -> Response? = { null },
    /** Routes that carry their own proof (Google's OAuth callback checks `state`). */
    private val public: (IHTTPSession) -> Response? = { null },
    /** What a phone's Home Screen icon for the web app is called (this TV's name: [PhoneIcon]), read at each page load. */
    private val homeName: () -> String = { PhoneIcon.APP_NAME },
) : NanoHTTPD(port) {

    /** The page as last filled in, and its ETag, for the Home Screen label it was filled in with. */
    private class Rendered(val label: String, val html: String, val tag: String)
    @Volatile private var rendered: Rendered? = null

    /** The page with its pairing key and Home Screen label; filled in again when the TV is renamed. */
    private fun currentPage(): Rendered {
        val label = homeName()
        rendered?.let { if (it.label == label) return it }
        val html = PhoneIcon.render(page, token, label)
        val tag = "\"" + MessageDigest.getInstance("SHA-256").digest(html.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) } + "\""
        return Rendered(label, html, tag).also { rendered = it }
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: NoRoom) {
        // Said before a byte is written; the rest of the upload is never read, so the connection closes after this.
        newFixedLengthResponse(InsufficientStorage, MIME_PLAINTEXT, e.message).apply { closeConnection(true) }
    } catch (e: SecurityException) {
        text(Response.Status.FORBIDDEN, e.message ?: "Forbidden")
    } catch (e: IOException) {
        text(Response.Status.INTERNAL_ERROR, e.message ?: "I/O error")
    } catch (e: IllegalArgumentException) {
        text(Response.Status.BAD_REQUEST, e.message ?: "Bad request")
    } catch (e: Exception) {
        text(Response.Status.INTERNAL_ERROR, e.message ?: "Something went wrong on the TV")
    }

    private fun route(s: IHTTPSession): Response {
        public(s)?.let { return it }
        val keyParam = s.parameters["k"]?.firstOrNull()
        if (!authorized(s, keyParam)) {
            return text(Response.Status.UNAUTHORIZED, "Scan the QR code on the TV (or type the full address shown there) to connect.")
        }
        return when {
            // The page keeps the key in its address so a Home Screen copy stays paired (see index.html), so "/?k=…" is
            // answered straight away, in one round trip, with the cookie its pictures and requests use (finding 45;
            // a redirect to "/" only cost a round trip, since the page puts the key back). ETag: the phone
            // revalidates each launch and gets a tiny 304 unless the app was updated.
            s.uri == "/" -> currentPage().let { p ->
                val r = if (s.headers["if-none-match"] == p.tag) {
                    newFixedLengthResponse(Response.Status.NOT_MODIFIED, MIME_HTML, "").apply { addHeader("ETag", p.tag) }
                } else {
                    newFixedLengthResponse(Response.Status.OK, MIME_HTML, p.html)
                        .apply { addHeader("Cache-Control", "no-cache"); addHeader("ETag", p.tag) }
                }
                if (keyParam != null && same(keyParam, token)) r.addHeader("Set-Cookie", "tvx=$token; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax")
                r
            }
            // Each storage, and the room a file from the phone has there (finding 44).
            s.uri == "/api/roots" -> json(JSONArray().apply {
                roots().forEach { r ->
                    put(JSONObject().put("name", r.title).put("path", r.dir.absolutePath).put("removable", r.removable)
                        .apply { UploadRoom.of(r.dir, onTv = !r.removable)?.let { put("free", it) } })
                }
            })
            s.uri == "/api/list" -> list(resolve(param(s, "path")))
            s.uri == "/api/download" -> download(resolve(param(s, "path")))
            s.uri == "/api/upload" && s.method == Method.POST -> upload(s)
            s.uri == "/api/mkdir" && s.method == Method.POST -> {
                val dir = FileOperations.uniqueTarget(resolve(param(s, "path")), clean(param(s, "name")))
                if (!dir.mkdirs()) throw IOException("Couldn't create folder")
                onActivity("Created ${dir.name}")
                json(JSONObject().put("ok", true))
            }
            s.uri == "/api/delete" && s.method == Method.POST -> {
                val f = resolve(param(s, "path"))
                if (roots().any { it.dir.canonicalFile == f }) throw SecurityException("Refusing to delete a storage root")
                if (!f.deleteRecursively()) throw IOException("Couldn't delete ${f.name}")
                onActivity("Deleted ${f.name}")
                json(JSONObject().put("ok", true))
            }
            else -> extra(s) ?: text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun list(dir: File): Response {
        val files = dir.listFiles() ?: throw IOException("Can't read folder")
        val arr = JSONArray()
        files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).forEach {
            arr.put(JSONObject()
                .put("name", it.name).put("path", it.absolutePath).put("dir", it.isDirectory)
                .put("size", if (it.isFile) it.length() else 0).put("modified", it.lastModified()))
        }
        val parent = dir.parentFile?.takeIf { p -> inRoots(p.canonicalFile) }
        return json(JSONObject().put("path", dir.absolutePath).put("parent", parent?.absolutePath ?: JSONObject.NULL).put("files", arr)
            // The room an upload has here, for the phone to check a file against before sending it (finding 44).
            .apply { UploadRoom.of(dir, onTv = rootOf(dir)?.removable != true)?.let { put("free", it) } })
    }

    private fun download(f: File): Response {
        if (!f.isFile) throw IllegalArgumentException("Not a file")
        onActivity("Sent ${f.name}")
        return newFixedLengthResponse(Response.Status.OK, FileTypes.mimeOf(f.name), WhileSending(FileInputStream(f)), f.length()).apply {
            addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20"))
        }
    }

    private fun upload(s: IHTTPSession): Response {
        val dir = resolve(param(s, "path"))
        if (!dir.isDirectory) throw IllegalArgumentException("Destination is not a folder")
        val name = clean(param(s, "name"))
        // One that won't fit is turned down before a byte is written (the phone checks first; this is the backstop).
        val root = rootOf(dir)
        val where = if (root?.removable == true) root.title else homeName().takeIf { it != PhoneIcon.APP_NAME } ?: "the TV"
        UploadRoom.check(name, s.headers["content-length"]?.toLongOrNull(), UploadRoom.of(dir, onTv = root?.removable != true), where)
        val target = FileOperations.uniqueTarget(dir, name)
        receiveFile(s, target)
        onActivity("Received ${target.name}")
        return json(JSONObject().put("ok", true).put("name", target.name))
    }

    private fun authorized(s: IHTTPSession, keyParam: String?): Boolean {
        if (keyParam != null && same(keyParam, token)) return true
        val cookie = s.headers["cookie"] ?: return false
        return cookie.split(';').map { it.trim() }.any { it.startsWith("tvx=") && same(it.removePrefix("tvx="), token) }
    }

    private fun resolve(path: String): File {
        val f = File(path).canonicalFile
        if (!inRoots(f)) throw SecurityException("Path outside shared storage")
        return f
    }

    private fun inRoots(f: File): Boolean = rootOf(f) != null

    /** The storage [f] (a canonical path) is on; null when it's on none of them. */
    private fun rootOf(f: File): StorageRoot? = roots().firstOrNull { r ->
        val root = r.dir.canonicalFile
        f == root || f.path.startsWith(root.path + File.separator)
    }

    private fun param(s: IHTTPSession, name: String): String =
        s.parameters[name]?.firstOrNull() ?: throw IllegalArgumentException("Missing $name")

    private fun clean(name: String): String {
        val n = name.substringAfterLast('/').substringAfterLast('\\').trim()
        FileOperations.validateName(n)
        return n
    }

    private fun json(body: Any) = newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, MIME_PLAINTEXT, msg)

    companion object {
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        /** 12 chars from a 31-letter alphabet ≈ 59 bits: not guessable on a LAN. */
        fun randomToken(): String {
            val rnd = SecureRandom()
            return (1..12).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        }

        private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}

/**
 * The room a file from the phone has where it goes (finding 44): what's free there, less [KEEP] on the TV's own storage
 * (Android, its updates and saved posters need some to run), so an upload never fills the TV; on a USB drive or SD
 * card, all that's free. The phone checks a file against it before sending; [check] is the TV's backstop.
 */
internal object UploadRoom {
    const val KEEP = 200L * 1024 * 1024

    /** The room in [dir] (or the nearest folder above it that exists); null when its storage doesn't say. */
    fun of(dir: File, onTv: Boolean): Long? {
        val d = generateSequence(dir.absoluteFile) { it.parentFile }.firstOrNull { it.exists() } ?: return null
        if (d.totalSpace <= 0) return null
        return (d.usableSpace - if (onTv) KEEP else 0L).coerceAtLeast(0)
    }

    /** "This video is 2.1 GB; Living Room has 800 MB free.", or "Living Room is full." with no room at all. */
    fun words(name: String, size: Long, room: Long, where: String): String {
        val kind = when (FileTypes.kindOf(name)) { FileKind.VIDEO -> "video"; FileKind.IMAGE -> "photo"; else -> "file" }
        // (a number never parts from its unit at a line end)
        fun bytes(b: Long) = Format.bytes(b).replace(' ', '\u00A0')
        return if (room <= 0) "${where.replaceFirstChar { it.uppercase() }} is full."
        else "This $kind is ${bytes(size)}; $where has ${bytes(room)} free."
    }

    /** [NoRoom], in [words], when an upload of [length] bytes named [name] won't fit in [room] ([where] has it). */
    fun check(name: String, length: Long?, room: Long?, where: String) {
        if (length != null && room != null && length > room) throw NoRoom(words(name, length, room, where))
    }
}

/** An upload that won't fit where it goes: the phone hears 507 and the plain words ([UploadRoom.words]). */
internal class NoRoom(message: String) : IOException(message)

/** HTTP 507, which NanoHTTPD has no name for. */
private object InsufficientStorage : Response.IStatus {
    override fun getDescription() = "507 Insufficient Storage"
    override fun getRequestStatus() = 507
}

/**
 * Saves a request's body as [target]: the raw file bytes (sent by fetch/XHR, not multipart, so they stream), written
 * aside and renamed into place, so the file is never seen half-written and a failed upload leaves nothing behind.
 */
internal fun receiveFile(s: IHTTPSession, target: File) = com.woodruff.tvexplorer.util.Presence.busy {
    // A file coming from the phone: an update waits for it (cut short, it would leave a part-file behind).
    val length = s.headers["content-length"]?.toLongOrNull() ?: throw IllegalArgumentException("Missing Content-Length")
    val partial = File(target.parentFile, ".${target.name}.part")
    try {
        FileOutputStream(partial).use { out ->
            val buf = ByteArray(256 * 1024)
            var remaining = length
            val input = s.inputStream
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("Upload interrupted")
                out.write(buf, 0, n)
                remaining -= n
            }
            out.fd.sync()
        }
        if (!partial.renameTo(target)) throw IOException("Couldn't save ${target.name}")
    } catch (e: IOException) {
        partial.delete()
        throw e
    }
}

/**
 * A file on its way to the phone: counts as a job an update waits for ([com.woodruff.tvexplorer.util.Presence])
 * from when it's opened until the server closes it, sent or not (NanoHTTPD closes every response).
 */
internal class WhileSending(stream: java.io.InputStream) : java.io.FilterInputStream(stream) {
    private val job = com.woodruff.tvexplorer.util.Presence.begin()

    override fun close() {
        com.woodruff.tvexplorer.util.Presence.end(job)
        super.close()
    }
}
