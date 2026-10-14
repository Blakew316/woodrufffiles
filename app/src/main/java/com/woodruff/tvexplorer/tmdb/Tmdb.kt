package com.woodruff.tvexplorer.tmdb

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
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
 * "not found", are cached on disk so each title is looked up once. Each match also brings the
 * release year, genres and rating used by the library filters. The owner's own poster art
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
    private val cache = ConcurrentHashMap<String, String>() // query -> Hit JSON ("" = no match)
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

    /**
     * Fills [DriveFile.posterUrl] where it's empty and [DriveFile.info] (year, genres, rating).
     * [show] is the show's name when listing its seasons.
     */
    suspend fun fill(c: Context, files: List<DriveFile>, mode: Mode, show: String? = null): List<DriveFile> {
        if (mode == Mode.NONE) return files
        val key = key(c) ?: return files
        load(c)
        val before = cache.size
        val out = coroutineScope {
            files.map { f ->
                async(Dispatchers.IO) {
                    // Seasons only need art; movies and shows are looked up even with the owner's
                    // own poster, for the year / genre / rating filters.
                    if (mode == Mode.SEASONS && f.posterUrl != null) return@async f
                    val hit = runCatching {
                        gate.withPermit {
                            when (mode) {
                                Mode.MOVIES -> if (f.isFolder || f.kind == FileKind.VIDEO) movie(key, f.name, f.isFolder) else null
                                Mode.SHOWS -> if (f.isFolder) tv(key, f.name) else null
                                Mode.SEASONS -> if (f.isFolder && show != null) season(key, show, f.name) else null
                                Mode.NONE -> null
                            }
                        }
                    }.onFailure { Log.w("Tmdb", "lookup failed: ${f.name}", it) }.getOrNull() ?: return@async f
                    val parsed = f.info ?: Library.parsed(f.name, f.isFolder)
                    f.copy(
                        posterUrl = f.posterUrl ?: hit.poster,
                        info = if (mode == Mode.SEASONS) f.info else parsed.copy(
                            year = parsed.year ?: hit.year, genres = hit.genres, rating = hit.rating,
                        ),
                    )
                }
            }.awaitAll()
        }
        if (cache.size != before) save(c)
        return out
    }

    /** One TMDB match, as cached: poster URL, release year, genre names, rating out of 10. */
    internal data class Hit(val poster: String?, val year: Int?, val genres: List<String>, val rating: Double?) {
        fun toJson(): String = JSONObject().put("p", poster ?: "").put("y", year ?: 0)
            .put("g", org.json.JSONArray(genres)).put("r", rating ?: 0.0).toString()

        companion object {
            fun fromJson(s: String): Hit? = runCatching {
                val o = JSONObject(s)
                val g = o.optJSONArray("g")
                Hit(
                    o.optString("p").ifBlank { null }, o.optInt("y").takeIf { it > 0 },
                    g?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    o.optDouble("r", 0.0).takeIf { it > 0 },
                )
            }.getOrNull()
        }
    }

    private fun movie(key: String, name: String, folder: Boolean): Hit? {
        val (title, year) = parse(name, stripExtension = !folder)
        if (title.isBlank()) return null
        return cached("m:$title:$year") {
            val q = "search/movie?query=${enc(title)}&include_adult=false"
            best(get(key, q + (year?.let { "&year=$it" } ?: "")), MOVIE_GENRES, "release_date")
                ?: if (year != null) best(get(key, q), MOVIE_GENRES, "release_date") else null
        }
    }

    private fun tv(key: String, name: String): Hit? {
        val (title, year) = parse(name, stripExtension = false)
        if (title.isBlank()) return null
        return cached("t:$title:$year") { showResult(key, title, year)?.let { hit(it, TV_GENRES, "first_air_date") } }
    }

    private fun season(key: String, showName: String, folder: String): Hit? {
        val number = seasonNumber(folder) ?: return tv(key, showName)
        val (title, year) = parse(showName, stripExtension = false)
        return cached("s:$title:$year:$number") {
            val id = showResult(key, title, year)?.optInt("id") ?: return@cached null
            runCatching { get(key, "tv/$id/season/$number").optString("poster_path").ifBlank { null } }.getOrNull()
                ?.let { Hit(IMG + it, null, emptyList(), null) }
                ?: tv(key, showName) // no season art: use the show's poster
        }
    }

    private fun showResult(key: String, title: String, year: String?): JSONObject? {
        val r = get(key, "search/tv?query=${enc(title)}" + (year?.let { "&first_air_date_year=$it" } ?: ""))
        return r.optJSONArray("results")?.optJSONObject(0)
            ?: if (year != null) get(key, "search/tv?query=${enc(title)}").optJSONArray("results")?.optJSONObject(0) else null
    }

    /** The first of the top 5 results with a poster (else the top result, for its details). */
    private fun best(r: JSONObject, genres: Map<Int, List<String>>, dateField: String): Hit? {
        val results = r.optJSONArray("results") ?: return null
        val top = (0 until minOf(results.length(), 5)).mapNotNull { results.optJSONObject(it) }
        val pick = top.firstOrNull { it.optString("poster_path").isNotBlank() } ?: top.firstOrNull() ?: return null
        return hit(pick, genres, dateField)
    }

    /** Reads one TMDB search result. Visible for tests. */
    internal fun hit(o: JSONObject, genres: Map<Int, List<String>>, dateField: String): Hit {
        val ids = o.optJSONArray("genre_ids")
        val names = ids?.let { a -> (0 until a.length()).flatMap { genres[a.optInt(it)].orEmpty() } }.orEmpty().distinct()
        // A score from a handful of votes means little: leave it out of "Top rated".
        val rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 && o.optInt("vote_count") >= 20 }
        return Hit(
            o.optString("poster_path").ifBlank { null }?.let { IMG + it },
            o.optString(dateField).take(4).toIntOrNull()?.takeIf { it in 1870..2100 },
            names, rating,
        )
    }

    private inline fun cached(query: String, lookup: () -> Hit?): Hit? {
        cache[query]?.let { return if (it.isEmpty()) null else Hit.fromJson(it) }
        val hit = lookup()
        cache[query] = hit?.toJson().orEmpty()
        return hit
    }

    // TMDB's genre ids (stable). TV's combined genres are split so one filter covers both.
    private val MOVIE_GENRES: Map<Int, List<String>> = mapOf(
        28 to listOf("Action"), 12 to listOf("Adventure"), 16 to listOf("Animation"), 35 to listOf("Comedy"),
        80 to listOf("Crime"), 99 to listOf("Documentary"), 18 to listOf("Drama"), 10751 to listOf("Family"),
        14 to listOf("Fantasy"), 36 to listOf("History"), 27 to listOf("Horror"), 10402 to listOf("Music"),
        9648 to listOf("Mystery"), 10749 to listOf("Romance"), 878 to listOf("Sci-Fi"), 10770 to listOf("TV Movie"),
        53 to listOf("Thriller"), 10752 to listOf("War"), 37 to listOf("Western"),
    )
    private val TV_GENRES: Map<Int, List<String>> = mapOf(
        10759 to listOf("Action", "Adventure"), 16 to listOf("Animation"), 35 to listOf("Comedy"), 80 to listOf("Crime"),
        99 to listOf("Documentary"), 18 to listOf("Drama"), 10751 to listOf("Family"), 10762 to listOf("Kids"),
        9648 to listOf("Mystery"), 10763 to listOf("News"), 10764 to listOf("Reality"), 10765 to listOf("Sci-Fi", "Fantasy"),
        10766 to listOf("Soap"), 10767 to listOf("Talk"), 10768 to listOf("War", "Politics"), 37 to listOf("Western"),
    )
    internal val movieGenres get() = MOVIE_GENRES
    internal val tvGenres get() = TV_GENRES

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

    // v2 holds full matches; the old file (posters only) is dropped so titles get their genres.
    private fun cacheFile(c: Context) = File(c.applicationContext.filesDir, "tmdb_cache2.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        File(c.applicationContext.filesDir, "tmdb_cache.json").delete()
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
