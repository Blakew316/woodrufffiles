package com.woodruff.tvexplorer.cloud

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.server.TransferServer
import com.woodruff.tvexplorer.server.TransferService
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import com.woodruff.tvexplorer.ui.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Short address shown under the setup QR for typing on a computer instead. */
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
 * Google Drive state, kept apart from the local-file ViewModel. Owns the short-lived
 * [SignInServer] while the user is connecting from their phone.
 */
class DriveViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _state = MutableStateFlow(DriveState(step = initialStep()))
    val state: StateFlow<DriveState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var opJob: Job? = null
    private var server: SignInServer? = null
    @Volatile private var attempt: GoogleAuth.Attempt? = null

    private fun initialStep() = when {
        GoogleAuth.isConnected(ctx) -> SignInStep.CONNECTED
        GoogleAuth.isConfigured(ctx) -> SignInStep.SIGN_IN
        else -> SignInStep.SETUP
    }

    /** Called when the Drive screen opens: load files, or get the phone sign-in ready. */
    fun start() {
        when (_state.value.step) {
            SignInStep.CONNECTED -> if (_state.value.files.isEmpty() && !_state.value.busy) load()
            else -> prepareSignIn()
        }
    }

    /** Called when the Drive screen closes, so the LAN sign-in server doesn't linger. */
    fun stop() {
        server?.stop()
        server = null
    }

    private fun prepareSignIn() {
        val ip = TransferService.lanAddress()
            ?: return _state.update { it.copy(error = "Connect the TV to Wi-Fi or Ethernet first. Your phone signs in over the home network.", qrUrl = null) }
        val srv = server ?: startServer() ?: return _state.update { it.copy(error = "Couldn't open a network port for sign-in.") }
        val base = "http://$ip:${srv.listeningPort}"
        if (_state.value.step == SignInStep.SETUP || !GoogleAuth.isConfigured(ctx)) {
            _state.update { it.copy(step = SignInStep.SETUP, qrUrl = "$base/setup?k=${srv.key}", setupAddress = "$base/setup?k=${srv.key}", error = null) }
        } else {
            val a = GoogleAuth.newAttempt(ctx, base)
            attempt = a
            _state.update { it.copy(step = SignInStep.SIGN_IN, qrUrl = a.authUrl, error = null) }
        }
    }

    private fun startServer(): SignInServer? {
        val key = TransferServer.randomToken()
        for (port in listOf(8091, 8092, 8093, 0)) {
            val s = SignInServer(
                port, key,
                expectedState = { attempt?.state },
                onClient = { id, secret ->
                    GoogleAuth.saveClient(ctx, id, secret)
                    viewModelScope.launch { _state.update { it.copy(step = SignInStep.SIGN_IN) }; prepareSignIn() }
                },
                onCode = { code -> viewModelScope.launch { finishSignIn(code) } },
                onError = { msg -> _state.update { it.copy(error = msg) } },
            )
            try {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                server = s
                return s
            } catch (e: IOException) { /* try the next port */ }
        }
        return null
    }

    private suspend fun finishSignIn(code: String) {
        val a = attempt ?: return
        attempt = null // one use only
        _state.update { it.copy(busy = true, error = null) }
        try {
            GoogleAuth.exchange(ctx, code, a)
            // Let the phone receive its "You're signed in" page before closing the server.
            viewModelScope.launch { delay(3000); if (_state.value.connected) stop() }
            _state.update { it.copy(step = SignInStep.CONNECTED, busy = false, qrUrl = null) }
            load()
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Sign-in failed") }
            prepareSignIn()
        }
    }

    /** Signs out of Google on this TV; the saved client stays so signing in again is one QR scan. */
    fun signOut() {
        viewModelScope.launch {
            GoogleAuth.signOut(ctx)
            _state.value = DriveState(step = SignInStep.SIGN_IN)
            prepareSignIn()
        }
    }

    /** Starts over from pasting the client ID/secret (e.g. after creating a new OAuth client). */
    fun resetSetup() {
        GoogleAuth.resetClient(ctx)
        _state.value = DriveState(step = SignInStep.SETUP)
        prepareSignIn()
    }

    override fun onCleared() { stop() }

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
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Woodruff Pictures").apply { mkdirs() }
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
                say("Saved to Downloads/Woodruff Pictures/${dest.name}")
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
