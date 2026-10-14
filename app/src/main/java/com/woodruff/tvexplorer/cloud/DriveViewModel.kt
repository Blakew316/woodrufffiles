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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) {
    val place: DrivePlace get() = stack.last()
    val connected: Boolean get() = step == SignInStep.CONNECTED
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

    init {
        viewModelScope.launch { CompanionHub.googleCodes.collect { (code, attempt) -> if (attempt.grant == GoogleAuth.Grant.DRIVE) finishSignIn(code, attempt) } }
        // Keys saved from the phone or the Settings screen: move on to the sign-in step.
        viewModelScope.launch { CompanionHub.changes.collect { if (!_state.value.connected) { _state.update { it.copy(step = initialStep()) }; prepareSignIn() } } }
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

    /** Signs out of Google on this TV; the saved keys stay so signing in again is one QR scan. */
    fun signOut() {
        viewModelScope.launch {
            GoogleAuth.signOut(ctx)
            _state.value = DriveState(step = SignInStep.SIGN_IN)
            prepareSignIn()
        }
    }

    /** Forgets the Google Cloud keys too (e.g. after creating a new OAuth client). */
    fun resetSetup() {
        GoogleAuth.resetClient(ctx)
        _state.value = DriveState(step = SignInStep.SETUP)
        prepareSignIn()
    }

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

    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(busy = true, error = null, files = emptyList()) }
        loadJob = viewModelScope.launch {
            try {
                val token = GoogleAuth.token(ctx)
                if (token == null) {
                    // Approval expired or was revoked in the Google account: sign in again.
                    _state.update { it.copy(step = SignInStep.SIGN_IN, busy = false) }
                    prepareSignIn()
                    return@launch
                }
                val files = DriveApi.list(token, _state.value.place)
                _state.update { it.copy(token = token, files = files, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = explain(e)) }
            }
        }
    }

    fun download(file: DriveFile) {
        if (opJob?.isActive == true) return say("A download is already running")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Woodruff Films").apply { mkdirs() }
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
                say("Saved to Downloads/Woodruff Films/${dest.name}")
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
