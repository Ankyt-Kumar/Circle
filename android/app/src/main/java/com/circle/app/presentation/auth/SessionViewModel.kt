package com.circle.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.model.Account
import com.circle.app.domain.model.AuthSession
import com.circle.app.domain.repository.AuthRepository
import com.circle.app.domain.usecase.GetAccount
import com.circle.app.domain.usecase.SaveFirstName
import com.circle.app.presentation.common.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SessionUiState(val session: AuthSession = AuthSession(), val account: Account? = null,
    val loading: Boolean = true, val saving: Boolean = false, val error: String? = null)

class SessionViewModel(private val auth: AuthRepository, private val getAccount: GetAccount, private val saveFirstName: SaveFirstName) : ViewModel() {
    private val mutable = MutableStateFlow(SessionUiState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    init { viewModelScope.launch {
        auth.session.collectLatest { session ->
            operation?.cancel()
            mutable.value = SessionUiState(session = session, loading = session.userId != null)
            if (session.userId != null) load(session)
        }
    } }
    private suspend fun load(session: AuthSession) {
        try {
            val account = getAccount()
            if (auth.session.value.generation != session.generation) return
            mutable.value = if (account.authProvider in setOf("password", "google.com")) SessionUiState(session, account, loading = false)
                else SessionUiState(session, loading = false, error = "The backend is in shared demo mode. Select the same sign-in mode for the app and backend.")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { if (auth.session.value.generation == session.generation) mutable.value = mutable.value.copy(loading = false, error = e.toUiMessage()) }
    }
    fun retry() {
        val session = auth.session.value
        if (session.userId == null || mutable.value.loading) return
        operation?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        operation = viewModelScope.launch { load(session) }
    }
    fun saveName(name: String) {
        if (mutable.value.saving) return
        val session = auth.session.value
        if (session.userId == null) return
        mutable.value = mutable.value.copy(saving = true, error = null)
        operation = viewModelScope.launch {
            try {
                val account = saveFirstName(name)
                if (auth.session.value.generation == session.generation) mutable.value = SessionUiState(session, account, loading = false)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { if (auth.session.value.generation == session.generation) mutable.value = mutable.value.copy(saving = false, error = "Couldn’t save your name. Use 1–60 letters and check your connection.") }
        }
    }
    fun signOut() { operation?.cancel(); auth.signOut() }
    fun accountUpdated(account: Account) {
        if (mutable.value.session.userId != null && mutable.value.account?.id == account.id) {
            mutable.value = mutable.value.copy(account = account, error = null)
        }
    }
}
