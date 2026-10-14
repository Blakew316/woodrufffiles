package com.woodruff.tvexplorer.tmdb

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Automatic posters from The Movie Database (TMDB). Titles come from file and folder names
 * ("Inception (2010).mkv", "The.Office.US.S01.1080p" → "The Office US"); results, including
 * "not found", are cached on disk so each title is looked up once. The owner's own poster art
 * always wins; this only fills tiles that have none.
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
object Tmdb {
    enum class Mode { MOVIES, SHOWS, SEASONS, NONE }

    private const val KEY = "tmdb.key"
    private const val API = "https://api.themoviedb.org/3/"
    // w342 is plenty for a ~150dp poster tile and about 40% smaller to download than w500.
    private const val IMG = "https://image.tmdb.org/t/p/w342"
    private val cache = ConcurrentHashMap<String, String>() // query -> poster URL ("" = none)
    @Volatile private var loaded = false
    private val gate = Semaphore(6)

    fun key(c: Context): String? = SecretStore.get(c).get(KEY)?.takeIf { it.isNotBlank() }
    fun isConfigured(c: Context) = key(c) != null

    /** Accepts either the v3 "API Key" or the v4 "API Read Access Token". */
    fun saveKey(c: Context, key: String?) {
        SecretStore.get(c).put(KEY, key?.trim()?.ifBlank { null })
        cache.clear()
        cacheFile(c).delete()
    }

    /** Fills [DriveFile.posterUrl] where it's empty. [show] is the show's name when listing its seasons. */
    suspend fun fill(c: Context, files: List<DriveFile>, mode: Mode, show: String? = null): List<DriveFile> {
        if (mode == Mode.NONE) return files
        val key = key(c) ?: return files
        load(c)
        val before = cache.size
        val out = coroutineScope {
            files.map { f ->
                async(Dispatchers.IO) {
                    if (f.posterUrl != null) return@async f
                    val url = runCatching {
                        gate.withPermit {
                            when (mode) {
                                Mode.MOVIES -> if (f.isFolder || f.kind == FileKind.VIDEO) movie(key, f.name, f.isFolder) else null
                                Mode.SHOWS -> if (f.isFolder) tv(key, f.name) else null
                                Mode.SEASONS -> if (f.isFolder && show != null) season(key, show, f.name) else null
                                Mode.NONE -> null
                            }
                        }
                    }.onFailure { Log.w("Tmdb", "lookup failed: ${f.name}", it) }.getOrNull()
                    if (url != null) f.copy(posterUrl = url) else f
                }
            }.awaitAll()
        }
        if (cache.size != before) save(c)
        return out
    }

    private fun movie(key: String, name: String, folder: Boolean): String? {
        val (title, year) = parse(name, stripExtension = !folder)
        if (title.isBlank()) return null
        return cached("m:$title:$year") {
            val r = get(key, "search/movie?query=${enc(title)}&include_adult=false" + (year?.let { "&year=$it" } ?: ""))
            firstPoster(r) ?: if (year != null) firstPoster(get(key, "search/movie?query=${enc(title)}&include_adult=false")) else null
        }
    }

    private fun tv(key: String, name: String): String? {
        val (title, year) = parse(name, stripExtension = false)
        if (title.isBlank()) return null
        return cached("t:$title:$year") { showResult(key, title, year)?.optString("poster_path")?.ifBlank { null }?.let { IMG + it } }
    }

    private fun season(key: String, showName: String, folder: String): String? {
        val number = seasonNumber(folder) ?: return tv(key, showName)
        val (title, year) = parse(showName, stripExtension = false)
        return cached("s:$title:$year:$number") {
            val id = showResult(key, title, year)?.optInt("id") ?: return@cached null
            runCatching { get(key, "tv/$id/season/$number").optString("poster_path").ifBlank { null }?.let { IMG + it } }.getOrNull()
                ?: tv(key, showName) // no season art: use the show's poster
        }
    }

