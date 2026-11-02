package com.woodruff.tvexplorer.nest

import android.content.Context
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.util.NetworkWatch
import com.woodruff.tvexplorer.util.backoffMs
import com.woodruff.tvexplorer.util.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class NestStep {
    /** Google client ID/secret aren't saved yet (same keys as Google Drive). */
    NEED_GOOGLE,
    /** The Device Access Project ID is missing. */
    NEED_PROJECT,
    /** Showing the QR code for the phone sign-in. */
    SIGN_IN,
    CONNECTED,
}

data class NestState(
    val step: NestStep = NestStep.NEED_GOOGLE,
    val cameras: List<Camera> = emptyList(),
    val loading: Boolean = false,
    val qrUrl: String? = null,
    val error: String? = null,
    /**
     * The camera list couldn't reach Google (no network yet, Google unreachable). The sign-in is
     * fine: [cameras] are the last ones listed, if any, and it lists them again by itself as soon as
     * the network is back.
     */
    val offline: Boolean = false,
)

/**
 * App-wide camera state. Lives outside any screen so the phone's sign-in callback is handled even
 * if the viewer has moved to another screen while signing in on the phone.
 *
 * The camera list is kept on the TV ([camerasToJson]: names, labels and rooms, nothing secret) and
 * read back at start-up, before anything asks: so Home's camera tiles show straight away, and a
 * doorbell or person alert names the right camera even when the TV started by itself (at power-on,
 * after an update) with no screen open. It's listed again in the background once per run.
 */
object Nest {
    private const val PREFS = "nest"
    private const val K_CAMERAS = "cameras"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(NestState())
    val state: StateFlow<NestState> = _state
    private lateinit var app: Context
    private var signInJob: Job? = null
    /** The saved camera list has been read (see [awaitSaved]). */
    private val saved = CompletableDeferred<Unit>()

