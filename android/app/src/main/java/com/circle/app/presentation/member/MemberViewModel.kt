package com.circle.app.presentation.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.model.*
import com.circle.app.domain.usecase.MemberActions
import com.circle.app.presentation.common.toUiMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MemberUiState(
    val stats: MemberStats? = null,
    val notices: List<CircleNotice> = emptyList(),
    val preferences: NoticePreferences = NoticePreferences(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
)

class MemberViewModel(private val actions: MemberActions) : ViewModel() {
    private val mutable = MutableStateFlow(MemberUiState())
    val state = mutable.asStateFlow()

    fun load() {
        viewModelScope.launch {
            try {
                val stats = actions.stats()
                val notices = actions.notices()
                val p = actions.preferences()
                mutable.value =
                    state.value.copy(
                        stats = stats,
                        notices = notices,
                        preferences = p,
                        loading = false,
                        error = null,
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.toUiMessage()) }
            }
        }
    }

    fun read(id: Long) {
        viewModelScope.launch {
            try {
                actions.read(id)
                mutable.update {
                    it.copy(
                        notices = it.notices.map { n -> if (n.id == id) n.copy(read = true) else n }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.toUiMessage()) }
            }
        }
    }

    fun save(p: NoticePreferences, deviceToken: String? = null) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                if (p.pushEnabled && deviceToken != null) actions.registerDevice(deviceToken)
                actions.save(p)
                mutable.update { it.copy(busy = false, preferences = p, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, error = e.toUiMessage()) }
            }
        }
    }

    fun delete() {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                actions.deleteAccount()
                mutable.update { it.copy(busy = false, deleted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        busy = false,
                        error =
                            "${e.toUiMessage()} For account deletion, sign out and sign in again first.",
                    )
                }
            }
        }
    }

    fun error(message: String) {
        mutable.update { it.copy(error = message) }
    }
}
