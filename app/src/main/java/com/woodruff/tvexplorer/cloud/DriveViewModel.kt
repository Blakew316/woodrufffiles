package com.woodruff.tvexplorer.cloud

import android.app.Application
import android.os.Environment
import android.view.Choreographer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.ui.components.BACK_ONLINE_MS
import com.woodruff.tvexplorer.ui.components.OFFLINE_GRACE_MS
import com.woodruff.tvexplorer.util.NetworkWatch
import com.woodruff.tvexplorer.util.backoffMs
import com.woodruff.tvexplorer.util.isNetworkFailure
import java.io.File

/** Where the Connect flow is: paste the client from a phone, sign in on a phone, or done. */
enum class SignInStep { SETUP, SIGN_IN, CONNECTED }

/**
 * How the tries to reach Google Drive are going, as Streaming says it: in its search field over the
 * saved library, or a calm page when nothing is saved (see the offline-states spec).
 */
sealed interface DriveStatus {
    /** Reached it: what's on screen is current. */
    data object Online : DriveStatus

    /**
     * Couldn't get through since [since] (no network yet after standby, a timeout, Google's own outage).
     * The saved copy stays on screen, and it tries again by itself, at once when the network comes back.
     * Never the sign-in QR code: the sign-in is fine. [google]: the TV's network works and Google didn't
     * answer ("Can't reach Google"); otherwise "Offline". [shown]: [OFFLINE_GRACE_MS] has passed and the
     * screen says so; until then what was there stays, silently (most wake-ups reconnect sooner).
     */
    data class Offline(val since: Long, val google: Boolean, val shown: Boolean = false) : DriveStatus

    /** A retry under way after the offline words showed ([was]: what they said, and say again if it fails). */
    data class Reconnecting(val was: Offline) : DriveStatus

    /** A retry got through after the offline words showed over the saved library: "Back online" for [BACK_ONLINE_MS] from [at]. */
    data class BackOnline(val at: Long) : DriveStatus

    /**
     * Google refused this TV's keys ("Keys not accepted"): nothing refreshes until they're fixed, and
     * it never clears by itself, so no retrying and no grace. [message] says what to fix.
     */
    data class Refused(val message: String) : DriveStatus
}

/** Not getting through: offline (said yet or not), or retrying after it. */
val DriveStatus.down: Boolean get() = this is DriveStatus.Offline || this is DriveStatus.Reconnecting

/** The screen says it isn't getting through: the offline words show, or a retry after them. */
val DriveStatus.said: Boolean get() = (this is DriveStatus.Offline && shown) || this is DriveStatus.Reconnecting

/** A retry after the offline words, asked for or not, shows "Reconnecting…" only once it has taken this long (a quick failure doesn't flicker). */
internal const val RECONNECTING_AFTER_MS = 600L

/** A retry asked for (Try again, Refresh) shows "Trying…" at least this long, so the press is seen to do something. */
internal const val TRYING_AT_LEAST_MS = 800L

/**
 * The time the connection's status is kept in: the TV's uptime, which no clock change (a TV setting
 * its time after a power cut) moves, so the grace and "Back online" always last as long as they should.
 */
private fun clock(): Long = android.os.SystemClock.elapsedRealtime()

/**
 * The pause before the next try after [tries] failed ones in a row, unless the network comes back
 * first (then at once): 5 s, 15 s, 30 s, then every minute while the screen is on show.
 */
internal fun retryPause(tries: Int): Long = when (tries) {
    0 -> 5_000L
    1 -> 15_000L
    2 -> 30_000L
    else -> 60_000L
}

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
    /** How the last try to reach Google Drive went (offline: what's shown is the saved copy). */
    val status: DriveStatus = DriveStatus.Online,
    /**
     * Where the listing limit cut what's shown ([DriveApi.LIBRARY_LIMIT] for a library, else
     * [DriveApi.LIST_LIMIT]) because Drive had more; 0 when it's all there. The screen says so.
     */
    val cutAt: Int = 0,
    /** Google ended the Drive sign-in (not a first sign-in): the sign-in panel asks to sign in *again*. */
    val ended: Boolean = false,
) {
    val place: DrivePlace get() = stack.last()
    val connected: Boolean get() = step == SignInStep.CONNECTED
    /** Inside Movies or TV Shows: poster tiles instead of file tiles. */
    val library: Boolean get() = stack.firstOrNull() is DrivePlace.Named
    /** The library's own top level gets filters; seasons and episodes don't need them. */
    val filterable: Boolean get() = library && stack.size == 1
    val filter: LibraryFilter get() = filters[stack.first().title] ?: LibraryFilter(overview = true) // Overview first, like Netflix
}

/** What one try at listing a Drive place came to. */
internal sealed interface Fetch {
    /** Listed: the fresh copy is on screen. */
    data object Done : Fetch
    /** The sign-in has ended (Google expired or revoked it, or there's none): the QR code. */
    data object SignedOut : Fetch
    /** Couldn't get through to Google: keep what's shown and try again (see [DriveStatus.Offline]). */
    data object Offline : Fetch
    /** Google refused this TV's keys (see [DriveStatus.Refused]). */
    data class Refused(val message: String) : Fetch
    /** Anything else (no Movies folder, a 404…): [message] in place of a list when nothing's shown. */
    data class Failed(val message: String) : Fetch
}

