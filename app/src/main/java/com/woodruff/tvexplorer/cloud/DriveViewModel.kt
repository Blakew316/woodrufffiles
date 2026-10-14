package com.woodruff.tvexplorer.cloud

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.ui.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DriveState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val token: String? = null,
    /** Navigation stack; the last entry is what's on screen. */
    val stack: List<DrivePlace> = listOf(DrivePlace.MyDrive),
    val files: List<DriveFile> = emptyList(),
    val error: String? = null,
    val operation: Operation? = null,
    val message: String? = null,
) {
    val place: DrivePlace get() = stack.last()
}

/** Google Drive browsing state, kept apart from the local-file ViewModel. */
class DriveViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(DriveState(connected = GoogleAuth.isConnected(app)))
    val state: StateFlow<DriveState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var opJob: Job? = null

    /** Called when the Drive screen opens and when the user picks Connect. */
    fun connect(launchConsent: (PendingIntent) -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (val o = GoogleAuth.authorize(getApplication())) {
                    is GoogleAuth.Outcome.Token -> onToken(o.accessToken)
                    is GoogleAuth.Outcome.NeedsConsent -> launchConsent(o.intent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = explain(e)) }
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        try {
            when (val o = GoogleAuth.fromResultIntent(getApplication(), data)) {
                is GoogleAuth.Outcome.Token -> onToken(o.accessToken)
                is GoogleAuth.Outcome.NeedsConsent -> _state.update { it.copy(busy = false, error = "Google sign-in wasn't completed.") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = explain(e)) }
        }
    }

    private fun onToken(token: String) {
        GoogleAuth.setConnected(getApplication(), true)
        _state.update { it.copy(connected = true, token = token, busy = false) }
        load()
    }

    /** Forgets the connection in this app. Access can be fully revoked at myaccount.google.com. */
    fun disconnect() {
        GoogleAuth.setConnected(getApplication(), false)
        _state.value = DriveState(connected = false)
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
        val token = _state.value.token ?: return
        loadJob?.cancel()
        _state.update { it.copy(busy = true, error = null, files = emptyList()) }
        loadJob = viewModelScope.launch {
            try {
                // Tokens expire after an hour; Play services hands back a fresh one silently.
                val fresh = GoogleAuth.token(getApplication()) ?: token
                val files = DriveApi.list(fresh, _state.value.place)
                _state.update { it.copy(token = fresh, files = files, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = explain(e)) }
            }
        }
    }

    fun download(file: DriveFile) {
        if (opJob?.isActive == true) return say("A download is already running")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Woodruff Shows").apply { mkdirs() }
        val dest = FileOperations.uniqueTarget(dir, file.name.replace('/', '_'))
        _state.update { it.copy(operation = Operation("Downloading")) }
        var last = 0L
        opJob = viewModelScope.launch {
            try {
                val token = GoogleAuth.token(getApplication()) ?: _state.value.token ?: error("Not signed in")
                DriveApi.download(token, file, dest) { p ->
                    val now = System.currentTimeMillis()
                    if (now - last > 150) { last = now; _state.update { it.copy(operation = Operation("Downloading", p)) } }
                }
                say("Saved to Downloads/Woodruff Shows/${dest.name}")
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
        e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
            "Google Drive isn't set up for this app yet. Follow the \"Connect Google Drive\" steps in the README."
        e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR -> "No internet connection."
        e is ApiException && e.statusCode == CommonStatusCodes.CANCELED -> "Google sign-in was cancelled."
        e is ApiException && e.statusCode == CommonStatusCodes.API_NOT_CONNECTED ->
            "This TV doesn't have Google Play services, which Google Drive sign-in needs."
        e is java.net.UnknownHostException -> "No internet connection."
        else -> e.message ?: "Something went wrong talking to Google Drive."
    }
}
