package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.data.OpProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
) {
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
 * Minimal Drive v3 REST client over HttpURLConnection; read-only, matching the drive.readonly
 * scope. Streaming playback doesn't go through here: the player fetches [mediaUrl] itself with
 * a bearer token, using HTTP range requests so seeking works.
 */
object DriveApi {
    private const val BASE = "https://www.googleapis.com/drive/v3/files"
    private const val FOLDER = "application/vnd.google-apps.folder"
    private const val FIELDS = "nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink)"

    fun mediaUrl(id: String) = "$BASE/${enc(id)}?alt=media&supportsAllDrives=true"

    /** Folder ids already found for [DrivePlace.Named], so each library opens with one request. */
    private val namedIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun list(token: String, place: DrivePlace): List<DriveFile> = withContext(Dispatchers.IO) {
        val (q, order) = when (place) {
            is DrivePlace.Named -> {
                val id = namedIds[place.title] ?: findFolder(token, place.names)?.also { namedIds[place.title] = it }
                    ?: throw IOException("There's no \"${place.names.first()}\" folder in your Google Drive yet. Create one and put your ${place.title.lowercase()} in it.")
                "'${id.replace("'", "\\'")}' in parents and trashed = false" to "folder,name_natural"
            }
            is DrivePlace.Folder -> "'${place.id.replace("'", "\\'")}' in parents and trashed = false" to "folder,name_natural"
            DrivePlace.SharedWithMe -> "sharedWithMe = true and trashed = false" to "folder,name_natural"
            is DrivePlace.Videos -> "mimeType contains 'video/' and trashed = false" to "modifiedTime desc"
        }
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            ensureActive()
            val url = "$BASE?q=${enc(q)}&orderBy=${enc(order)}&pageSize=200&fields=${enc(FIELDS)}" +
                "&includeItemsFromAllDrives=true&supportsAllDrives=true" +
                (pageToken?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(get(token, url))
            val files = json.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) {
                val f = files!!.getJSONObject(i)
                out += DriveFile(
                    id = f.getString("id"),
                    name = f.optString("name"),
                    mimeType = f.optString("mimeType"),
                    size = f.optString("size").toLongOrNull() ?: 0L,
                    modified = parseTime(f.optString("modifiedTime")),
                    thumbnailLink = f.optString("thumbnailLink").ifEmpty { null },
                )
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null && out.size < 2000)
        out
    }

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
        val reason = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        throw IOException(
            when (code) {
                401 -> "Google sign-in expired. Choose Google Drive again to reconnect."
                403 -> reason ?: "Google Drive refused access (403). Check the Drive API is enabled in your Google Cloud project."
                404 -> "That file is no longer in Google Drive."
                else -> "Google Drive error $code${reason?.let { ": $it" } ?: ""}"
            },
        )
    }

    /** RFC 3339 as Drive sends it (e.g. 2026-10-14T00:48:23.123Z); java.time needs API 26. */
    private fun parseTime(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    }.getOrDefault(0L)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