/** [token]'s answer as a [Fetch], when there's no token to list with. Null for a token. */
internal fun fetchFor(token: GoogleAuth.TokenResult): Fetch? = when (token) {
    is GoogleAuth.TokenResult.Ok -> null
    GoogleAuth.TokenResult.SignedOut -> Fetch.SignedOut
    is GoogleAuth.TokenResult.Offline -> Fetch.Offline
    is GoogleAuth.TokenResult.Rejected -> Fetch.Refused(token.message)
}

/**
 * [e], thrown while listing, as a [Fetch]. The network failing is [Fetch.Offline], however it shows
 * (a timeout, a name that won't resolve, a dropped connection): never a reason to sign in again.
 * [explain] words anything else.
 */
internal fun fetchFailure(e: Throwable, explain: (Throwable) -> String): Fetch = when {
    e is GoogleAuth.AuthException.SignedOut -> Fetch.SignedOut
    e is GoogleAuth.AuthException.Rejected -> Fetch.Refused(e.message.orEmpty())
    isNetworkFailure(e) -> Fetch.Offline
    // Drive answered that it's busy, or still wouldn't take a token just renewed, or with something
    // that can't be read (cut off part-way): Google isn't answering properly, so it's "Can't reach
    // Google", tried again by itself.
    e is DriveHttpException && e.unreachable -> Fetch.Offline
    e is org.json.JSONException -> Fetch.Offline
    else -> Fetch.Failed(explain(e))
}

/**
 * The state once a try came to [outcome] at [now] ([google]: the TV's network works, for a failure).
 * [here]: the viewer is still on the place it listed (the place's own loading and error only change
 * then; the connection's [DriveState.status] always). The listing itself is put on screen as it
 * arrives, not here.
 */
internal fun DriveState.after(outcome: Fetch, here: Boolean, now: Long = System.currentTimeMillis(), google: Boolean = true): DriveState = when (outcome) {
    Fetch.Done -> copy(status = status.reached(saved = files.isNotEmpty(), now = now))
    // Only for a sign-in that really ended: the QR code takes over, the library is kept for after.
    Fetch.SignedOut -> copy(step = SignInStep.SIGN_IN, busy = false, status = DriveStatus.Online)
    // What's on screen stays, with no error over it. Within the grace nothing is said, and with
    // nothing saved it goes on saying "Loading" until the offline page may show.
    Fetch.Offline -> {
        val st = status.failed(now, google)
        copy(status = st, busy = if (here) files.isEmpty() && !st.said else busy, error = if (here) null else error)
    }
    is Fetch.Refused -> copy(status = DriveStatus.Refused(outcome.message), busy = if (here) false else busy, error = if (here) null else error)
    // Google answered, with something else (no Movies folder, a 404): not offline any more. With a
    // list on screen, keep showing it rather than an error.
    is Fetch.Failed -> {
        val st = status.reached(saved = files.isNotEmpty(), now = now)
        if (!here) copy(status = st)
        else copy(status = if (st.down) DriveStatus.Online else st, busy = false, error = if (files.isEmpty()) outcome.message else null)
    }
}

/** [this] once a try failed to get through at [now]: offline since the first failure, the words as they were. */
internal fun DriveStatus.failed(now: Long, google: Boolean): DriveStatus = when (this) {
    is DriveStatus.Offline -> copy(google = google)
    is DriveStatus.Reconnecting -> was.copy(google = google)
    else -> DriveStatus.Offline(since = now, google = google)
}

/**
 * [this] once Google answered at [now], [saved]: the saved library is what's on screen. "Back online"
 * only after the offline words showed over it; with nothing saved the offline page stays up until the
 * listing replaces it (the posters are the answer), and within the grace nothing was said.
 */
internal fun DriveStatus.reached(saved: Boolean, now: Long): DriveStatus = when {
    this is DriveStatus.BackOnline -> this
    said && saved -> DriveStatus.BackOnline(now)
    said -> this
    else -> DriveStatus.Online
}

/** [this] once [OFFLINE_GRACE_MS] may have passed by [now]: the offline words show if it's still offline. */
internal fun DriveStatus.graceOver(now: Long): DriveStatus =
    if (this is DriveStatus.Offline && !shown && now - since >= com.woodruff.tvexplorer.ui.components.OFFLINE_GRACE_MS) copy(shown = true) else this

/**
 * Google Drive state, kept apart from the local-file ViewModel. Sign-in happens on the phone
 * through the always-on phone connection ([TransferService]); keys come from Settings.
 */
class DriveViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _state = MutableStateFlow(initialStep().let { DriveState(step = it, ended = it != SignInStep.CONNECTED && endedHere()) })
    val state: StateFlow<DriveState> = _state.asStateFlow()
    /**
     * [state] as the Drive screen shows it: without the download progress and the messages (AppRoot
     * shows those). Progress changes every 150 ms while a file downloads; on [state] each change
     * recomposed the whole screen of posters, though nothing on it had changed.
     */
    val screen: StateFlow<DriveState> = _state.map { it.copy(operation = null, message = null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.copy(operation = null, message = null))
    private var loadJob: Job? = null
    private var opJob: Job? = null
    private var prepJob: Job? = null
    private var prefetchJob: Job? = null

    /**
     * Last listing per place. Opening a place shows this instantly while a fresh copy loads
     * (stale-while-revalidate), so Movies / TV Shows / folders you've been to open with no wait.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<DrivePlace, List<DriveFile>>()

    /**
     * When each library was last listed in full — Drive, its posters, TMDB, saved — or, before that
     * this run, when its saved copy was. Opening one listed less than [FRESH_MS] ago lists nothing.
     */
    private val listedAt = java.util.concurrent.ConcurrentHashMap<DrivePlace, Long>()

    /** Where the listing limit cut each place's last listing, for the ones it did ([DriveState.cutAt]). */
    private val cuts = java.util.concurrent.ConcurrentHashMap<DrivePlace, Int>()

    /**
     * Each library's refresh, shared by the screen, both tabs and the warm-up at launch. Off the main
     * thread (a big library's titles are compared and copied as they arrive), and apart from the
     * screen's own job: switching tabs doesn't cancel a listing halfway. Signing out does.
     */
    private val refreshes = LibraryRefreshes<DrivePlace>(CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Default))

    /** The libraries whose saved copy's art was loaded ahead this run (once each is enough). */
    private val warmedSaved = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<DrivePlace, Boolean>())

    /**
     * Bumped by signing out ([forgetLibrary]): a refresh still under way then (on its own thread,
     * a step from its next check for being cancelled) keeps nothing it finds. Guarded by [libraryLock].
     */
    private var generation = 0
    private val libraryLock = Any()

    /** [block], unless the library was forgotten (signed out) since [gen]: then the refresh ends here. */
    private inline fun <T> kept(gen: Int, block: () -> T): T = synchronized(libraryLock) {
        if (gen != generation) throw CancellationException("Signed out meanwhile")
        block()
    }

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

    /** Whether the Drive screen is on show (and Woodruff on screen): only then does a listing retry. */
    private val onScreen = MutableStateFlow(false)

    /**
     * Ticks when a listing of another place got through to Google (the other library warming, say):
     * the place on screen, waiting to try again, tries at once rather than at the end of its pause.
     */
    private val reachedTicks = MutableStateFlow(0)

    /** The grace timer under way, for the failure that began at [graceFor] (main thread only). */
    private var graceJob: Job? = null
    private var graceFor = 0L

    init {
        // Retries wait on the network coming back (the first start of it registers its callback).
        NetworkWatch.start(app)
        viewModelScope.launch { CompanionHub.googleCodes.collect { (code, attempt) -> if (attempt.grant == GoogleAuth.Grant.DRIVE) finishSignIn(code, attempt) } }
        // Signed out, or the keys changed or removed — here, in Settings or from the phone: the
        // next account starts clean.
        viewModelScope.launch {
            GoogleAuth.driveSignOuts.collect {
                forgetLibrary()
                // A new account, or new keys: a first sign-in, not "again".
                _state.value = DriveState(step = initialStep())
                prepareSignIn()
            }
        }
        // Google ended the sign-in (found out by anything renewing it: the player, the prefetch):
        // the QR code, at once rather than at the next listing, asking to sign in again. The library
        // is kept for after.
        viewModelScope.launch {
            GoogleAuth.expirations.collect { grant -> if (grant == GoogleAuth.Grant.DRIVE) askToSignIn(ended = true) }
        }
        // Keys saved from the phone or the Settings screen: move on to the sign-in step. Signed in,
        // nothing here depends on the other settings this announces (mail, the doorbell, a TV's
        // name…): reloading both libraries for each of them blanked Movies and listed everything again.
        viewModelScope.launch {
            CompanionHub.changes.collect {
                if (!_state.value.connected) {
                    // Still signed in (a sign-in step shown in error, or the Keystore just woke up):
                    // back to the library rather than another QR code.
                    val step = initialStep()
                    _state.update { it.copy(step = step) }
                    if (step == SignInStep.CONNECTED) load() else prepareSignIn()
                }
            }
        }
        // A new TMDB key (here, from the phone or a linked TV) changes posters and details only:
        // each library's listing as it is, looked up again; no Drive pass, nothing blanked.
        viewModelScope.launch {
            Tmdb.keyChanges.collect {
                for (place in LIBRARIES) if (cache[place] != null) refreshes.tmdbPass(place) { retag(place) }
                // A show's seasons or episodes on screen: their pictures, listed again (a folder is small).
                val s = _state.value
                if (s.connected && s.library && s.stack.size > 1) load()
            }
        }
        prefetchLibraries(seed = true)
    }

    /** The Drive screen is on show ([on]) or not: the screen's start and stop, and Woodruff's. */
    fun showing(on: Boolean) { onScreen.value = on }

    /**
     * The sign-in has ended: the QR code takes over (once, however many find out). [ended]: Google
     * ended it (known), so the panel asks to sign in again; null: look it up.
     */
    private fun askToSignIn(ended: Boolean? = null) {
        if (_state.value.step != SignInStep.CONNECTED) return
        _state.update { it.after(Fetch.SignedOut, here = true).copy(ended = ended ?: it.ended) }
        prepareSignIn()
    }

    /**
     * Warm Movies and TV Shows in the background, so Home has their posters and Recently added,
     * search finds them and the sidebar opens them instantly. [seed] (at launch) starts from the
     * copies saved last time, read just after the first frame, so all that works at once rather
     * than after the refresh. A library listed moments ago (its saved copy too, before a restart)
     * isn't listed again; one the Drive screen is listing already is joined, not listed twice.
     */
    private fun prefetchLibraries(seed: Boolean = false) {
        prefetchJob?.cancel()
        // Part of this job, so signing out (which cancels it) can't bring the old library back.
        prefetchJob = viewModelScope.launch {
            if (seed && _state.value.connected) {
                afterNextFrame()
                val saved = withContext(Dispatchers.IO) {
                    LIBRARIES.associateWith { place -> ListingCache.read(ctx, place)?.also { Library.prepare(it.files); learnMatchKeys(it.files) } }
                }
                // Not over a fresher copy: a library opened meanwhile has loaded its own.
                saved.forEach { (place, s) -> if (s != null && s.files.isNotEmpty()) keepSaved(place, s) }
                if (saved.values.any { !it?.files.isNullOrEmpty() }) updateRecentlyAdded()
            }
            // Let the first screen settle (focus, cameras, dashboard) before the Drive and TMDB work.
            delay(4_000)
            // Started before the network was up (a TV turned on, or woken from standby): tried again
            // the moment it's back, and after a growing pause (15 s up to 15 min) if it never says.
            var tries = 0
            while (true) {
                val mark = NetworkWatch.comebacks.value
                val now = System.currentTimeMillis()
                val outcomes = LIBRARIES.filter { !isFresh(listedAt[it], now) }.map { refreshLibrary(it) }.awaitAll()
                // Signed out, or the keys refused: the Drive screen says so, nothing to warm. Listed,
                // or Drive answered something else (no TV Shows folder): done until the next visit.
                if (outcomes.none { it == Fetch.Offline } || outcomes.any { it == Fetch.SignedOut || it is Fetch.Refused }) return@launch
                val cameBack = NetworkWatch.awaitComeback(mark, backoffMs(tries, 15_000, 15 * 60_000))
                tries = if (cameBack) 0 else tries + 1
            }
        }
    }

    /**
     * [saved], read from disk, as [place]'s listing — unless one is in memory already (a fresher
     * copy, loaded meanwhile). Its time counts as when it was listed, so a library listed just
     * before Woodruff restarted isn't listed again at once.
     */
    private fun keepSaved(place: DrivePlace, saved: ListingCache.Saved) {
        if (cache.putIfAbsent(place, saved.files) != null) return
        if (saved.listedAt > 0 && place is DrivePlace.Named) listedAt.putIfAbsent(place, saved.listedAt)
        if (saved.cutAt > 0) cuts.putIfAbsent(place, saved.cutAt)
    }

    /**
     * Returns just after the next frame is drawn (at launch: the first one), so the work after it
     * doesn't hold up the first screen; after a second at most (no frames while the screen is off).
     */
    private suspend fun afterNextFrame() {
        withTimeoutOrNull(1_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                choreographer.postFrameCallback(callback)
                cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            }
        }
    }

    /**
     * The show-match keys of a library's folders and of what's in progress, worked out now, on this
     * background thread: the Overview's Continue watching row then only looks them up ([matchKeyOf]).
     */
    private fun learnMatchKeys(files: List<DriveFile>) {
        for (f in files) if (f.isFolder) matchKeyOf(f.name)
        for (r in RecentStore(ctx).all()) matchKeyOf(r.title)
    }


    // Synchronized: Movies' and TV Shows' refreshes finish on their own threads, maybe at once.
    @Synchronized
    private fun updateRecentlyAdded() {
        _recentlyAdded.value = LIBRARIES
            .flatMap { place -> cache[place].orEmpty().map { place to it } }
            .sortedByDescending { it.second.added }
            .take(10)
        // Off the main thread: the name matching runs a few regexes per title.
        val entries = LIBRARIES.flatMap { cache[it].orEmpty() }.filter { it.posterUrl != null }
        artworkJob?.cancel()
        artworkJob = viewModelScope.launch(Dispatchers.Default) {
            val index = HashMap<String, DriveFile>()
            for (f in entries) {
                index["id:" + f.id] = f
                matchKeyOf(f.name).takeIf { it.isNotBlank() }?.let { index.putIfAbsent(it, f) }
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

    /** Whether Google ended this TV's Drive sign-in (so signing in is "again"); read with the sign-in step, before its panel shows. */
    private fun endedHere() = GoogleAuth.endedAt(ctx, GoogleAuth.Grant.DRIVE) != null

    /** Called when the Drive screen opens: load files, or get the phone sign-in ready. */
    fun start() {
        if (_state.value.step != SignInStep.CONNECTED) _state.update { initialStep().let { step -> it.copy(step = step, ended = step != SignInStep.CONNECTED && endedHere()) } }
        when (_state.value.step) {
            // Keys Google refused aren't retried by themselves; coming back to the screen (after
            // fixing them, say) tries once more.
            SignInStep.CONNECTED -> _state.value.let { if ((it.files.isEmpty() && !it.busy) || it.status is DriveStatus.Refused) load() }
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
                _state.update { it.copy(step = SignInStep.SETUP, qrUrl = "${server.url}#settings", setupAddress = "${server.url}#settings", error = null, ended = false) }
            } else {
                val a = GoogleAuth.newAttempt(ctx, base)
                CompanionHub.expect(a)
                // Whether Google ended the last sign-in (then it's "sign in again"), even after a restart.
                val ended = withContext(Dispatchers.IO) { GoogleAuth.endedAt(ctx, GoogleAuth.Grant.DRIVE) != null }
                // Never over a library that's (back) on screen: a sign-in that finished meanwhile.
                _state.update { if (it.connected) it else it.copy(step = SignInStep.SIGN_IN, qrUrl = a.authUrl, error = null, ended = ended || it.ended) }
            }
        }
    }

    private suspend fun finishSignIn(code: String, attempt: GoogleAuth.Attempt) {
        _state.update { it.copy(busy = true, error = null) }
        try {
            GoogleAuth.exchange(ctx, code, attempt)
            _state.update { it.copy(step = SignInStep.CONNECTED, busy = false, qrUrl = null, ended = false, status = DriveStatus.Online) }
            load()
            // The other library too, so Home's Recently added and search have both without a visit.
            prefetchLibraries()
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
        synchronized(libraryLock) {
            generation++
            cache.clear()
            listedAt.clear()
            cuts.clear()
        }
        refreshes.cancelAll()
        warmedSaved.clear()
        artworkJob?.cancel()
        _artwork.value = emptyMap()
        _recentlyAdded.value = emptyList()
        placing?.cancel()
        unplaced.clear()
        EpisodeShows.clear(ctx)
        DriveApi.forgetFolders()
        matchKeys.clear()
        // The saved listings, and the title pages' folder listings with them.
        viewModelScope.launch(Dispatchers.IO) { ListingCache.clear(ctx); FolderCache.clear(ctx) }
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

    /**
     * Filter → Refresh, and Try again on the offline page: listed again now, however recently it was.
     * Offline, it says "Reconnecting…" (the page's button "Trying…") while it tries; a press while a
     * try is under way does nothing.
     */
    fun refresh() {
        if (_state.value.status is DriveStatus.Reconnecting) return
        load(force = true, asked = true)
    }

    fun setFilter(filter: LibraryFilter) = _state.update { it.copy(filters = it.filters + (it.stack.first().title to filter)) }

    private fun tmdbMode(stack: List<DrivePlace>) = when {
        stack.first() == DrivePlace.Movies && stack.size == 1 -> Tmdb.Mode.MOVIES
        stack.first() == DrivePlace.TvShows && stack.size == 1 -> Tmdb.Mode.SHOWS
        stack.first() == DrivePlace.TvShows && stack.size == 2 -> Tmdb.Mode.SEASONS
        // Inside a season: each episode's picture.
        stack.first() == DrivePlace.TvShows && stack.size >= 3 -> Tmdb.Mode.EPISODES
        else -> Tmdb.Mode.NONE
    }

    private suspend fun tmdb(stack: List<DrivePlace>, files: List<DriveFile>): List<DriveFile> =
        Tmdb.fill(ctx, files, tmdbMode(stack), show = stack.getOrNull(1)?.title, season = stack.getOrNull(2)?.title)
            .let { withContext(Dispatchers.Default) { Library.prepare(it) } }

    /**
     * Downloads what a listing shows first into Coil's caches ahead of time, so it appears with its
     * art instead of popping in. Movies and TV Shows open on the Overview: its featured picture, at
     * the banner's size, and the first posters of its top two rows. A filtered grid or a show's
     * seasons: the first posters in order. Google-hosted art gets the Drive token; TMDB never does.
     */
    private fun warm(token: String, stack: List<DrivePlace>, files: List<DriveFile>) {
        val top = stack.size == 1 && stack.first() is DrivePlace.Named
        val filter = _state.value.filters[stack.first().title] ?: LibraryFilter(overview = true)
        viewModelScope.launch(Dispatchers.Default) {
            val (backdrop, posters) = if (top && filter.overview) {
                // The rows as LibraryRows makes them: Continue watching, then Recently added and the rest.
                EpisodeShows.load(ctx)
                val watching = Library.continuing(files, RecentStore(ctx).continueWatching(), EpisodeShows.map.value, ::matchKeyOf)
                Library.overviewArt(files, watching.take(20).map { it.first }, Library.today())
            } else {
                null to (if (top) Library.apply(files, filter) else files).asSequence().mapNotNull { it.posterUrl }.take(12).toList()
            }
            val loader = coil.Coil.imageLoader(ctx)
            fun request(url: String, width: Int, height: Int) = coil.request.ImageRequest.Builder(ctx).data(url).size(width, height)
                .apply { if (isGoogleUrl(url)) addHeader("Authorization", "Bearer $token") }
                .build()
            // The same sizes the banner and the tiles ask for: memory-cache hits.
            backdrop?.let { loader.enqueue(request(it, 1280, 720)) }
            posters.forEach { loader.enqueue(request(it, 320, 480)) }
        }
    }

    /** Movies / TV Shows top level: titles, years and match keys worked out once, on a background thread. */
    private suspend fun parse(files: List<DriveFile>) = withContext(Dispatchers.Default) {
        Library.prepare(Library.withInfo(files)).also { learnMatchKeys(it) }
    }

    /**
     * Shows the place on top of the stack: what's kept of it at once, then a fresh listing. A
     * library (Movies, TV Shows) listed less than [FRESH_MS] ago is only shown ([force]: Refresh
     * lists it anyway), and its listing is the shared one ([refreshLibrary]): leaving it meanwhile
     * doesn't stop it. [asked]: someone asked for it (Refresh, Try again), so a retry after the
     * offline words shows at once.
     */
    private fun load(force: Boolean = false, asked: Boolean = false) {
        loadJob?.cancel()
        val place = _state.value.place
        val stack = _state.value.stack
        val library = _state.value.library
        val cached = cache[place]
        // The status stays as it was until this try says otherwise: offline stays offline (no
        // flicker of the words) while the next place loads from what's saved, and the offline page
        // stays up while it tries again rather than flashing "Loading".
        _state.update { it.copy(busy = cached == null && !it.status.said, error = null, files = cached ?: emptyList(), cutAt = cuts[place] ?: 0) }
        loadJob = viewModelScope.launch {
            // Nothing in memory yet (first visit since the app started): show the saved listing at
            // once, then refresh it from Drive behind the scenes.
            if (cached == null && library) {
                val saved = withContext(Dispatchers.IO) {
                    ListingCache.read(ctx, place)?.also { Library.prepare(it.files); if (place is DrivePlace.Named) learnMatchKeys(it.files) }
                }
                if (saved != null && saved.files.isNotEmpty()) {
                    keepSaved(place, saved)
                    val shown = cache[place] ?: saved.files
                    _state.update { if (it.place == place && it.files.isEmpty()) it.copy(files = shown, busy = false, cutAt = cuts[place] ?: 0) else it }
                }
            }
            // Listed in full a moment ago (switching tabs, back from a show): as it is. Not while the
            // header says offline: then it's listed again, so the note goes once Google answers.
            if (place is DrivePlace.Named && !needsListing(force, _state.value.status, listedAt[place], System.currentTimeMillis())) {
                _state.update { if (it.place == place && it.busy && it.files.isNotEmpty()) it.copy(busy = false) else it }
                return@launch
            }
            // Offline (a TV just back from standby, its Wi-Fi still reconnecting): what's on screen
            // stays, and it tries again the moment the network is back, or another listing gets
            // through — or after a growing pause (5 s, 15 s, 30 s, then every minute) if nothing
            // says — while the Drive screen is on show.
            var tries = 0
            var byHand = asked
            while (true) {
                val mark = NetworkWatch.comebacks.value
                val tick = reachedTicks.value
                val started = clock()
                // A try after the offline words showed says "Reconnecting…": at once when asked for,
                // otherwise only once it takes a moment (a failure as quick as no network doesn't flicker).
                val trying = launch {
                    if (!byHand) delay(RECONNECTING_AFTER_MS)
                    _state.update { s -> (s.status as? DriveStatus.Offline)?.takeIf { it.shown }?.let { s.copy(status = DriveStatus.Reconnecting(it)) } ?: s }
                }
                val outcome = if (place is DrivePlace.Named) refreshLibrary(place).await() else fetch(place, stack, library)
                trying.cancel()
                // Asked for, and no good news: "Trying…" stays long enough to be seen.
                if (byHand && outcome != Fetch.Done) delay(TRYING_AT_LEAST_MS - (clock() - started))
                byHand = false
                // Only a sign-in that really ended (never a network failure) brings the QR code.
                if (outcome == Fetch.SignedOut) {
                    askToSignIn(ended = withContext(Dispatchers.IO) { GoogleAuth.endedAt(ctx, GoogleAuth.Grant.DRIVE) != null })
                    return@launch
                }
                _state.update { it.after(outcome, here = it.place == place, now = clock(), google = NetworkWatch.online.value) }
                afterStatusChange()
                if (outcome != Fetch.Offline) return@launch
                val cameBack = withTimeoutOrNull(retryPause(tries)) {
                    combine(NetworkWatch.comebacks, reachedTicks) { c, r -> c != mark || r != tick }.first { it }
                } != null
                tries = if (cameBack) 0 else tries + 1
                onScreen.first { it }
            }
        }
    }

    /**
     * Whatever a status change needs next: the offline words once the grace is over (a timer, not a
     * loop), and "Back online" going after [BACK_ONLINE_MS]. Each timer only acts if the status is
     * still the one it was set for. Any thread.
     */
    private fun afterStatusChange() {
        when (val st = _state.value.status) {
            is DriveStatus.Offline -> if (!st.shown) viewModelScope.launch(Dispatchers.Main.immediate) {
                if (graceJob?.isActive == true && graceFor == st.since) return@launch
                graceFor = st.since
                graceJob = viewModelScope.launch {
                    delay(st.since + OFFLINE_GRACE_MS - clock())
                    _state.update { s ->
                        val now = s.status
                        // Still that same failure: the words (or, with nothing saved, the page) show.
                        if (now is DriveStatus.Offline && now.since == st.since) {
                            s.copy(status = now.copy(shown = true), busy = if (s.files.isEmpty()) false else s.busy)
                        } else s
                    }
                }
            }
            is DriveStatus.BackOnline -> viewModelScope.launch {
                delay(st.at + BACK_ONLINE_MS - clock())
                _state.update { if (it.status == st) it.copy(status = DriveStatus.Online) else it }
            }
            else -> Unit
        }
    }

    /**
     * Google answered a listing of [place]: on screen, the status catches up at once ("Back online"
     * over the saved library) rather than when the posters are done; elsewhere, the place on screen
     * tries again now. Any thread.
     */
    private fun reached(place: DrivePlace) {
        var elsewhere = false
        _state.update { s ->
            if (s.place != place) { elsewhere = true; s }
            else s.copy(status = s.status.reached(saved = s.files.isNotEmpty(), now = clock()))
        }
        if (elsewhere) reachedTicks.update { it + 1 } else afterStatusChange()
    }

    /** [place]'s refresh: the listing of it under way, or a new one (see [LibraryRefreshes]). */
    private fun refreshLibrary(place: DrivePlace.Named): Deferred<Fetch> = refreshes.listing(place) { listLibrary(place) }

    /**
     * [files] as [place]'s listing from now on ([cutAt]: where the listing limit cut it), unless
     * signed out since [gen]; and on screen if the viewer is there. [fromDrive]: Drive itself just
     * answered (not only TMDB), so an offline page there gives way to it.
     */
    private fun publish(gen: Int, place: DrivePlace, files: List<DriveFile>, cutAt: Int, token: String? = null, fromDrive: Boolean = true) {
        kept(gen) {
            cache[place] = files
            if (cutAt > 0) cuts[place] = cutAt else cuts.remove(place)
        }
        // The offline page (nothing was saved) gives way to the listing itself: no "Back online".
        _state.update {
            if (it.place == place) it.copy(files = files, busy = false, error = null, cutAt = cutAt, token = token ?: it.token, status = if (fromDrive && it.status.down) DriveStatus.Online else it.status)
            else it
        }
    }

    /**
     * Lists a library (Movies or TV Shows) from Drive in full: its entries, the owner's posters, then
     * TMDB's details, each put on screen as it arrives (if the viewer is there). Only a listing that
     * went all the way is saved and counts as fresh ([listedAt]): one cut short keeps what it had in
     * memory, and is listed again next time. Unchanged, the saved copy isn't written again. A token
     * Drive won't take (401: the TV's clock was off when it was saved, or access was just revoked)
     * is renewed once and the listing tried again ([renewed]).
     */
    private suspend fun listLibrary(place: DrivePlace.Named, renewed: Boolean = false): Fetch {
        // Read first: a sign-out from here on leaves nothing of this listing behind, in memory or saved.
        val gen = synchronized(libraryLock) { generation }
        val disk = ListingCache.generation()
        val result = GoogleAuth.tokenResult(ctx)
        fetchFor(result)?.let { return it }
        val token = (result as GoogleAuth.TokenResult.Ok).token
        // Home's Google-hosted posters need it too, before Streaming is ever opened.
        _state.update { if (it.connected && it.token != token) it.copy(token = token) else it }
        val stack = listOf<DrivePlace>(place)
        // What's shown now (the copy in memory, or the one saved last time) already has TMDB's
        // details; the fresh listing borrows them until its own lookup is done, so the posters,
        // genres, rows and featured title don't vanish for a moment — taking the focus with them —
        // and keeps them where a lookup fails this time (TMDB unreachable).
        val shown = cache[place]
        val shownCut = cuts[place] ?: 0
        val before = shown?.associateBy { it.id }
        // Its art loaded ahead, once a run: opening it before the fresh listing is in shows pictures.
        if (shown != null && warmedSaved.add(place)) warm(token, stack, shown)
        return try {
            // The listing with the owner's own art first; TMDB's posters follow.
            val listing = DriveApi.listing(token, place)
            // Google answered: the screen says so now, not after the posters.
            reached(place)
            val files = parse(DriveApi.withPosters(token, listing.files))
            val interim = carryOver(files, before)
            if (interim != shown || listing.cutAt != shownCut || _state.value.let { it.place == place && it.status.down }) publish(gen, place, interim, listing.cutAt, token)
            val withTmdb = carryOver(tmdb(stack, files), before)
            warm(token, stack, withTmdb)
            if (withTmdb != interim) publish(gen, place, withTmdb, listing.cutAt, token)
            val unchanged = withTmdb == shown && listing.cutAt == shownCut
            withContext(Dispatchers.IO) {
                if (unchanged) ListingCache.touch(ctx, place, gen = disk)
                else ListingCache.write(ctx, place, withTmdb, cutAt = listing.cutAt, gen = disk)
            }
            kept(gen) { listedAt[place] = System.currentTimeMillis() }
            updateRecentlyAdded()
            Fetch.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveHttpException) {
            if (e.code == 401 && !renewed) {
                withContext(Dispatchers.IO) { GoogleAuth.forgetAccess(ctx) }
                listLibrary(place, renewed = true)
            } else fetchFailure(e, ::explain)
        } catch (e: Exception) {
            fetchFailure(e, ::explain)
        }
    }

    /**
     * A new TMDB key: a library's listing as it is, with TMDB's posters and details looked up again,
     * and no Drive pass. What TMDB matched before comes from its cache; only the rest is asked for.
     * Unchanged (the same answers), nothing is redrawn or saved.
     */
    private suspend fun retag(place: DrivePlace.Named): Fetch {
        val gen = synchronized(libraryLock) { generation }
        val disk = ListingCache.generation()
        val shown = cache[place] ?: return Fetch.Done
        val cut = cuts[place] ?: 0
        val withTmdb = carryOver(tmdb(listOf(place), shown), shown.associateBy { it.id })
        if (withTmdb == shown) return Fetch.Done
        publish(gen, place, withTmdb, cut, fromDrive = false)
        _state.value.token?.let { warm(it, listOf(place), withTmdb) }
        withContext(Dispatchers.IO) { ListingCache.write(ctx, place, withTmdb, cutAt = cut, gen = disk) }
        updateRecentlyAdded()
        return Fetch.Done
    }

    /**
     * One try at listing a place that isn't a library's top (a show's seasons, a folder, Shared with
     * me), putting what arrives on screen (if the viewer is still there) and saving a library
     * folder's. A token Drive won't take (401) is renewed once and the listing tried again ([renewed]).
     */
    private suspend fun fetch(place: DrivePlace, stack: List<DrivePlace>, library: Boolean, renewed: Boolean = false): Fetch {
        val disk = ListingCache.generation()
        val result = GoogleAuth.tokenResult(ctx)
        fetchFor(result)?.let { return it }
        val token = (result as GoogleAuth.TokenResult.Ok).token
        return try {
            // Show the listing (with the owner's own art) right away; TMDB posters follow.
            val listing = DriveApi.listing(token, place)
            reached(place)
            val files = listing.files.let { if (library) DriveApi.withPosters(token, it) else it }
            // What was on screen already has TMDB's pictures; this fresh listing only gets them when
            // the lookup below finishes. Until then it borrows them, so they don't vanish for a moment.
            val before = if (library && tmdbMode(stack) != Tmdb.Mode.NONE) cache[place]?.associateBy { it.id } else null
            val interim = carryOver(files, before)
            cache[place] = interim
            if (listing.cutAt > 0) cuts[place] = listing.cutAt else cuts.remove(place)
            // Only apply if the viewer is still on this place (the offline page giving way to it).
            _state.update {
                if (it.place == place) it.copy(token = token, files = interim, busy = false, cutAt = listing.cutAt, status = if (it.status.down) DriveStatus.Online else it.status)
                else it
            }
            if (library && tmdbMode(stack) == Tmdb.Mode.NONE) withContext(Dispatchers.IO) { ListingCache.write(ctx, place, files, cutAt = listing.cutAt, gen = disk) }
            if (library && tmdbMode(stack) != Tmdb.Mode.NONE) {
                // A lookup that failed this time (TMDB unreachable) keeps what was found before.
                val withTmdb = carryOver(tmdb(stack, files), before)
                warm(token, stack, withTmdb)
                cache[place] = withTmdb
                _state.update { if (it.place == place) it.copy(files = withTmdb) else it }
                withContext(Dispatchers.IO) { ListingCache.write(ctx, place, withTmdb, cutAt = listing.cutAt, gen = disk) }
            }
            Fetch.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveHttpException) {
            if (e.code == 401 && !renewed) {
                withContext(Dispatchers.IO) { GoogleAuth.forgetAccess(ctx) }
                fetch(place, stack, library, renewed = true)
            } else fetchFailure(e, ::explain)
        } catch (e: Exception) {
            fetchFailure(e, ::explain)
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
                val token = withContext(Dispatchers.IO) {
                    GoogleAuth.requireToken(ctx, GoogleAuth.Grant.DRIVE, signedOut = "Google ended this TV’s sign-in. Open Streaming and scan the code with your phone.")
                }
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

    /** [e] in the family's words: the network's, or Woodruff's own message for Drive's answer; never an exception's own text. */
    private fun explain(e: Throwable): String = when {
        isNetworkFailure(e) -> if (NetworkWatch.online.value) DriveApi.CANT_REACH else "This TV is offline. Try again when it’s back online."
        e is java.io.IOException -> e.message ?: DriveApi.CANT_REACH
        else -> DriveApi.CANT_REACH
    }
}

/**
 * The Movies / TV Shows entry whose poster fits something being watched: the entry it's known to
 * sit under ([shows], from [EpisodeShows]: an episode's show, a film's folder), else by its own
 * Drive id (a film at the top of Movies), else by its film or show name.
 */
fun artworkFor(index: Map<String, DriveFile>, key: String, title: String, shows: Map<String, String>): DriveFile? {
    // No posters known yet (the libraries still loading): nothing to find, so no name worked out.
    if (index.isEmpty()) return null
    return shows[key]?.let { index["id:$it"] }
        ?: (if (key.startsWith("drive:")) index["id:" + key.removePrefix("drive:")] else null)
        ?: matchKeyOf(title).takeIf { it.isNotBlank() }?.let { index[it] }
}

/** Movies and TV Shows: the libraries listed in full, kept warm for Home, search and the sidebar. */
private val LIBRARIES = listOf(DrivePlace.Movies, DrivePlace.TvShows)

/**
 * [Library.showMatchKey] of the libraries' names and of what's in progress, worked out on a
 * background thread as each listing arrives (and for anything else the first time it's asked).
 * Matching what's being watched to its show then only looks them up, rather than running a dozen
 * regexes per title while a screen draws its first frame. Forgotten on signing out.
 */
private val matchKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

/** [Library.showMatchKey], remembered ([matchKeys]). */
internal fun matchKeyOf(name: String): String =
    matchKeys[name] ?: Library.showMatchKey(name).also { if (matchKeys.size < 10_000) matchKeys[name] = it }
