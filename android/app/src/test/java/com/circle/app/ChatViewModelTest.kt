package com.circle.app

import androidx.lifecycle.SavedStateHandle
import com.circle.app.domain.error.*
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.CommunityRepository
import com.circle.app.domain.usecase.ChatActions
import com.circle.app.presentation.chat.ChatViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private class Repository : CommunityRepository {
        var page = ChatPage(emptyList(), false, false, false)
        var loadFailure: Exception? = null
        var failSend = false
        val sends = mutableListOf<Pair<String, String>>()

        override suspend fun messages(circleId: String, before: Long): ChatPage {
            loadFailure?.let { throw it }
            return page
        }

        override suspend fun send(circleId: String, clientId: String, body: String): ChatMessage {
            sends += clientId to body
            if (failSend) throw CircleException(FailureReason.NETWORK)
            return ChatMessage(1, clientId, "a", "Tester", body, "allowed", Instant.now())
        }

        override suspend fun venues(query: String) = emptyList<PublicVenue>()

        override suspend fun guide(circleId: String) = ConversationGuide(emptyList(), false, 0, 0)

        override suspend fun edit(circleId: String, revision: Int, draft: CircleDraft) = Unit

        override suspend fun cancel(circleId: String, revision: Int) = Unit

        override suspend fun feedback(circleId: String, attended: Boolean, rating: String) = Unit

        override suspend fun notices() = emptyList<CircleNotice>()

        override suspend fun readNotice(id: Long) = Unit

        override suspend fun noticePreferences() = NoticePreferences()

        override suspend fun saveNoticePreferences(value: NoticePreferences) = Unit

        override suspend fun registerDevice(token: String) = Unit

        override suspend fun removeDevice(token: String) = Unit

        override suspend fun stats() = MemberStats(0, 0, 0)

        override suspend fun deleteAccount() = Unit

        override suspend fun suggest(prompt: String): ActivitySuggestion = error("unused")

        override suspend fun recommend(query: CircleQuery) = Recommendations(emptyList(), "nearby")
    }

    @After
    fun reset() {
        Dispatchers.resetMain()
    }

    @Test
    fun retryAfterRecreationKeepsMessageId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = Repository().apply { failSend = true }
        val saved = SavedStateHandle()
        val vm = ChatViewModel("circle", ChatActions(repository), saved)
        vm.edit("Looking forward to coffee")
        vm.send()
        advanceUntilIdle()
        assertEquals("Looking forward to coffee", vm.state.value.text)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val recreated = ChatViewModel("circle", ChatActions(repository), restored)
        repository.failSend = false
        recreated.send()
        advanceUntilIdle()
        assertEquals(repository.sends[0], repository.sends[1])
        assertEquals("", recreated.state.value.text)
    }

    @Test
    fun losingMembershipClearsMessagesAndStopsSending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository =
            Repository().apply {
                page =
                    ChatPage(
                        listOf(
                            ChatMessage(
                                1,
                                "client",
                                "a",
                                "Tester",
                                "Hello",
                                "allowed",
                                Instant.now(),
                            )
                        ),
                        false,
                        false,
                        false,
                    )
            }
        val vm = ChatViewModel("circle", ChatActions(repository), SavedStateHandle())
        vm.refresh()
        assertEquals(1, vm.state.value.messages.size)
        repository.loadFailure = CircleException(FailureReason.USER_BLOCKED)
        vm.refresh()
        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(vm.state.value.unavailable)
        vm.edit("Should not send")
        vm.send()
        advanceUntilIdle()
        assertTrue(repository.sends.isEmpty())
    }

    @Test
    fun archivedChatRejectsSendOnTheClient() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = Repository().apply { page = page.copy(archived = true) }
        val vm = ChatViewModel("circle", ChatActions(repository), SavedStateHandle())
        vm.refresh()
        vm.edit("After the event")
        vm.send()
        advanceUntilIdle()
        assertTrue(repository.sends.isEmpty())
    }
}
