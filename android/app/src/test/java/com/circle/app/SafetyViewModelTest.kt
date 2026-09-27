package com.circle.app

import androidx.lifecycle.SavedStateHandle
import com.circle.app.domain.error.*
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.*
import com.circle.app.domain.usecase.*
import com.circle.app.presentation.safety.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyViewModelTest {
    private class Safety : SafetyRepository {
        var failBlock = false
        var blocks = 0
        var users = listOf(BlockedUser("b", "Maya"))

        override suspend fun block(circleId: String, userId: String) {
            blocks++
            if (failBlock) throw CircleException(FailureReason.NETWORK)
        }

        override suspend fun unblock(userId: String) {
            if (failBlock) throw CircleException(FailureReason.NETWORK)
            users = users.filterNot { it.id == userId }
        }

        override suspend fun blockedUsers() = users
    }

    private class Circles : CircleRepository {
        override suspend fun getCircle(id: String) =
            Circle(
                id,
                "Coffee together",
                "coffee",
                "Public sample meetup",
                "Indiranagar",
                "Sample café",
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200),
                6,
                listOf(Attendee("b", "Maya")),
                true,
                0.0,
            )

        override suspend fun create(draft: CircleDraft) = error("Unused")

        override suspend fun getNearby(query: CircleQuery) = emptyList<Circle>()

        override suspend fun getJoined() = emptyList<Circle>()

        override suspend fun setJoined(id: String, joined: Boolean) = Unit
    }

    private fun vm(
        repo: Safety,
        saved: SavedStateHandle = SavedStateHandle(mapOf("circleId" to "circle", "userId" to "b")),
    ) = SafetyViewModel(GetCircle(Circles()), BlockUser(repo), saved)

    @After
    fun reset() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedBlockKeepsTheScreenOpenAndCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Safety().apply { failBlock = true }
        val vm = vm(repo)
        advanceUntilIdle()
        vm.block()
        advanceUntilIdle()
        assertFalse(vm.state.value.blocked)
        assertNotNull(vm.state.value.error)
        repo.failBlock = false
        vm.block()
        advanceUntilIdle()
        assertTrue(vm.state.value.blocked)
        assertEquals(2, repo.blocks)
    }

    @Test
    fun failedUnblockRetainsPersonUntilServerConfirms() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Safety().apply { failBlock = true }
        val vm = BlockedUsersViewModel(GetBlockedUsers(repo), UnblockUser(repo))
        advanceUntilIdle()
        vm.unblock("b")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.users.size)
        repo.failBlock = false
        vm.unblock("b")
        advanceUntilIdle()
        assertTrue(vm.state.value.users.isEmpty())
    }
}
