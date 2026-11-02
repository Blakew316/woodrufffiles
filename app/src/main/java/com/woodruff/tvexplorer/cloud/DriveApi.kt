package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.data.OpProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long,
    val thumbnailLink: String?,
    /** Poster art for Movies / TV Shows tiles (a poster image's thumbnail), when one was found. */
    val posterUrl: String? = null,
    /** Title, year, genres and rating for Movies / TV Shows (see [Library]); null outside the library. */
    val info: MediaInfo? = null,
    /** When it was put in Drive (0 when not known). */
    val created: Long = 0,
    /** An episode's season, number, and TMDB's name, synopsis and length (see [EpisodeMeta]); null for anything else. */
    val episode: EpisodeMeta? = null,
) {
    /**
     * When it came into the library, for "Recently added": when it was put in Drive. Not [modified],
     * which a rename moves to now (renaming the library made "Recently added" whatever was renamed last).
     */
    val added: Long get() = if (created > 0) created else modified
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
    /** Google Docs/Sheets/Slides have no file bytes to stream or download. */
    val isGoogleDoc: Boolean get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder
    val kind: FileKind get() = when {
        isFolder -> FileKind.FOLDER
        mimeType.startsWith("video/") -> FileKind.VIDEO
        mimeType.startsWith("audio/") -> FileKind.AUDIO
        mimeType.startsWith("image/") -> FileKind.IMAGE
        else -> FileTypes.kindOf(name)
    }

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}

/** Where in Drive the user is: a folder, or one of the special top-level views. */
sealed interface DrivePlace {
    val title: String
    data class Folder(val id: String, override val title: String) : DrivePlace
    data object SharedWithMe : DrivePlace { override val title = "Shared with me" }
    data class Videos(override val title: String = "All videos") : DrivePlace
    /** A top-level library folder found by name, e.g. "Movies" (the first of [names] that exists). */
    data class Named(val names: List<String>, override val title: String) : DrivePlace

    companion object {
        val MyDrive = Folder("root", "My Drive")
        val Movies = Named(listOf("Movies", "Movie", "Films"), "Movies")
        val TvShows = Named(listOf("TV Shows", "TV shows", "TV", "Shows", "Series"), "TV Shows")
    }
}

/**
 * Google Drive answered with an error. [code] is its HTTP status: 401 means it didn't take the
 * access token (see [GoogleAuth.forgetAccess]), which a network failure never is. [busy]: Drive said
 * it's limiting requests just now (a 403 for a rate limit).
 */
class DriveHttpException(val code: Int, message: String, val busy: Boolean = false) : IOException(message) {
    /**
     * Google isn't answering properly, which clears by itself: busy or failing (408, 429, 5xx, a rate
     * limit), or still refusing a token it has just renewed (401). Tried again like a network failure.
     */
    val unreachable: Boolean get() = code == 401 || code == 408 || code == 429 || code >= 500 || busy
}

/**
 * What a listing came to: its entries, and [cutAt], the limit it stopped at because Drive had more
 * (0: this is all of it), so the screen can say so instead of quietly leaving the rest out.
 */
data class DriveListing(val files: List<DriveFile>, val cutAt: Int = 0)

/** One page of a listing: its entries, and the token for the next page (null: that was the last). */
internal class DrivePage(val files: List<DriveFile>, val next: String?)

/**
 * Reads pages from [fetch] (given null for the first, then each page's token) until the last one,
 * or until [limit] entries are in: then exactly [limit] of them, and the listing says where it was
 * cut. Visible for tests.
 */
internal inline fun pageAll(limit: Int, fetch: (pageToken: String?) -> DrivePage): DriveListing {
    val out = ArrayList<DriveFile>()
    var next: String? = null
    do {
        val page = fetch(next)
        out += page.files
        next = page.next
    } while (next != null && out.size < limit)
    val cut = next != null || out.size > limit
    return DriveListing(if (out.size > limit) ArrayList(out.subList(0, limit)) else out, if (cut) limit else 0)
}

/**
 * Minimal Drive v3 REST client over HttpURLConnection; read-only, matching the drive.readonly
 * scope. Streaming playback doesn't go through here: the player fetches [mediaUrl] itself with
 * a bearer token, using HTTP range requests so seeking works.
 */
object DriveApi {
    private const val BASE = "https://www.googleapis.com/drive/v3/files"
    private const val FOLDER = "application/vnd.google-apps.folder"
    private const val FIELDS = "nextPageToken,files(id,name,mimeType,size,modifiedTime,createdTime,thumbnailLink)"
    /** Entries per request: Drive's most. A library of 1,000 films is one request instead of five. */
    private const val PAGE_SIZE = 1000

