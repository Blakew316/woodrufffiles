package com.woodruff.tvexplorer.nest

import android.content.Context
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
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

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val cams = NestApi.cameras(app)
                _state.update { it.copy(cameras = cams, loading = false, error = if (cams.isEmpty()) "No cameras were shared. Connect again and turn on your cameras on Google's sharing page." else null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load cameras", step = step()) }
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
            GoogleAuth.saveNestProject(app, id)
            _state.update { it.copy(cameras = emptyList(), error = null) }
            open()
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun signOut(done: () -> Unit = {}) {
        scope.launch {
            GoogleAuth.signOut(app, GoogleAuth.Grant.NEST)
            _state.update { NestState(step = step()) }
            done()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