    fun init(c: Context) {
        app = c.applicationContext
        NetworkWatch.start(app)
        // Google ended the cameras' sign-in (found out by any renewal, the doorbell's included).
        scope.launch {
            GoogleAuth.expirations.collect { grant ->
                if (grant == GoogleAuth.Grant.NEST) { _state.update { it.copy(step = step(), offline = false) }; Doorbell.nudge() }
            }
        }
        // Reading the encrypted settings goes through the Keystore; keep it off the main thread at
        // launch. This also warms the settings cache, so Settings and Drive open instantly later.
        scope.launch {
            // The cameras as last listed, first: a ring can come before any screen opens. The list is
            // kept only while they're connected (it goes when they're signed out or the project
            // changes), so with one saved they show as connected straight away: Home's tiles don't
            // wait for the sign-in to be read from the Keystore (just below, which puts it right if
            // it changed meanwhile), and nothing says "aren't connected" for a moment at every start.
            // (Every change to the state is made on the main thread: this read and write is one step.)
            val known = withContext(Dispatchers.IO) { camerasFromJson(prefs().getString(K_CAMERAS, null)) }
            val (shown, guessed) = startingWith(_state.value, known)
            _state.value = shown
            saved.complete(Unit)
            val s = withContext(Dispatchers.IO) { step() }
            _state.update { signInRead(it, s, guessed) }
            if (s == NestStep.CONNECTED) {
                // The tiles' GL thread is made now, in the background, before Home shows them.
                CameraGl.warm()
                // And the list again, behind the saved one: a camera renamed or added since.
                refresh()
            }
        }
        scope.launch {
            CompanionHub.googleCodes.collect { (code, attempt) ->
                if (attempt.grant != GoogleAuth.Grant.NEST) return@collect
                try {
                    GoogleAuth.exchange(app, code, attempt)
                    _state.update { it.copy(step = NestStep.CONNECTED, qrUrl = null, error = null) }
                    refresh()
                    // The doorbell listens on this TV from now on.
                    Doorbell.nudge()
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Sign-in failed") }
                }
            }
        }
        // Google keys or the project ID changed (from the phone or Settings).
        scope.launch { CompanionHub.changes.collect { if (_state.value.step != NestStep.CONNECTED) _state.update { it.copy(step = step()) } } }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The cameras [cams] are what Google lists now: shown, and kept for the next start. */
    private fun listed(cams: List<Camera>) {
        _state.update { it.copy(cameras = cams) }
        prefs().edit().putString(K_CAMERAS, camerasToJson(cams)).apply()
    }

    /** The saved list goes (signed out, another project: other cameras). */
    private fun forgetCameras() {
        prefs().edit().remove(K_CAMERAS).apply()
    }

    /** [camera]'s name ("Front Door"), from the list as last known; null for a camera it doesn't know. */
    fun label(camera: String): String? = _state.value.cameras.firstOrNull { it.name == camera }?.label

    /** Waits (at most [timeoutMs]) until the saved camera list has been read at start-up. */
    suspend fun awaitSaved(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { saved.await() }
    }

    private var labelListing: Deferred<Boolean>? = null

    /**
     * Lists the cameras for naming an alert from a camera the list doesn't know: once in each run of
     * the app, waiting at most [timeoutMs] for it (it carries on in the background after that, and
     * the next alert has it). True when the list came in.
     */
    suspend fun listOnce(timeoutMs: Long): Boolean {
        val listing = synchronized(this) {
            labelListing ?: scope.async {
                runCatching { NestApi.cameras(app) }.onSuccess { listed(it) }.isSuccess
            }.also { labelListing = it }
        }
        return withTimeoutOrNull(timeoutMs) { listing.await() } ?: false
    }

    private fun step(): NestStep = when {
        !GoogleAuth.isConfigured(app) -> NestStep.NEED_GOOGLE
        GoogleAuth.nestProject(app) == null -> NestStep.NEED_PROJECT
        GoogleAuth.isConnected(app, GoogleAuth.Grant.NEST) -> NestStep.CONNECTED
        else -> NestStep.SIGN_IN
    }

    /** Called when the Command center opens: load cameras, or get a sign-in QR code ready. */
    fun open() {
        val s = step()
        _state.update { it.copy(step = s, error = null) }
        // Cameras are about to show: their GL thread and WebRTC are made ready in the background.
        if (s == NestStep.CONNECTED) { CameraGl.warm(); Rtc.warm(app) }
        when (s) {
            NestStep.CONNECTED -> if (_state.value.cameras.isEmpty()) refresh()
            NestStep.SIGN_IN -> startSignIn()
            else -> Unit
        }
    }

    /**
     * For the Home dashboard and the doorbell view: load cameras when already connected, without
     * starting any sign-in. Whether they are is read off the main thread (the Keystore); the job
     * returned is done once it has been (the doorbell view waits for it before saying they aren't).
     */
    fun warm(): Job =
        scope.launch {
            val s = withContext(Dispatchers.IO) { step() }
            _state.update { it.copy(step = s) }
            if (s == NestStep.CONNECTED) {
                CameraGl.warm()
                Rtc.warm(app)
                if (_state.value.cameras.isEmpty() && !_state.value.loading) refresh()
            }
        }

    private var refreshJob: Job? = null

    /**
     * Lists the cameras. Offline (no network yet, Google unreachable), it keeps what it had and
     * lists them again the moment the network is back, or after a growing pause (10 s up to 5 min).
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            var tries = 0
            while (true) {
                val mark = NetworkWatch.comebacks.value
                // Retries keep the offline state up rather than flashing "Loading".
                _state.update { if (it.offline) it.copy(error = null) else it.copy(loading = true, error = null) }
                try {
                    val cams = NestApi.cameras(app)
                    listed(cams)
                    _state.update { it.copy(loading = false, offline = false, error = if (cams.isEmpty()) "No cameras were shared. Connect again and turn on your cameras on Google's sharing page." else null) }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isNetworkFailure(e)) {
                        _state.update { it.copy(loading = false, offline = false, error = e.message ?: "Couldn't load cameras", step = step()) }
                        return@launch
                    }
                    _state.update { it.copy(loading = false, offline = true, error = null) }
                }
                val cameBack = NetworkWatch.awaitComeback(mark, backoffMs(tries, 10_000, 5 * 60_000))
                tries = if (cameBack) 0 else tries + 1
            }
        }
    }

    fun startSignIn() {
        signInJob?.cancel()
        signInJob = scope.launch {
            TransferService.start(app)
            val server = TransferService.state.first { it.running || it.error != null }
            val base = server.base
            if (base == null) {
                _state.update { it.copy(error = server.error ?: "The phone connection couldn't start.") }
                return@launch
            }
            try {
                val a = GoogleAuth.newAttempt(app, base, GoogleAuth.Grant.NEST)
                CompanionHub.expect(a)
                _state.update { it.copy(step = NestStep.SIGN_IN, qrUrl = a.authUrl, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, step = step()) }
            }
        }
    }

    fun saveProject(id: String) {
        try {
            com.woodruff.tvexplorer.settings.SharedKeys.set(app, com.woodruff.tvexplorer.settings.SharedKey.CAMERAS) { GoogleAuth.saveNestProject(app, id) }
            // Another project: other cameras (and a new sign-in, which the doorbell waits for).
            forgetCameras()
            _state.update { it.copy(cameras = emptyList(), error = null) }
            Doorbell.nudge()
            open()
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun signOut(done: () -> Unit = {}) {
        refreshJob?.cancel()
        scope.launch {
            GoogleAuth.signOut(app, GoogleAuth.Grant.NEST)
            forgetCameras()
            _state.update { NestState(step = step()) }
            Doorbell.nudge()
            done()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

/**
 * What shows at start-up once the cameras saved at the last listing ([known]) have been read, over
 * [state], before the sign-in is read from the Keystore: those cameras, as connected (the list is
 * kept only while they are), unless something has already said otherwise. Second: whether that
 * step is the saved list's guess, for [signInRead] to replace.
 */
internal fun startingWith(state: NestState, known: List<Camera>): Pair<NestState, Boolean> {
    if (known.isEmpty() || state.cameras.isNotEmpty()) return state to false
    val guess = state.step == NestStep.NEED_GOOGLE
    return state.copy(cameras = known, step = if (guess) NestStep.CONNECTED else state.step) to guess
}

/**
 * [state] once the sign-in has been read at start-up as [read]: it replaces the step unless
 * something else set it meanwhile (the Command Center opening, a sign-in finishing). [guessed]: the
 * step showing is [startingWith]'s guess, which it does replace.
 */
internal fun signInRead(state: NestState, read: NestStep, guessed: Boolean): NestState =
    if (state.step == NestStep.NEED_GOOGLE || (guessed && state.step == NestStep.CONNECTED)) state.copy(step = read) else state