    fun mediaUrl(id: String) = "$BASE/${enc(id)}?alt=media&supportsAllDrives=true"

    /** Folder ids already found for [DrivePlace.Named], so each library opens with one request. */
    private val namedIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Signing out: another account's Movies / TV Shows folders are found afresh. */
    fun forgetFolders() = namedIds.clear()

    /**
     * A library folder (Movies, TV Shows) lists this many entries at most: far more than a family's
     * collection (a loose film is two or three entries with its poster and subtitles), yet a bound on
     * the memory and time one listing takes on a TV with 1.5 GB. It used to stop at 2,000, silently,
     * leaving out the titles last in the alphabet, newly added ones included.
     */
    const val LIBRARY_LIMIT = 20_000

    /**
     * Everywhere else: All videos (newest first, so a cut leaves out the oldest), Shared with me and
     * plain folders.
     */
    const val LIST_LIMIT = 2_000

    /** [place]'s entries (see [listing]), for callers that don't need to know where it was cut. */
    suspend fun list(token: String, place: DrivePlace): List<DriveFile> = listing(token, place).files

    /**
     * Lists [place]: a library folder up to [LIBRARY_LIMIT] entries, anything else up to
     * [LIST_LIMIT], and where it was cut when Drive had more.
     */
    suspend fun listing(token: String, place: DrivePlace): DriveListing = withContext(Dispatchers.IO) {
        val (q, order) = when (place) {
            is DrivePlace.Named -> {
                val id = namedIds[place.title] ?: findFolder(token, place.names)?.also { namedIds[place.title] = it }
                    ?: throw IOException("There’s no “${place.names.first()}” folder in your Google Drive yet. Create one and put your ${place.title.lowercase()} in it.")
                "'${id.replace("'", "\\'")}' in parents and trashed = false" to "folder,name_natural"
            }
            is DrivePlace.Folder -> "'${place.id.replace("'", "\\'")}' in parents and trashed = false" to "folder,name_natural"
            DrivePlace.SharedWithMe -> "sharedWithMe = true and trashed = false" to "folder,name_natural"
            is DrivePlace.Videos -> "mimeType contains 'video/' and trashed = false" to "modifiedTime desc"
        }
        // One for the whole listing: a new one for every date (two per file) was most of the time
        // spent reading a big library's answer.
        val times = Rfc3339()
        pageAll(if (place is DrivePlace.Named) LIBRARY_LIMIT else LIST_LIMIT) { pageToken ->
            ensureActive()
            val url = "$BASE?q=${enc(q)}&orderBy=${enc(order)}&pageSize=$PAGE_SIZE&fields=${enc(FIELDS)}" +
                "&includeItemsFromAllDrives=true&supportsAllDrives=true" +
                (pageToken?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(get(token, url))
            val files = json.optJSONArray("files")
            val page = ArrayList<DriveFile>(files?.length() ?: 0)
            for (i in 0 until (files?.length() ?: 0)) {
                val f = files!!.getJSONObject(i)
                page += DriveFile(
                    id = f.getString("id"),
                    name = f.optString("name"),
                    mimeType = f.optString("mimeType"),
                    size = f.optString("size").toLongOrNull() ?: 0L,
                    modified = times.parse(f.optString("modifiedTime")),
                    thumbnailLink = f.optString("thumbnailLink").ifEmpty { null },
                    created = times.parse(f.optString("createdTime")),
                )
            }
            DrivePage(page, json.optString("nextPageToken").ifEmpty { null })
        }
    }

    /** The folder a file sits in (its first parent), or null at the top. */
    suspend fun parentOf(token: String, id: String): String? = withContext(Dispatchers.IO) {
        JSONObject(get(token, "$BASE/${enc(id)}?fields=parents&supportsAllDrives=true")).optJSONArray("parents")?.optString(0)?.ifEmpty { null }
    }

    /** Folder [id]'s name and the folder it sits in (none at the top of a drive, or for one shared with you). */
    suspend fun folderFacts(token: String, id: String): FolderFacts = withContext(Dispatchers.IO) {
        val o = JSONObject(get(token, "$BASE/${enc(id)}?fields=name,parents&supportsAllDrives=true"))
        FolderFacts(o.optString("name"), o.optJSONArray("parents")?.optString(0)?.ifEmpty { null })
    }

    /**
     * Videos put in Drive since [since] (Drive's createdTime: when each was uploaded or copied in),
     * newest first, up to [limit]: what's new anywhere in the family's Drive, for the new episodes of
     * the shows they have. A page of 100 is one small request; most months it's the only one.
     */
    suspend fun recentVideos(token: String, since: Long, limit: Int = RECENT_LIMIT): List<RecentVideo> = withContext(Dispatchers.IO) {
        val after = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(since))
        val q = "mimeType contains 'video/' and createdTime > '$after' and trashed = false"
        val times = Rfc3339()
        val out = ArrayList<RecentVideo>()
        var next: String? = null
        do {
            ensureActive()
            val url = "$BASE?q=${enc(q)}&orderBy=${enc("createdTime desc")}&pageSize=100" +
                "&fields=${enc("nextPageToken,files(id,name,parents,createdTime)")}&includeItemsFromAllDrives=true&supportsAllDrives=true" +
                (next?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(get(token, url))
            val files = json.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) {
                val f = files!!.getJSONObject(i)
                out += RecentVideo(f.getString("id"), f.optString("name"), f.optJSONArray("parents")?.optString(0)?.ifEmpty { null }, times.parse(f.optString("createdTime")))
            }
            next = json.optString("nextPageToken").ifEmpty { null }
        } while (next != null && out.size < limit)
        if (out.size > limit) out.subList(limit, out.size).clear()
        out
    }

