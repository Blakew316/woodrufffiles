package com.woodruff.tvexplorer.server

import android.content.Context
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.isGoogleUrl
import com.woodruff.tvexplorer.data.FileKind
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Drive-hosted art for the phone's Watch tab: an owner's own poster images, and the frames Drive
 * makes of videos (a loose film's backdrop, an episode's still). Those need this TV's Google token,
 * which never leaves the TV: the phone asks here (`/api/poster?id=…&kind=poster|backdrop|still`, its
 * cookie is the pairing key) and the TV fetches the picture, at the size the phone shows it, from
 * Google's own hosts only. Kept on disk (about 50 MB, the least recently shown going first), so each
 * picture is fetched once; TMDB's pictures don't come through here (the phone loads them itself).
 */
internal object PhonePosters {
    private const val KEEP_BYTES = 50L * 1024 * 1024
    private const val TRIM_TO_BYTES = 40L * 1024 * 1024
    private const val MAX_PICTURE = 6 * 1024 * 1024
    @Volatile private var writes = 0

    fun serve(c: Context, s: IHTTPSession): Response {
        val id = s.parameters["id"]?.firstOrNull().orEmpty()
        val kind = s.parameters["kind"]?.firstOrNull() ?: "poster"
        val folder = s.parameters["in"]?.firstOrNull()
        val (file, url) = source(c, id, kind, folder) ?: return notFound()
        val cached = File(dir(c), name(kind, file))
        if (cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis()) // shown again: kept longer
            return picture(cached)
        }
        val token = GoogleAuth.blockingToken(c) ?: return notFound()
        return try {
            fetch(url, token, cached)
            if (++writes % 20 == 1) trim(dir(c))
            picture(cached)
        } catch (e: IOException) {
            notFound()
        }
    }

    /**
     * The library entry or episode file [id] stands for and the Drive picture to fetch, sized for
     * [kind]: a poster ~342 wide (posters on a 3× screen), a backdrop 1280, a still 300. Only files
     * this TV has listed (an entry of Movies or TV Shows, or an episode in the listing of [folder], one
     * the Watch tab shows: [watchFolder]), only Google-hosted pictures: never an address someone made up.
     */
    private fun source(c: Context, id: String, kind: String, folder: String?): Pair<DriveFile, String>? {
        if (id.isBlank()) return null
        val (m, sh) = WatchLibrary.listings(c)
        val entries = m?.files.orEmpty() + sh?.files.orEmpty()
        val entry = entries.firstOrNull { it.id == id }
        val episode by lazy {
            folder?.takeIf { watchFolder(c, it, entries, sh?.files.orEmpty()) }?.let { FolderCache.peek(c, it) }?.firstOrNull { it.id == id }
        }
        val url = when (kind) {
            "poster" -> entry?.let { it.posterUrl ?: it.thumbnailLink?.takeIf { _ -> it.kind == FileKind.VIDEO } }?.let { sized(it, "w342") }
            "backdrop" -> entry?.takeIf { it.kind == FileKind.VIDEO }?.thumbnailLink?.let { sized(it, "w1280") }
            "still" -> episode?.thumbnailLink?.let { sized(it, "w300") }
            else -> null
        } ?: return null
        if (!isGoogleUrl(url)) return null
        val file = entry ?: episode ?: return null
        return file to url
    }

    /**
     * Whether [folder] is one whose videos the phone's Watch tab shows: a library entry ([entries], Movies' and TV
     * Shows': a show, or a collection of films), or a season folder in the listing ([listing]) of one of [shows].
     * Stills come from such folders only, so no other folder of the Drive is looked into for the phone, and no frame
     * of its videos is fetched with this TV's token. Visible for tests.
     */
    fun watchFolder(folder: String, entries: List<DriveFile>, shows: List<DriveFile>, listing: (String) -> List<DriveFile>?): Boolean =
        entries.any { it.isFolder && it.id == folder } ||
            shows.any { show -> show.isFolder && listing(show.id)?.any { it.isFolder && it.id == folder } == true }

    /** [watchFolder] with the listings this TV holds: the shows it holds in memory first (the one just opened on the phone). */
    private fun watchFolder(c: Context, folder: String, entries: List<DriveFile>, shows: List<DriveFile>): Boolean {
        val (held, saved) = shows.partition { FolderCache.inMemory(it.id) != null }
        return watchFolder(folder, entries, held + saved) { FolderCache.peek(c, it) }
    }

    /**
     * A Drive thumbnail address asked for at [size] ("w342"): its own size ("=s220", or "=s720" as
     * DriveApi.big asks for TV posters) replaced. Visible for tests.
     */
    fun sized(url: String, size: String): String = url.replace(SIZE, "") + "=" + size

    /** Kept under the file, the kind, and when the file last changed: a new poster gets a new picture. Visible for tests. */
    fun name(kind: String, f: DriveFile): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$kind|${f.id}|${f.modified}".toByteArray())
        return d.joinToString("") { "%02x".format(it) } + ".img"
    }

    private fun fetch(url: String, token: String, to: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = false // a redirect could take the token elsewhere
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val type = conn.contentType.orEmpty()
            if (!type.startsWith("image/")) throw IOException("Not a picture")
            val tmp = File(to.parentFile, to.name + ".part" + Thread.currentThread().id)
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PICTURE) throw IOException("Too big")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(to)) { tmp.delete(); throw IOException("Couldn’t keep it") }
        } finally {
            conn.disconnect()
        }
    }

    private fun picture(f: File): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mimeOf(f), FileInputStream(f), f.length())
            .apply { addHeader("Cache-Control", "private, max-age=604800") }

    /** What the bytes are (Drive serves JPEG, sometimes PNG or WebP). */
    private fun mimeOf(f: File): String = runCatching {
        val head = ByteArray(12)
        val n = FileInputStream(f).use { it.read(head) }
        when {
            n >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
            n >= 12 && head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() -> "image/webp"
            else -> "image/jpeg"
        }
    }.getOrDefault("image/jpeg")

    private fun notFound(): Response = NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No picture")

    private fun dir(c: Context) = File(c.applicationContext.cacheDir, "phone-posters").apply { mkdirs() }

    /** Past [KEEP_BYTES], the pictures shown least recently go until it's under [TRIM_TO_BYTES]. */
    private fun trim(d: File) = runCatching {
        val files = d.listFiles { f -> f.name.endsWith(".img") }?.sortedBy { it.lastModified() } ?: return@runCatching
        var total = files.sumOf { it.length() }
        if (total <= KEEP_BYTES) return@runCatching
        for (f in files) {
            if (total <= TRIM_TO_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private val SIZE = Regex("=[swh]\\d+[^/=]*$")
}
