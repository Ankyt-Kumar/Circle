package com.circle.app.presentation.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.error.*
import com.circle.app.domain.model.*
import com.circle.app.domain.usecase.ChatActions
import com.circle.app.presentation.common.toUiMessage
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val text: String = "",
    val loading: Boolean = true,
    val sending: Boolean = false,
    val archived: Boolean = false,
    val unavailable: Boolean = false,
    val hasOlder: Boolean = false,
    val privateAi: Boolean = false,
    val guide: ConversationGuide? = null,
    val error: String? = null,
    val notice: String? = null,
)

class ChatViewModel(
    private val id: String,
    private val actions: ChatActions,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(ChatUiState(text = saved["message"] ?: ""))
    val state = mutable.asStateFlow()
    private var older = emptyList<ChatMessage>()

    fun edit(text: String) {
        if (state.value.sending) return
        saved["message"] = text.take(1000)
        saved["clientId"] = UUID.randomUUID().toString()
        mutable.update { it.copy(text = text.take(1000), error = null) }
    }

    suspend fun refresh() {
        try {
            val page = actions.load(id)
            val previousFirst = older.firstOrNull()?.id
            val checkedOlder = mutableListOf<ChatMessage>()
            var cursor = page.messages.firstOrNull()?.id
            var hasOlder = page.hasOlder
            var rounds = 0
            while (
                previousFirst != null &&
                    cursor != null &&
                    cursor > previousFirst &&
                    hasOlder &&
                    rounds++ < 10
            ) {
                val history = actions.load(id, cursor)
                checkedOlder.addAll(history.messages)
                hasOlder = history.hasOlder
                cursor = history.messages.firstOrNull()?.id
            }
            older = checkedOlder.distinctBy { it.id }.sortedBy { it.id }
            mutable.update {
                it.copy(
                    messages =
                        (older + page.messages).distinctBy { m -> m.id }.sortedBy { m -> m.id },
                    loading = false,
                    archived = page.archived,
                    unavailable = false,
                    hasOlder = hasOlder,
                    privateAi = page.privateAi,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val lost =
                e is CircleException &&
                    e.reason in
                        listOf(
                            FailureReason.USER_BLOCKED,
                            FailureReason.NOT_FOUND,
                            FailureReason.SIGN_IN_REQUIRED,
                            FailureReason.ACCOUNT_BLOCKED,
                        )
            if (lost) older = emptyList()
            mutable.update {
                it.copy(
                    loading = false,
                    messages = if (lost) emptyList() else it.messages,
                    unavailable = lost,
                    error = e.toUiMessage(),
                )
            }
        }
    }

    fun loadOlder() {
        viewModelScope.launch {
            try {
                val first = state.value.messages.firstOrNull()?.id ?: return@launch
                val page = actions.load(id, first)
                older = (page.messages + older).distinctBy { it.id }
                mutable.update {
                    it.copy(
                        messages = (page.messages + it.messages).distinctBy { m -> m.id },
                        hasOlder = page.hasOlder,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.toUiMessage()) }
            }
        }
    }

    fun send() {
        if (
            state.value.sending ||
                state.value.text.isBlank() ||
                state.value.archived ||
                state.value.unavailable
        )
            return
        val body = state.value.text
        val clientId =
            saved.get<String>("clientId")
                ?: UUID.randomUUID().toString().also { saved["clientId"] = it }
        mutable.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                actions.send(id, clientId, body)
                saved["message"] = ""
                saved["clientId"] = UUID.randomUUID().toString()
                mutable.update { it.copy(sending = false, text = "", notice = "Message sent.") }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(sending = false, error = e.toUiMessage()) }
            }
        }
    }

    fun dismissGuide() {
        mutable.update { it.copy(guide = null) }
    }

    fun guide() {
        viewModelScope.launch {
            try {
                val g = actions.guide(id)
                mutable.update { it.copy(guide = g) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.toUiMessage()) }
            }
        }
    }
}
