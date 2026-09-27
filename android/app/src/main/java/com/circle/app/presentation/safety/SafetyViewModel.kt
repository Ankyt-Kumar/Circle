package com.circle.app.presentation.safety

import androidx.lifecycle.*
import com.circle.app.domain.model.*
import com.circle.app.domain.usecase.*
import com.circle.app.presentation.common.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SafetyUiState(
    val circle: Circle? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val blocked: Boolean = false,
    val error: String? = null,
)

class SafetyViewModel(
    private val getCircle: GetCircle,
    private val blockUser: BlockUser,
    saved: SavedStateHandle,
) : ViewModel() {
    val circleId: String = checkNotNull(saved["circleId"])
    val targetUserId: String = checkNotNull(saved["userId"])
    private val mutable = MutableStateFlow(SafetyUiState())
    val state = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (state.value.busy || state.value.blocked) return
        viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val circle = getCircle(circleId)
                mutable.update { it.copy(circle = circle, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.toUiMessage()) }
            }
        }
    }

    fun block() {
        if (state.value.busy || targetUserId.isBlank()) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                blockUser(circleId, targetUserId)
                mutable.update { it.copy(busy = false, blocked = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, error = e.toUiMessage()) }
            }
        }
    }
}

data class BlockedUsersUiState(
    val users: List<BlockedUser> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

class BlockedUsersViewModel(
    private val getBlocked: GetBlockedUsers,
    private val unblockUser: UnblockUser,
) : ViewModel() {
    private val mutable = MutableStateFlow(BlockedUsersUiState())
    val state = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (state.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val users = getBlocked()
                mutable.update { it.copy(users = users, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.toUiMessage()) }
            }
        }
    }

    fun unblock(id: String) {
        if (state.value.busy || state.value.loading) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                unblockUser(id)
                mutable.update {
                    it.copy(users = it.users.filterNot { person -> person.id == id }, busy = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, error = e.toUiMessage()) }
            }
        }
    }
}
