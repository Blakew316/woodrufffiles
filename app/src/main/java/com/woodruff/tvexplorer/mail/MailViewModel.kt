package com.woodruff.tvexplorer.mail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woodruff.tvexplorer.server.CompanionHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MailState(
    val accounts: List<MailAccount> = emptyList(),
    val selected: String? = null,
    val messages: List<MailSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val openUid: Long? = null,
    val open: MailBody? = null,
    val openLoading: Boolean = false,
) {
    val account: MailAccount? get() = accounts.firstOrNull { it.id == selected }
}

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(MailState())
    val state: StateFlow<MailState> = _state.asStateFlow()
    private var listJob: Job? = null
    private var readJob: Job? = null

    init {
        reloadAccounts()
        viewModelScope.launch { CompanionHub.changes.collect { reloadAccounts() } }
    }

    fun reloadAccounts() {
        val accounts = MailAccounts.list(getApplication())
        val keep = _state.value.selected?.takeIf { id -> accounts.any { it.id == id } }
        _state.update { it.copy(accounts = accounts) }
        select(keep ?: accounts.firstOrNull()?.id, force = keep == null)
    }

    fun select(id: String?, force: Boolean = true) {
        if (!force && id == _state.value.selected && _state.value.messages.isNotEmpty()) return
        val account = _state.value.accounts.firstOrNull { it.id == id }
        _state.update { it.copy(selected = id, messages = emptyList(), open = null, openUid = null, error = null, loading = account != null) }
        if (account == null) return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            try {
                val msgs = MailClient.inbox(account)
                _state.update { it.copy(messages = msgs, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the inbox") }
            }
        }
    }

    fun refresh() = select(_state.value.selected)

    fun open(m: MailSummary) {
        val account = _state.value.account ?: return
        _state.update { it.copy(openUid = m.uid, open = null, openLoading = true) }
        readJob?.cancel()
        readJob = viewModelScope.launch {
            try {
                val body = MailClient.read(account, m.uid)
                _state.update { s -> s.copy(open = body, openLoading = false, messages = s.messages.map { if (it.uid == m.uid) it.copy(seen = true) else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(openLoading = false, open = MailBody(m.from, "", m.subject, m.date, "Couldn't open this message: ${e.message}")) }
            }
        }
    }

    fun closeMessage() = _state.update { it.copy(open = null, openUid = null) }
}
