package com.woodruff.tvexplorer.nest

import android.content.Context
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.util.NetworkWatch
import com.woodruff.tvexplorer.util.backoffMs
import com.woodruff.tvexplorer.util.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 */
object Nest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(NestState())
    val state: StateFlow<NestState> = _state
    private lateinit var app: Context
    private var signInJob: Job? = null

    fun init(c: Context) {
        app = c.applicationContext
        NetworkWatch.start(app)
        // Google ended the cameras' sign-in (found out by any renewal, the doorbell's included).
        scope.launch {
            GoogleAuth.expirations.collect { grant ->
                if (grant == GoogleAuth.Grant.NEST) _state.update { it.copy(step = step(), offline = false) }
            }
        }
        // Reading the encrypted settings goes through the Keystore; keep it off the main thread at
        // launch. This also warms the settings cache, so Settings and Drive open instantly later.
        scope.launch {
            val s = withContext(Dispatchers.IO) { step() }
            _state.update { if (it.step == NestStep.NEED_GOOGLE) it.copy(step = s) else it }
            // Cameras connected: the tiles' GL thread is made now, in the background, before Home shows them.
            if (s == NestStep.CONNECTED) CameraGl.warm()
        }
        scope.launch {
            CompanionHub.googleCodes.collect { (code, attempt) ->
                if (attempt.grant != GoogleAuth.Grant.NEST) return@collect
                try {
                    GoogleAuth.exchange(app, code, attempt)
                    _state.update { it.copy(step = NestStep.CONNECTED, qrUrl = null, error = null) }
                    refresh()
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Sign-in failed") }
                }
            }
        }
        // Google keys or the project ID changed (from the phone or Settings).
        scope.launch { CompanionHub.changes.collect { if (_state.value.step != NestStep.CONNECTED) _state.update { it.copy(step = step()) } } }
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
        if (s == NestStep.CONNECTED) CameraGl.warm()
        when (s) {
            NestStep.CONNECTED -> if (_state.value.cameras.isEmpty()) refresh()
            NestStep.SIGN_IN -> startSignIn()
            else -> Unit
        }
    }

    /** For the Home dashboard: load cameras when already connected, without starting any sign-in. */
    fun warm() {
        val s = step()
        _state.update { it.copy(step = s) }
        if (s == NestStep.CONNECTED) CameraGl.warm()
        if (s == NestStep.CONNECTED && _state.value.cameras.isEmpty() && !_state.value.loading) refresh()
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
                    _state.update { it.copy(cameras = cams, loading = false, offline = false, error = if (cams.isEmpty()) "No cameras were shared. Connect again and turn on your cameras on Google's sharing page." else null) }
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
            _state.update { it.copy(cameras = emptyList(), error = null) }
            open()
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun signOut(done: () -> Unit = {}) {
        refreshJob?.cancel()
        scope.launch {
            GoogleAuth.signOut(app, GoogleAuth.Grant.NEST)
            _state.update { NestState(step = step()) }
            done()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