    private fun showResult(key: String, title: String, year: String?): JSONObject? {
        val r = get(key, "search/tv?query=${enc(title)}" + (year?.let { "&first_air_date_year=$it" } ?: ""))
        return r.optJSONArray("results")?.optJSONObject(0)
            ?: if (year != null) get(key, "search/tv?query=${enc(title)}").optJSONArray("results")?.optJSONObject(0) else null
    }

    private fun firstPoster(r: JSONObject): String? {
        val results = r.optJSONArray("results") ?: return null
        for (i in 0 until minOf(results.length(), 5)) {
            val p = results.optJSONObject(i)?.optString("poster_path").orEmpty()
            if (p.isNotBlank()) return IMG + p
        }
        return null
    }

    private inline fun cached(query: String, lookup: () -> String?): String? {
        cache[query]?.let { return it.ifEmpty { null } }
        val url = lookup()
        cache[query] = url.orEmpty()
        return url
    }

    private fun get(key: String, path: String): JSONObject {
        // v4 read-access tokens are JWTs ("eyJ…") sent as a bearer token; v3 keys go in the URL.
        val bearer = key.startsWith("eyJ")
        val url = API + path + if (bearer) "" else (if ('?' in path) "&" else "?") + "api_key=${enc(key)}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            if (bearer) setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 401) throw IllegalStateException("TMDB rejected the API key")
            if (code !in 200..299) throw IllegalStateException("TMDB error $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    // ---- Names → titles -------------------------------------------------------------------

    private val YEAR = Regex("(?<![0-9])(19[2-9][0-9]|20[0-9]{2})(?![0-9])")
    private val JUNK = Regex(
        "(?i)\\b(2160p|1080p|720p|576p|480p|4k|uhd|hdr10?|dv|bluray|blu ray|bdrip|brrip|webrip|web dl|webdl|web|hdtv|dvdrip|" +
            "x264|x265|h 264|h 265|hevc|avc|aac|ac3|dts|atmos|remux|proper|repack|extended|unrated|imax|criterion|" +
            "s\\d{1,2}(e\\d{1,3})?|season \\d+|complete)\\b",
    )

    /** "The.Matrix.1999.1080p.BluRay.mkv" → ("The Matrix", "1999"). Visible for tests. */
    fun parse(name: String, stripExtension: Boolean): Pair<String, String?> {
        var s = if (stripExtension) name.substringBeforeLast('.', name) else name
        s = s.replace(Regex("[\\[{].*?[\\]}]"), " ") // [release group], {tags}
            .replace('.', ' ').replace('_', ' ')
            .replace(Regex("\\s+"), " ").trim()
        val year = YEAR.findAll(s).firstOrNull { it.range.first > 0 }
        var title = if (year != null) s.substring(0, year.range.first) else s
        JUNK.find(title)?.let { if (it.range.first > 0) title = title.substring(0, it.range.first) }
        title = title.replace(Regex("[(\\-–:,\\s]+$"), "").replace("(", "").replace(")", "").trim()
        return title to year?.value
    }

    /** "Season 2", "S02", "Series 3" → 2 / 2 / 3; "Specials" → 0. Visible for tests. */
    fun seasonNumber(folder: String): Int? {
        val f = folder.trim().lowercase()
        if (f == "specials" || f == "extras") return 0
        Regex("(?:season|series|staffel|saison|temporada)\\s*(\\d{1,3})").find(f)?.let { return it.groupValues[1].toInt() }
        Regex("^s(\\d{1,3})$").find(f)?.let { return it.groupValues[1].toInt() }
        return null
    }

    private fun cacheFile(c: Context) = File(c.applicationContext.filesDir, "tmdb_cache.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(cacheFile(c).readText())
            o.keys().forEach { k -> cache[k] = o.getString(k) }
        }
    }

    @Synchronized
    private fun save(c: Context) {
        runCatching {
            val f = cacheFile(c)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject(cache as Map<*, *>).toString())
            tmp.renameTo(f)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
