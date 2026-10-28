package com.woodruff.tvexplorer.cloud

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.woodruff.tvexplorer.tmdb.Tmdb
import java.io.File

/** Where the Connect flow is: paste the client from a phone, sign in on a phone, or done. */
enum class SignInStep { SETUP, SIGN_IN, CONNECTED }

data class DriveState(
    val step: SignInStep = SignInStep.SETUP,
    val busy: Boolean = false,
    /** QR target for the current step: the setup form, or Google's sign-in page. */
    val qrUrl: String? = null,
    /** Phone-connection address, shown under the setup QR for typing on a computer instead. */
    val setupAddress: String? = null,
    /** Navigation stack; the last entry is what's on screen. */
    val stack: List<DrivePlace> = listOf(DrivePlace.MyDrive),
    val files: List<DriveFile> = emptyList(),
    val token: String? = null,
    val error: String? = null,
    val operation: Operation? = null,
    val message: String? = null,
    /** Filter per library ("Movies", "TV Shows"), kept while the app runs. */
    val filters: Map<String, LibraryFilter> = emptyMap(),
) {
    val place: DrivePlace get() = stack.last()
    val connected: Boolean get() = step == SignInStep.CONNECTED
    /** Inside Movies or TV Shows: poster tiles instead of file tiles. */
    val library: Boolean get() = stack.firstOrNull() is DrivePlace.Named
    /** The library's own top level gets filters; seasons and episodes don't need them. */
    val filterable: Boolean get() = library && stack.size == 1
    val filter: LibraryFilter get() = filters[stack.first().title] ?: LibraryFilter(overview = true) // Overview first, like Netflix
}

/**
 * Google Drive state, kept apart from the local-file ViewModel. Sign-in happens on the phone
 * through the always-on phone connection ([TransferService]); keys come from Settings.
 */
class DriveViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _state = MutableStateFlow(DriveState(step = initialStep()))
    val state: StateFlow<DriveState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var opJob: Job? = null
    private var prepJob: Job? = null
    private var prefetchJob: Job? = null

    /**
     * Last listing per place. Opening a place shows this instantly while a fresh copy loads
     * (stale-while-revalidate), so Movies / TV Shows / folders you've been to open with no wait.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<DrivePlace, List<DriveFile>>()

    // Declared before init: the libraries start loading from there.
    private val _recentlyAdded = MutableStateFlow<List<Pair<DrivePlace, DriveFile>>>(emptyList())
    /** Newest Movies and TV Shows entries (by Drive's modified time), for Home. */
    val recentlyAdded: StateFlow<List<Pair<DrivePlace, DriveFile>>> = _recentlyAdded.asStateFlow()
    private var artworkJob: Job? = null
    private val _artwork = MutableStateFlow<Map<String, DriveFile>>(emptyMap())
    /**
     * Movies and TV Shows entries that have a poster, by "id:" + Drive id and by film or show name
     * ([Library.showMatchKey]), so Home's Continue watching can show posters (see [artworkFor]).
     */
    val artwork: StateFlow<Map<String, DriveFile>> = _artwork.asStateFlow()

    init {
        viewModelScope.launch { CompanionHub.googleCodes.collect { (code, attempt) -> if (attempt.grant == GoogleAuth.Grant.DRIVE) finishSignIn(code, attempt) } }
        // Signed out, or the keys changed or removed — here, in Settings or from the phone: the
        // next account starts clean.
        viewModelScope.launch {
            GoogleAuth.driveSignOuts.collect {
                forgetLibrary()
                _state.value = DriveState(step = initialStep())
                prepareSignIn()
            }
        }
        // Keys saved from the phone or the Settings screen: move on to the sign-in step.
        viewModelScope.launch {
            CompanionHub.changes.collect {
                if (!_state.value.connected) { _state.update { it.copy(step = initialStep()) }; prepareSignIn() }
                // A new TMDB key changes posters: drop cached library listings and fetch again.
                else if (_state.value.library) { cache.clear(); prefetchLibraries(); load() }
            }
        }
        prefetchLibraries()
    }

    /** Warm Movies and TV Shows in the background at launch, so the sidebar opens them instantly. */
    private fun prefetchLibraries() {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            // Let the first screen settle (focus, cameras, dashboard) before the Drive and TMDB work.
            delay(4_000)
            val token = GoogleAuth.token(ctx) ?: return@launch
            // Home's Google-hosted posters need it too, before Streaming is ever opened.
            _state.update { if (it.connected && it.token != token) it.copy(token = token) else it }
            for (place in listOf(DrivePlace.Movies, DrivePlace.TvShows)) {
                runCatching { posters(token, listOf(place), parse(DriveApi.list(token, place))) }.onSuccess { cache[place] = it }
            }
            ensureActive() // cancelled (signed out, or a newer prefetch): leave Recently added alone
            updateRecentlyAdded()
        }
    }


    private fun updateRecentlyAdded() {
        _recentlyAdded.value = listOf(DrivePlace.Movies, DrivePlace.TvShows)
            .flatMap { place -> cache[place].orEmpty().map { place to it } }
            .sortedByDescending { it.second.added }
            .take(10)
        // Off the main thread: the name matching runs a few regexes per title.
        val entries = listOf(DrivePlace.Movies, DrivePlace.TvShows).flatMap { cache[it].orEmpty() }.filter { it.posterUrl != null }
        artworkJob?.cancel()
        artworkJob = viewModelScope.launch(Dispatchers.Default) {
            val index = HashMap<String, DriveFile>()
            for (f in entries) {
                index["id:" + f.id] = f
                Library.showMatchKey(f.name).takeIf { it.isNotBlank() }?.let { index.putIfAbsent(it, f) }
            }
            ensureActive()
            _artwork.value = index
        }
    }

    // Videos found not to sit under any Movies / TV Shows entry (asked again next launch).
    private val unplaced = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private var placing: Job? = null

    /**
     * Works out, once, which Movies / TV Shows entry each in-progress Drive video sits under (an
     * episode's show, a film's folder) by walking up its folders on Drive, so its poster is the
     * right one even when the file name doesn't say. Remembered in [EpisodeShows].
     */
    fun resolveShows(keys: List<String>) {
        val tops = listOf(DrivePlace.Movies, DrivePlace.TvShows).flatMap { cache[it].orEmpty() }.mapTo(HashSet()) { it.id }
        if (tops.isEmpty() || placing?.isActive == true) return
        EpisodeShows.load(ctx)
        val known = EpisodeShows.map.value
        val todo = keys.filter { it.startsWith("drive:") && it !in known && it !in unplaced && it.removePrefix("drive:") !in tops }
        if (todo.isEmpty()) return
        placing = viewModelScope.launch {
            val token = GoogleAuth.token(ctx) ?: return@launch
            // Folder -> the entry it's under (null: none); siblings in one season share the walk.
            val under = HashMap<String, String?>()
            val found = HashMap<String, String>()
            for (key in todo) {
                var id = key.removePrefix("drive:")
                val walked = ArrayList<String>()
                var entry: String? = null
                var failed = false
                // Episode -> season -> show, or film -> its folder: a few levels at most.
                for (level in 0 until 4) {
                    val parent = try { DriveApi.parentOf(token, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true; break }
                    if (parent == null) break
                    if (parent in tops) { entry = parent; break }
                    if (under.containsKey(parent)) { entry = under[parent]; break }
                    walked += parent
                    id = parent
                }
                if (failed) continue // the network failed: try again another time
                val result = entry
                walked.forEach { under[it] = result }
                if (result != null) found[key] = result else unplaced += key
            }
            if (found.isNotEmpty()) EpisodeShows.remember(ctx, found)
        }
    }

    /**
     * Movies and TV Shows whose title matches every word of [query] (accents and case ignored),
     * from the listings already loaded; for voice search.
     */
    fun searchLibraries(query: String): List<Pair<DrivePlace, DriveFile>> {
        val words = Library.normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return listOf(DrivePlace.Movies, DrivePlace.TvShows).flatMap { place ->
            cache[place].orEmpty().filter { f -> val t = Library.info(f).searchText; words.all { it in t } }.map { place to it }
        }.take(12)
    }

    private fun initialStep() = when {
        GoogleAuth.isConnected(ctx) -> SignInStep.CONNECTED
        GoogleAuth.isConfigured(ctx) -> SignInStep.SIGN_IN
        else -> SignInStep.SETUP
    }

    /** Called when the Drive screen opens: load files, or get the phone sign-in ready. */
    fun start() {
        if (_state.value.step != SignInStep.CONNECTED) _state.update { it.copy(step = initialStep()) }
        when (_state.value.step) {
            SignInStep.CONNECTED -> if (_state.value.files.isEmpty() && !_state.value.busy) load()
            else -> prepareSignIn()
        }
    }

    fun stop() { prepJob?.cancel() }

    private fun prepareSignIn() {
        prepJob?.cancel()
        prepJob = viewModelScope.launch {
            TransferService.start(ctx)
            val server = TransferService.state.first { it.running || it.error != null }
            val base = server.base
            if (base == null) {
                _state.update { it.copy(qrUrl = null, error = server.error ?: "The phone connection couldn't start.") }
                return@launch
            }
            if (!GoogleAuth.isConfigured(ctx)) {
                // Keys are entered in the phone's Settings tab (or on the TV in Settings).
                _state.update { it.copy(step = SignInStep.SETUP, qrUrl = "${server.url}#settings", setupAddress = "${server.url}#settings", error = null) }
            } else {
                val a = GoogleAuth.newAttempt(ctx, base)
                CompanionHub.expect(a)
                _state.update { it.copy(step = SignInStep.SIGN_IN, qrUrl = a.authUrl, error = null) }
            }
        }
    }

    private suspend fun finishSignIn(code: String, attempt: GoogleAuth.Attempt) {
        _state.update { it.copy(busy = true, error = null) }
        try {
            GoogleAuth.exchange(ctx, code, attempt)
            _state.update { it.copy(step = SignInStep.CONNECTED, busy = false, qrUrl = null) }
            load()
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Sign-in failed") }
            prepareSignIn()
        }
    }

    /**
     * Signs out of Google on this TV; the saved keys stay so signing in again is one QR scan. The
     * library is forgotten as the sign-in ends ([GoogleAuth.driveSignOuts]).
     */
    fun signOut() {
        viewModelScope.launch { GoogleAuth.signOut(ctx) }
    }

    /**
     * Nothing of the signed-out account's library stays: not in memory, not the folders found, not
     * the listings saved on disk (the next account would see them first, and keep seeing them
     * while its own folders fail to load). Continue watching stays: it's where the family left off,
     * and signing out and back in (an expired approval, new keys) is far likelier than a new account.
     */
    private fun forgetLibrary() {
        loadJob?.cancel()
        prefetchJob?.cancel()
        cache.clear()
        artworkJob?.cancel()
        _artwork.value = emptyMap()
        _recentlyAdded.value = emptyList()
        placing?.cancel()
        unplaced.clear()
        EpisodeShows.clear(ctx)
        DriveApi.forgetFolders()
        viewModelScope.launch(Dispatchers.IO) { ListingCache.clear(ctx) }
    }

    /**
     * Keeps [DriveState.token] current. Google's access tokens last an hour, and the screen uses
     * this one for its art and a show's episode list — after a long film it had expired.
     */
    fun refreshToken() {
        if (_state.value.step != SignInStep.CONNECTED) return
        viewModelScope.launch {
            val t = runCatching { GoogleAuth.token(ctx) }.getOrNull() ?: return@launch
            _state.update { if (it.token != t) it.copy(token = t) else it }
        }
    }

    /** Forgets the Google Cloud keys too (e.g. after creating a new OAuth client), and the library with them. */
    fun resetSetup() = GoogleAuth.resetClient(ctx)

    fun open(place: DrivePlace) {
        _state.update { it.copy(stack = it.stack + place) }
        load()
    }

    /** Jump to a top-level view (My Drive, Shared, Videos), resetting the stack. */
    fun openRoot(place: DrivePlace) {
        _state.update { it.copy(stack = listOf(place)) }
        load()
    }

    /** Returns false at the top level, so the screen can go Home instead. */
    fun back(): Boolean {
        if (_state.value.stack.size <= 1) return false
        _state.update { it.copy(stack = it.stack.dropLast(1)) }
        load()
        return true
    }

    fun refresh() = load()

    fun setFilter(filter: LibraryFilter) = _state.update { it.copy(filters = it.filters + (it.stack.first().title to filter)) }

    /**
     * Poster art for a Movies / TV Shows listing: the owner's own images first, then TMDB.
     * Movies: the movies themselves. TV Shows: shows at the top, seasons one level down.
     */
    private suspend fun posters(token: String, stack: List<DrivePlace>, files: List<DriveFile>): List<DriveFile> =
        tmdb(token, stack, DriveApi.withPosters(token, files))

    private fun tmdbMode(stack: List<DrivePlace>) = when {
        stack.first() == DrivePlace.Movies && stack.size == 1 -> Tmdb.Mode.MOVIES
        stack.first() == DrivePlace.TvShows && stack.size == 1 -> Tmdb.Mode.SHOWS
        stack.first() == DrivePlace.TvShows && stack.size == 2 -> Tmdb.Mode.SEASONS
        // Inside a season: each episode's picture.
        stack.first() == DrivePlace.TvShows && stack.size >= 3 -> Tmdb.Mode.EPISODES
        else -> Tmdb.Mode.NONE
    }

    private suspend fun tmdb(token: String, stack: List<DrivePlace>, files: List<DriveFile>): List<DriveFile> =
        Tmdb.fill(ctx, files, tmdbMode(stack), show = stack.getOrNull(1)?.title, season = stack.getOrNull(2)?.title)
            .let { withContext(Dispatchers.Default) { Library.prepare(it) } }
            .also { warmPosters(token, it) }

    /**
     * Downloads the first screenfuls of poster images into Coil's caches ahead of time, so tiles
     * appear with art instead of popping in. Google-hosted art gets the Drive token; TMDB never does.
     */
    private fun warmPosters(token: String, files: List<DriveFile>) {
        val loader = coil.Coil.imageLoader(ctx)
        files.asSequence().mapNotNull { it.posterUrl }.take(12).forEach { url ->
            val google = isGoogleUrl(url)
            loader.enqueue(
                coil.request.ImageRequest.Builder(ctx).data(url).size(320, 480) // same size as the tiles: a memory-cache hit
                    .apply { if (google) addHeader("Authorization", "Bearer $token") }
                    .build(),
            )
        }
    }

    /** Movies / TV Shows top level: titles and years parsed once, on a background thread. */
    private suspend fun parse(files: List<DriveFile>) = withContext(Dispatchers.Default) { Library.prepare(Library.withInfo(files)) }

    private fun load() {
        loadJob?.cancel()
        val place = _state.value.place
        val stack = _state.value.stack
        val library = _state.value.library
        val filterable = _state.value.filterable
        val cached = cache[place]
        _state.update { it.copy(busy = cached == null, error = null, files = cached ?: emptyList()) }
        loadJob = viewModelScope.launch {
            // Nothing in memory yet (first visit since the app started): show the saved listing at
            // once, then refresh it from Drive behind the scenes.
            var shownSaved = false
            var saved: List<DriveFile>? = null
            if (cached == null && library) {
                saved = withContext(Dispatchers.IO) { ListingCache.read(ctx, place)?.let(Library::prepare) }
                if (!saved.isNullOrEmpty()) {
                    cache[place] = saved
                    shownSaved = true
                    _state.update { if (it.place == place && it.files.isEmpty()) it.copy(files = saved, busy = false) else it }
                }
            }
            try {
                val token = GoogleAuth.token(ctx)
                if (token == null) {
                    // Approval expired or was revoked in the Google account: sign in again.
                    _state.update { it.copy(step = SignInStep.SIGN_IN, busy = false) }
                    prepareSignIn()
                    return@launch
                }
                // Show the listing (with the owner's own art) right away; TMDB posters follow.
                val files = DriveApi.list(token, place).let { if (library) DriveApi.withPosters(token, it) else it }
                    .let { if (filterable) parse(it) else it }
                // What was on screen (the copy in memory or the saved listing) already has TMDB's
                // details; this fresh listing only gets them when the lookup below finishes. Until
                // then it borrows them, so the posters, genres, rows and featured title don't
                // vanish for a moment — taking the focus with them.
                val before = if (library && tmdbMode(stack) != Tmdb.Mode.NONE) (cached ?: saved)?.associateBy { it.id } else null
                val interim = carryOver(files, before)
                cache[place] = interim
                // Only apply if the viewer is still on this place.
                _state.update { if (it.place == place) it.copy(token = token, files = interim, busy = false) else it }
                if (library && tmdbMode(stack) == Tmdb.Mode.NONE) withContext(Dispatchers.IO) { ListingCache.write(ctx, place, files) }
                if (library && tmdbMode(stack) != Tmdb.Mode.NONE) {
                    // A lookup that failed this time (TMDB unreachable) keeps what was found before.
                    val withTmdb = carryOver(tmdb(token, stack, files), before)
                    cache[place] = withTmdb
                    _state.update { if (it.place == place) it.copy(files = withTmdb) else it }
                    withContext(Dispatchers.IO) { ListingCache.write(ctx, place, withTmdb) }
                    if (place == DrivePlace.Movies || place == DrivePlace.TvShows) updateRecentlyAdded()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // With a cached list on screen, keep showing it rather than an error.
                _state.update { if (cached != null || shownSaved) it.copy(busy = false) else it.copy(busy = false, error = explain(e)) }
            }
        }
    }

    /**
     * [fresh] with what [before] knew about the same titles (same id and name): TMDB's details
     * where the fresh copy has none, and a poster where it has none.
     */
    private fun carryOver(fresh: List<DriveFile>, before: Map<String, DriveFile>?): List<DriveFile> =
        if (before.isNullOrEmpty()) fresh else fresh.map { f ->
            val old = before[f.id]
            if (old == null || old.name != f.name) f
            else f.copy(
                info = if (!f.hasTmdb && old.hasTmdb) old.info else f.info,
                posterUrl = f.posterUrl ?: old.posterUrl,
            )
        }

    private val DriveFile.hasTmdb: Boolean
        get() = info?.let { it.tmdbId != null || it.genres.isNotEmpty() || it.rating != null } == true

    fun download(file: DriveFile) {
        if (opJob?.isActive == true) return say("A download is already running")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Woodruff Family").apply { mkdirs() }
        val dest = FileOperations.uniqueTarget(dir, file.name.replace('/', '_'))
        _state.update { it.copy(operation = Operation("Downloading")) }
        var last = 0L
        opJob = viewModelScope.launch {
            try {
                val token = GoogleAuth.token(ctx) ?: error("Signed out of Google Drive")
                DriveApi.download(token, file, dest) { p ->
                    val now = System.currentTimeMillis()
                    if (now - last > 150) { last = now; _state.update { it.copy(operation = Operation("Downloading", p)) } }
                }
                say("Saved to Downloads/Woodruff Family/${dest.name}")
            } catch (e: CancellationException) {
                say("Download cancelled")
            } catch (e: Exception) {
                say(explain(e))
            } finally {
                _state.update { it.copy(operation = null) }
            }
        }
    }

    fun cancelOperation() { opJob?.cancel() }
    fun say(text: String) = _state.update { it.copy(message = text) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun explain(e: Exception): String = when {
        e is java.net.UnknownHostException -> "No internet connection."
        else -> e.message ?: "Something went wrong talking to Google Drive."
    }
}

/**
 * The Movies / TV Shows entry whose poster fits something being watched: the entry it's known to
 * sit under ([shows], from [EpisodeShows]: an episode's show, a film's folder), else by its own
 * Drive id (a film at the top of Movies), else by its film or show name.
 */
fun artworkFor(index: Map<String, DriveFile>, key: String, title: String, shows: Map<String, String>): DriveFile? =
    shows[key]?.let { index["id:$it"] }
        ?: (if (key.startsWith("drive:")) index["id:" + key.removePrefix("drive:")] else null)
        ?: Library.showMatchKey(title).takeIf { it.isNotBlank() }?.let { index[it] }
