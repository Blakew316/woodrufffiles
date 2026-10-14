package com.woodruff.tvexplorer.server

import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.data.StorageRoot
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
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
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
        if (s.uri == "/" && keyParam != null && same(keyParam, token)) {
            // Swap the key in the URL for a cookie so it doesn't linger in browser history.
            return newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, MIME_HTML, "").apply {
                addHeader("Location", "/")
                addHeader("Set-Cookie", "tvx=$token; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax")
            }
        }
        if (!authorized(s, keyParam)) {
            return text(Response.Status.UNAUTHORIZED, "Scan the QR code on the TV (or type the full address shown there) to connect.")
        }
        return when {
            // The page keeps the key in its address so a Home Screen copy stays paired (see index.html).
            s.uri == "/" -> newFixedLengthResponse(Response.Status.OK, MIME_HTML, page.replace("__TVX_KEY__", token))
                .apply { addHeader("Cache-Control", "no-store") }
            s.uri == "/api/roots" -> json(JSONArray().apply {
                roots().forEach { put(JSONObject().put("name", it.title).put("path", it.dir.absolutePath)) }
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
        return json(JSONObject().put("path", dir.absolutePath).put("parent", parent?.absolutePath ?: JSONObject.NULL).put("files", arr))
    }

    private fun download(f: File): Response {
        if (!f.isFile) throw IllegalArgumentException("Not a file")
        onActivity("Sent ${f.name}")
        return newFixedLengthResponse(Response.Status.OK, FileTypes.mimeOf(f.name), FileInputStream(f), f.length()).apply {
            addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20"))
        }
    }

    /** Body is the raw file bytes (sent by fetch/XHR), not multipart, so it can be streamed. */
    private fun upload(s: IHTTPSession): Response {
        val dir = resolve(param(s, "path"))
        if (!dir.isDirectory) throw IllegalArgumentException("Destination is not a folder")
        val length = s.headers["content-length"]?.toLongOrNull() ?: throw IllegalArgumentException("Missing Content-Length")
        val target = FileOperations.uniqueTarget(dir, clean(param(s, "name")))
        val partial = File(dir, ".${target.name}.part")
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

    private fun inRoots(f: File): Boolean = roots().any { r ->
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