    /** New videos looked at, at most: three pages, newest first (a month of a big family's uploads). */
    const val RECENT_LIMIT = 300

    /** The Drive id of library folder [place], once a listing has found it (null before). */
    fun folderIdOf(place: DrivePlace.Named): String? = namedIds[place.title]

    /** The files in the same folder as file [id] (its first parent), in Drive's natural name order. */
    suspend fun siblings(token: String, id: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val json = JSONObject(get(token, "$BASE/${enc(id)}?fields=parents&supportsAllDrives=true"))
        val parent = json.optJSONArray("parents")?.optString(0)?.ifEmpty { null } ?: return@withContext emptyList()
        list(token, DrivePlace.Folder(parent, ""))
    }

    private val POSTER_NAMES = setOf("poster", "folder", "cover")
    /** Poster lookups at once: a big library's folders are found three times as fast, and Drive isn't flooded. */
    private const val POSTER_LANES = 3

    /**
     * Poster art, Plex/Kodi style: a folder's poster is a poster/folder/cover image inside it; a
     * loose video's poster is an image with the same name next to it (Inception.mp4 + Inception.jpg).
     * Those images are then hidden from the grid. Costs one extra request per 25 folders, three at a
     * time (one after another, a library of 1,000 shows waited on 40 of them in a row).
     */
    suspend fun withPosters(token: String, files: List<DriveFile>): List<DriveFile> = withContext(Dispatchers.IO) {
        val images = files.filter { it.kind == FileKind.IMAGE }
        val byStem = images.associateBy { it.name.substringBeforeLast('.').lowercase() }
        val used = HashSet<String>()
        val folderPosters = java.util.concurrent.ConcurrentHashMap<String, String>()
        val lanes = Semaphore(POSTER_LANES)
        coroutineScope {
            files.filter { it.isFolder }.chunked(25).map { chunk ->
                async {
                    lanes.withPermit {
                        ensureActive()
                        val parents = chunk.joinToString(" or ") { "'${it.id.replace("'", "\\'")}' in parents" }
                        val q = "mimeType contains 'image/' and trashed = false and " +
                            "(name contains 'poster' or name contains 'folder' or name contains 'cover') and ($parents)"
                        val url = "$BASE?q=${enc(q)}&pageSize=200&fields=${enc("files(name,parents,thumbnailLink)")}" +
                            "&includeItemsFromAllDrives=true&supportsAllDrives=true"
                        val found = runCatching { JSONObject(get(token, url)).optJSONArray("files") }.getOrNull() ?: return@withPermit
                        for (i in 0 until found.length()) {
                            val f = found.getJSONObject(i)
                            val stem = f.optString("name").substringBeforeLast('.').lowercase()
                            val link = f.optString("thumbnailLink").ifEmpty { null } ?: continue
                            if (stem !in POSTER_NAMES) continue
                            val parentsArr = f.optJSONArray("parents") ?: continue
                            for (j in 0 until parentsArr.length()) folderPosters.putIfAbsent(parentsArr.getString(j), big(link))
                        }
                    }
                }
            }.awaitAll()
        }
        files.mapNotNull { f ->
            when {
                f.isFolder -> f.copy(posterUrl = folderPosters[f.id])
                f.kind == FileKind.VIDEO -> {
                    val img = byStem[f.name.substringBeforeLast('.').lowercase()]
                    if (img != null) used += img.id
                    // Only real artwork here; the tile falls back to the video frame, and TMDB fills the rest.
                    f.copy(posterUrl = img?.thumbnailLink?.let(::big))
                }
                else -> f
            }
        }.filter { it.id !in used && !(it.kind == FileKind.IMAGE && it.name.substringBeforeLast('.').lowercase() in POSTER_NAMES) }
    }

