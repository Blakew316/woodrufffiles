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
import org.json.JSONArray
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
    enum class Mode { MOVIES, SHOWS, SEASONS, EPISODES, NONE }

    private const val KEY = "tmdb.key"
    private const val API = "https://api.themoviedb.org/3/"
    // w342 is plenty for a ~150dp poster tile and about 40% smaller to download than w500.
    private const val IMG = "https://image.tmdb.org/t/p/w342"
    /** Episode pictures: only small sizes or the original exist; a 16:9 card on a TV wants the original. */
    private const val STILL = "https://image.tmdb.org/t/p/original"
    private const val BACKDROP = "https://image.tmdb.org/t/p/w1280"
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
     * [show] is the show's name when listing its seasons or episodes; [season] the season folder's.
     */
    suspend fun fill(c: Context, files: List<DriveFile>, mode: Mode, show: String? = null, season: String? = null): List<DriveFile> = withContext(Dispatchers.IO) {
        // All of it off the main thread: the key is a Keystore read and the cache a large JSON file.
        if (mode == Mode.NONE) return@withContext files
        val key = key(c) ?: return@withContext files
        load(c)
        if (mode == Mode.EPISODES) return@withContext if (show != null) withEpisodeStills(c, files, show, season) else files
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
                                Mode.EPISODES, Mode.NONE -> null
                            }
                        }
                    }.onFailure { Log.w("Tmdb", "lookup failed: ${f.name}", it) }.getOrNull() ?: return@async f
                    val parsed = f.info ?: Library.parsed(f.name, f.isFolder)
                    f.copy(
                        posterUrl = f.posterUrl ?: hit.poster,
                        info = if (mode == Mode.SEASONS) f.info else parsed.copy(
                            year = parsed.year ?: hit.year, genres = hit.genres, rating = hit.rating,
                            overview = hit.overview, backdrop = hit.backdrop, tmdbId = hit.id, tv = hit.tv,
                        ),
                    )
                }
            }.awaitAll()
        }
        if (cache.size != before) save(c)
        when {
            // A show's own folder can hold its episodes too (no season folders): their pictures.
            mode == Mode.SEASONS && show != null -> withEpisodeStills(c, out, show, null)
            mode == Mode.SEASONS -> out
            else -> shareWithTwins(out)
        }
    }

    /**
     * Each episode's picture from TMDB as its poster, so episode tiles aren't blank: files named
     * S02E04, 2x04, or "Episode 4" inside a "Season 2" folder ([seasonFolder]). Episodes with art of
     * their own keep it.
     */
    suspend fun withEpisodeStills(c: Context, files: List<DriveFile>, show: String, seasonFolder: String?): List<DriveFile> = withContext(Dispatchers.IO) {
        val key = key(c) ?: return@withContext files
        load(c)
        val folderSeason = seasonFolder?.let(::seasonNumber)
        val wanted = files.mapNotNull { f -> if (f.kind == FileKind.VIDEO && f.posterUrl == null) episodeNumber(f.name, folderSeason) else null }
        if (wanted.isEmpty()) return@withContext files
        val (parsed, year) = parse(lookupName(show), stripExtension = false)
        val title = searchTitle(parsed)
        if (title.isBlank()) return@withContext files
        val before = cache.size
        val seasons = wanted.map { it.first }.distinct().associateWith { s ->
            runCatching { gate.withPermit { episodesOf(key, title, year, s) } }
                .onFailure { Log.w("Tmdb", "episode pictures failed: $title season $s", it) }
                .getOrDefault(emptyMap())
        }
        if (cache.size != before) save(c)
        files.map { f ->
            if (f.kind != FileKind.VIDEO || f.posterUrl != null) return@map f
            val (s, e) = episodeNumber(f.name, folderSeason) ?: return@map f
            seasons[s]?.get(e)?.still?.let { f.copy(posterUrl = it) } ?: f
        }
    }

    /**
     * An episode's length in minutes, as TMDB lists it in its season (the same request as its
     * picture), for "Ends at" on a show's page. [episode] is its file name; [seasonFolder] the
     * season folder's, for names like "Episode 4". Null when it isn't known.
     */
    suspend fun episodeRuntime(c: Context, show: String, episode: String, seasonFolder: String?): Int? = withContext(Dispatchers.IO) {
        val key = key(c) ?: return@withContext null
        load(c)
        val (s, e) = episodeNumber(episode, seasonFolder?.let(::seasonNumber)) ?: return@withContext null
        val (parsed, year) = parse(lookupName(show), stripExtension = false)
        val title = searchTitle(parsed)
        if (title.isBlank()) return@withContext null
        val before = cache.size
        val runtime = runCatching { gate.withPermit { episodesOf(key, title, year, s) } }
            .onFailure { Log.w("Tmdb", "episode lengths failed: $title season $s", it) }
            .getOrNull()?.get(e)?.runtime
        if (cache.size != before) save(c)
        runtime
    }

    /** One episode as TMDB lists it: its picture and its length in minutes (either can be missing). */
    internal data class Episode(val still: String?, val runtime: Int?)

    /** Season [season] of the show: episode number → its picture and length. */
    private fun episodesOf(key: String, title: String, year: String?, season: Int): Map<Int, Episode> {
        val json = cachedJson("ep:$title:$year:$season") {
            val id = showResult(key, title, year)?.optInt("id")?.takeIf { it > 0 } ?: return@cachedJson null
            val episodes = seasonEpisodes(get(key, "tv/$id/season/$season"))
            JSONObject().apply {
                episodes.forEach { (n, e) -> put(n.toString(), JSONObject().put("s", e.still ?: "").put("r", e.runtime ?: 0)) }
            }
        } ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { k ->
            val e = json.optJSONObject(k) ?: return@mapNotNull null
            k.toIntOrNull()?.let { it to Episode(e.optString("s").ifBlank { null }, e.optInt("r").takeIf { r -> r > 0 }) }
        }.toMap()
    }

    /** Reads a season (tv/{id}/season/{n}): episode number → picture and length. Visible for tests. */
    internal fun seasonEpisodes(o: JSONObject): Map<Int, Episode> {
        val episodes = o.optJSONArray("episodes") ?: return emptyMap()
        return (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }.mapNotNull { e ->
            val n = e.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
            val ep = Episode(e.text("still_path")?.let { STILL + it }, e.optInt("runtime").takeIf { it > 0 })
            if (ep.still == null && ep.runtime == null) null else n to ep
        }.toMap()
    }

    /** Like [cached], for a JSON object ("" = looked up, nothing found). */
    private inline fun cachedJson(query: String, lookup: () -> JSONObject?): JSONObject? {
        cache[query]?.let { return if (it.isEmpty()) null else runCatching { JSONObject(it) }.getOrNull() }
        val v = lookup()
        cache[query] = v?.toString().orEmpty()
        return v
    }

    /** "Show.S02E04" / "2x04" → (2, 4); "Episode 4" → ([folderSeason], 4). Visible for tests. */
    fun episodeNumber(name: String, folderSeason: Int?): Pair<Int, Int>? {
        EP_CODE.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        EP_X.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        if (folderSeason != null) EP_WORD.find(name)?.let { return folderSeason to it.groupValues[1].toInt() }
        return null
    }

    private val EP_CODE = Regex("(?i)s(\\d{1,2})\\s*e(\\d{1,3})")
    private val EP_X = Regex("(?i)(?<!\\d)(\\d{1,2})x(\\d{2,3})(?!\\d)")
    private val EP_WORD = Regex("(?i)\\b(?:episode|ep)\\s*(\\d{1,3})\\b")

    /**
     * A title kept twice (a copy, another version) that still found nothing borrows its twin's
     * poster and details: the same title (and year, when both have one) in the same library.
     * Visible for tests.
     */
    internal fun shareWithTwins(files: List<DriveFile>): List<DriveFile> {
        fun twinKey(f: DriveFile): String {
            val (t, _) = parse(lookupName(f.name), stripExtension = !f.isFolder)
            return Library.normalize(searchTitle(t))
        }
        val donors = files.filter { it.posterUrl != null }.groupBy(::twinKey)
        if (donors.isEmpty()) return files
        return files.map { f ->
            if (f.posterUrl != null) return@map f
            val candidates = donors[twinKey(f)] ?: return@map f
            val year = f.info?.year
            val twin = when {
                // Another year's film of the same name is a different film, not a twin.
                year != null -> candidates.firstOrNull { it.info?.year == year } ?: candidates.firstOrNull { it.info?.year == null }
                else -> candidates.takeIf { c -> c.mapNotNull { it.info?.year }.distinct().size <= 1 }?.first()
            } ?: return@map f
            val mine = f.info
            val theirs = twin.info
            f.copy(
                posterUrl = twin.posterUrl,
                info = if (mine != null && theirs != null && mine.tmdbId == null) theirs.copy(title = mine.title, year = mine.year ?: theirs.year) else mine,
            )
        }
    }

    /**
     * A name as it's looked up: without what duplicating a file adds — Drive's "Copy of …",
     * " - Copy", a " (1)" before the extension. Visible for tests.
     */
    fun lookupName(name: String): String = name.replace(COPY_PREFIX, "").replace(COPY_NUMBER, "").replace(COPY_SUFFIX, "")

    /**
     * A title as it's searched: without an edition ("Director's Cut", "Special Edition", "Blu-Ray": a
     * second copy shows its label but finds the film's poster). Visible for tests.
     */
    fun searchTitle(title: String): String = title.replace(EDITION, " ").replace(SPACES, " ").trim().ifBlank { title }

    private val COPY_PREFIX = Regex("(?i)^copy of\\s+")
    private val COPY_NUMBER = Regex("\\s*\\(\\d{1,2}\\)(?=\\.[A-Za-z0-9]{2,4}$|$)")
    private val COPY_SUFFIX = Regex("(?i)\\s+-\\s+copy(?=\\.[A-Za-z0-9]{2,4}$|$)")
    private val EDITION = Regex(
        "(?i)\\b(director'?s cut|extended (cut|edition|version)|theatrical (cut|edition|version)|special edition|" +
            "collector'?s edition|anniversary edition|ultimate (cut|edition)|final cut|remastered|redux|open matte|uncut|blu-?ray)\\b",
    )

    /**
     * What a title's page shows beyond the search result: a film's length, the US age rating, the
     * collection a film belongs to, and the main cast. Whatever TMDB doesn't know is null (or empty).
     */
    data class Details(
        /** Minutes, for a film (a show's episodes vary: see [episodeRuntime]). */
        val runtime: Int? = null,
        /** The US age rating: "PG-13", "TV-14". */
        val ageRating: String? = null,
        /** The film's collection ("The Lord of the Rings Collection"), whose films [collection] lists. */
        val collectionId: Int? = null,
        val collectionName: String? = null,
        /** The main cast: just names ("Name as Character" is too long for a TV line). */
        val cast: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().put("rt", runtime ?: 0).put("ar", ageRating ?: "")
            .put("ci", collectionId ?: 0).put("cn", collectionName ?: "").put("c", JSONArray(cast))

        companion object {
            fun fromJson(o: JSONObject): Details {
                val c = o.optJSONArray("c")
                return Details(
                    o.optInt("rt").takeIf { it > 0 }, o.optString("ar").ifBlank { null },
                    o.optInt("ci").takeIf { it > 0 }, o.optString("cn").ifBlank { null },
                    c?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty(),
                )
            }
        }
    }

    /**
     * A title's [Details], in one request (with its age ratings and cast appended), kept in the disk
     * cache with the rest: a title's page has them at once the next time, even after a restart.
     */
    suspend fun details(c: Context, id: Int, tv: Boolean): Details? = withContext(Dispatchers.IO) {
        val key = key(c) ?: return@withContext null
        load(c)
        val before = cache.size
        val json = runCatching {
            gate.withPermit {
                cachedJson("d:${if (tv) "t" else "m"}:$id") {
                    if (tv) showDetails(get(key, "tv/$id?append_to_response=content_ratings,aggregate_credits")).toJson()
                    else movieDetails(get(key, "movie/$id?append_to_response=release_dates,credits")).toJson()
                }
            }
        }.onFailure { Log.w("Tmdb", "details failed: ${if (tv) "tv" else "movie"} $id", it) }.getOrNull()
        if (cache.size != before) save(c)
        json?.let { Details.fromJson(it) }
    }

    /** The main cast, for a title's page (now part of [details]). */
    suspend fun cast(c: Context, id: Int, tv: Boolean): List<String> = details(c, id, tv)?.cast.orEmpty()

    /** The films in TMDB collection [id] (their TMDB ids), oldest first, for a film's page. */
    suspend fun collection(c: Context, id: Int): List<Int> = withContext(Dispatchers.IO) {
        val key = key(c) ?: return@withContext emptyList()
        load(c)
        val before = cache.size
        val json = runCatching {
            gate.withPermit { cachedJson("k:$id") { JSONObject().put("p", JSONArray(collectionParts(get(key, "collection/$id")))) } }
        }.onFailure { Log.w("Tmdb", "collection failed: $id", it) }.getOrNull()
        if (cache.size != before) save(c)
        json?.optJSONArray("p")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it > 0 } }.orEmpty()
    }

    /** Reads movie/{id}?append_to_response=release_dates,credits. Visible for tests. */
    internal fun movieDetails(o: JSONObject): Details {
        val dates = countryEntry(o.optJSONObject("release_dates"))?.optJSONArray("release_dates")
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        // The cinema release's rating first (type 3): a later TV or disc release can be unrated or cut.
        val rating = dates.sortedBy { if (it.optInt("type") == 3) 0 else 1 }.firstNotNullOfOrNull { ageRating(it.text("certification")) }
        val collection = o.optJSONObject("belongs_to_collection")
        return Details(
            runtime = o.optInt("runtime").takeIf { it > 0 },
            ageRating = rating,
            collectionId = collection?.optInt("id")?.takeIf { it > 0 },
            collectionName = collection?.text("name"),
            cast = castNames(o.optJSONObject("credits")?.optJSONArray("cast")),
        )
    }

    /** Reads tv/{id}?append_to_response=content_ratings,aggregate_credits. Visible for tests. */
    internal fun showDetails(o: JSONObject): Details = Details(
        ageRating = ageRating(countryEntry(o.optJSONObject("content_ratings"))?.text("rating")),
        cast = castNames(o.optJSONObject("aggregate_credits")?.optJSONArray("cast")),
    )

    /** Reads collection/{id}: its films' TMDB ids by release date, those without one last. Visible for tests. */
    internal fun collectionParts(o: JSONObject): List<Int> {
        val parts = o.optJSONArray("parts") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
            .mapNotNull { p -> p.optInt("id").takeIf { it > 0 }?.let { it to p.text("release_date").orEmpty() } }
            .sortedWith(compareBy<Pair<Int, String>> { it.second.isEmpty() }.thenBy { it.second })
            .map { it.first }
    }

    /** The United States' entry in a list of per-country results (release dates, TV ratings). */
    private fun countryEntry(o: JSONObject?): JSONObject? {
        val results = o?.optJSONArray("results") ?: return null
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == "US" }
    }

    /** An age rating worth showing: "NR" (not rated) says nothing. */
    private fun ageRating(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("NR", ignoreCase = true) }

    private fun castNames(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until minOf(arr.length(), 6)).mapNotNull { arr.optJSONObject(it)?.text("name") }

    /** One TMDB match, as cached: poster URL, release year, genre names, rating out of 10. */
    internal data class Hit(
        val poster: String?, val year: Int?, val genres: List<String>, val rating: Double?,
        /** The synopsis and a wide background image, for the title's detail page. */
        val overview: String? = null, val backdrop: String? = null,
        /** TMDB's id and whether it's a TV show, for the title page's [details]. */
        val id: Int? = null, val tv: Boolean = false,
    ) {
        fun toJson(): String = JSONObject().put("p", poster ?: "").put("y", year ?: 0)
            .put("g", JSONArray(genres)).put("r", rating ?: 0.0)
            .put("o", overview ?: "").put("b", backdrop ?: "").put("i", id ?: 0).put("tv", tv).toString()

        companion object {
            fun fromJson(s: String): Hit? = runCatching {
                val o = JSONObject(s)
                val g = o.optJSONArray("g")
                Hit(
                    // Earlier versions kept a missing picture as ".../w342null": none.
                    o.optString("p").ifBlank { null }?.takeUnless { it.endsWith("null") }, o.optInt("y").takeIf { it > 0 },
                    g?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    o.optDouble("r", 0.0).takeIf { it > 0 },
                    o.optString("o").ifBlank { null }, o.optString("b").ifBlank { null }?.takeUnless { it.endsWith("null") },
                    o.optInt("i").takeIf { it > 0 }, o.optBoolean("tv"),
                )
            }.getOrNull()
        }
    }

    private fun movie(key: String, name: String, folder: Boolean): Hit? {
        val (parsed, year) = parse(lookupName(name), stripExtension = !folder)
        val title = searchTitle(parsed)
        if (title.isBlank()) return null
        return cached("m:$title:$year") {
            val q = "search/movie?query=${enc(title)}&include_adult=false"
            best(get(key, q + (year?.let { "&year=$it" } ?: "")), MOVIE_GENRES, "release_date")
                ?: if (year != null) best(get(key, q), MOVIE_GENRES, "release_date") else null
        }
    }

    private fun tv(key: String, name: String): Hit? {
        val (parsed, year) = parse(lookupName(name), stripExtension = false)
        val title = searchTitle(parsed)
        if (title.isBlank()) return null
        return cached("t:$title:$year") { showResult(key, title, year)?.let { hit(it, TV_GENRES, "first_air_date") } }
    }

    private fun season(key: String, showName: String, folder: String): Hit? {
        val number = seasonNumber(folder) ?: return tv(key, showName)
        val (title, year) = parse(showName, stripExtension = false)
        return cached("s:$title:$year:$number") {
            val id = showResult(key, title, year)?.optInt("id") ?: return@cached null
            runCatching { get(key, "tv/$id/season/$number").text("poster_path") }.getOrNull()
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
        val pick = top.firstOrNull { it.text("poster_path") != null } ?: top.firstOrNull() ?: return null
        return hit(pick, genres, dateField)
    }

    /** Reads one TMDB search result. Visible for tests. */
    internal fun hit(o: JSONObject, genres: Map<Int, List<String>>, dateField: String): Hit {
        val ids = o.optJSONArray("genre_ids")
        val names = ids?.let { a -> (0 until a.length()).flatMap { genres[a.optInt(it)].orEmpty() } }.orEmpty().distinct()
        // A score from a handful of votes means little: leave it out of "Top rated".
        val rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 && o.optInt("vote_count") >= 20 }
        return Hit(
            o.text("poster_path")?.let { IMG + it },
            o.text(dateField)?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2100 },
            names, rating,
            o.text("overview")?.take(700),
            o.text("backdrop_path")?.let { BACKDROP + it },
            o.optInt("id").takeIf { it > 0 }, dateField == "first_air_date",
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
        s = s.replace(BRACKETS, " ") // [release group], {tags}
            .replace('.', ' ').replace('_', ' ')
            .replace(SPACES, " ").trim()
        val year = YEAR.findAll(s).firstOrNull { it.range.first > 0 }
        var title = if (year != null) s.substring(0, year.range.first) else s
        JUNK.find(title)?.let { if (it.range.first > 0) title = title.substring(0, it.range.first) }
        title = title.replace(TRAILING, "").replace("(", "").replace(")", "").trim()
        return title to year?.value
    }

    // Compiled once: every title in a library is parsed with these.
    private val BRACKETS = Regex("[\\[{].*?[\\]}]")
    private val SPACES = Regex("\\s+")
    private val TRAILING = Regex("[(\\-–:,\\s]+$")

    /** "Season 2", "S02", "Series 3" → 2 / 2 / 3; "Specials" → 0. Visible for tests. */
    fun seasonNumber(folder: String): Int? {
        val f = folder.trim().lowercase()
        if (f == "specials" || f == "extras") return 0
        Regex("(?:season|series|staffel|saison|temporada)\\s*(\\d{1,3})").find(f)?.let { return it.groupValues[1].toInt() }
        Regex("^s(\\d{1,3})$").find(f)?.let { return it.groupValues[1].toInt() }
        return null
    }

    // v2 holds full matches; the old file (posters only) is dropped so titles get their genres.
    private fun cacheFile(c: Context) = File(c.applicationContext.filesDir, "tmdb_cache4.json") // 4: adds synopsis, backdrop and TMDB id

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        File(c.applicationContext.filesDir, "tmdb_cache.json").delete()
        File(c.applicationContext.filesDir, "tmdb_cache2.json").delete()
        File(c.applicationContext.filesDir, "tmdb_cache3.json").delete()
        runCatching {
            val o = JSONObject(cacheFile(c).readText())
            o.keys().forEach { k -> cache[k] = o.getString(k) }
        }
        // Episode pictures are kept with the episodes' lengths now ("ep:"): the old entries go.
        cache.keys.removeAll { it.startsWith("e:") }
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

/** A text field; null when it's missing, blank or JSON null (Android's optString reads that as "null"). */
private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifBlank { null }