    /** Drive thumbnails come at 220px ("=s220"); ask for a size that's sharp on a TV poster. */
    fun big(link: String) = link.replace(THUMB_SIZE, "") + "=s720"

    // Compiled once: posters and episode cards ask for every tile they compose.
    private val THUMB_SIZE = Regex("=s\\d+(-c)?$")

    /** The first folder named one of [names]: at the top of My Drive if there is one, else anywhere. */
    private fun findFolder(token: String, names: List<String>): String? {
        for (topLevel in listOf(true, false)) {
            for (name in names) {
                val q = "mimeType = '$FOLDER' and name = '${name.replace("'", "\\'")}' and trashed = false" +
                    if (topLevel) " and 'root' in parents" else ""
                val url = "$BASE?q=${enc(q)}&pageSize=1&fields=${enc("files(id)")}&includeItemsFromAllDrives=true&supportsAllDrives=true"
                val files = JSONObject(get(token, url)).optJSONArray("files")
                if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
            }
        }
        return null
    }

    /** Copies a Drive file to [dest], deleting the partial file on failure or cancel. */
    suspend fun download(token: String, file: DriveFile, dest: File, onProgress: (OpProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val conn = open(token, mediaUrl(file.id))
            try {
                check(conn)
                val total = file.size.takeIf { it > 0 } ?: conn.contentLengthLong
                var done = 0L
                val buf = ByteArray(256 * 1024)
                try {
                    conn.inputStream.use { input ->
                        FileOutputStream(dest).use { out ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(OpProgress("Downloading", done, total, file.name))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    dest.delete()
                    throw t
                }
            } finally {
                conn.disconnect()
            }
        }

    private fun get(token: String, url: String): String {
        val conn = open(token, url)
        try {
            check(conn)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(token: String, url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("Authorization", "Bearer $token")
        connectTimeout = 15_000
        readTimeout = 30_000
    }

    private fun check(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        // Why, in Google's words ("userRateLimitExceeded", "accessNotConfigured"…): never shown, only read.
        val reason = runCatching { JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason") }.getOrNull()
        throw DriveHttpException(code, answerWords(code, reason), busy = code == 403 && reason?.contains("ratelimit", ignoreCase = true) == true)
    }

    /**
     * What Drive's error answer [code] (for Google's [reason]) says on screen: no codes or Google's own
     * messages, and what to do when it won't clear by itself. A 401 here comes after a fresh token, so it
     * isn't a sign-in that ended (renewing would have said so). A 403 that isn't Drive limiting requests
     * is a setting to fix (the Drive API not turned on for the keys, or Google Drive not allowed on the
     * consent page): "can't reach" would have the family wait for something that never comes. Visible
     * for tests.
     */
    internal fun answerWords(code: Int, reason: String?): String = when {
        code == 404 -> "That file is no longer in Google Drive."
        code == 403 && reason != null && reason.contains("ratelimit", ignoreCase = true) -> CANT_REACH
        code == 403 && reason != null && (reason.equals("accessNotConfigured", ignoreCase = true) || reason.contains("disabled", ignoreCase = true)) ->
            "The Google Drive API isn’t turned on for this TV’s Google keys. Turn it on in Google Cloud, then try again."
        code == 403 && reason != null && (reason.startsWith("insufficient", ignoreCase = true) || reason.contains("scope", ignoreCase = true)) ->
            "This TV isn’t allowed to see your Google Drive. Sign out of Google Drive, then sign in again and allow it on Google’s page."
        else -> CANT_REACH
    }

    /** What any other answer from Drive says on screen. */
    internal const val CANT_REACH = "Can’t reach Google Drive right now. Try again in a moment."

    /**
     * Reads times as Drive sends them, RFC 3339 in UTC (2026-10-14T00:48:23.123Z); java.time needs
     * API 26. One per listing: it isn't safe to share between threads. 0 for a time it can't read.
     */
    internal class Rfc3339 {
        private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun parse(s: String): Long = if (s.isEmpty()) 0L else runCatching { format.parse(s)!!.time }.getOrDefault(0L)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
