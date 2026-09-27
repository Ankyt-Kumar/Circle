package com.circle.app

import androidx.lifecycle.SavedStateHandle
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.CircleRepository
import com.circle.app.domain.usecase.*
import com.circle.app.presentation.detail.CircleDetailViewModel
import com.circle.app.presentation.discover.DiscoverViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CircleLifecycleViewModelTest {
    private class Circles : CircleRepository {
        var exists = true
        var calls = 0
        var networkFailure = false
        var lastQuery: CircleQuery? = null
        var lastMember = false
        private var circle = Circle("c", "Coffee", "coffee", "Sample public meetup", "Indiranagar", "Sample café",
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 6,
            listOf(Attendee("a", "Ankit"), Attendee("b", "Maya")), true, 0.0, true)
        override suspend fun getCircle(id: String): Circle {
            if (!exists) throw CircleException(FailureReason.NOT_FOUND)
            if (networkFailure) throw CircleException(FailureReason.NETWORK)
            return circle
        }
        override suspend fun getNearby(query: CircleQuery): List<Circle> { lastQuery = query; return if (exists) listOf(circle) else emptyList() }
        override suspend fun getJoined() = if (exists) listOf(circle) else emptyList()
        override suspend fun create(draft: CircleDraft) = error("Not used")
        override suspend fun setJoined(id: String, joined: Boolean) {
            calls++
            if (networkFailure) throw CircleException(FailureReason.NETWORK)
            if (!joined) {if (lastMember) exists = false else circle = circle.copy(joined = false, isHost = false, attendees = listOf(Attendee("b", "Maya")))}
        }
    }
    @After fun resetMain() { Dispatchers.resetMain() }
    @Test fun lastMemberLeaveAndMissingCircleClearTheDetailScreen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles().apply { lastMember = true }
        val vm = CircleDetailViewModel("c", GetCircle(repo), UpdateCircleMembership(repo))
        vm.refresh(); advanceUntilIdle(); assertTrue(vm.state.value.circle!!.isHost)
        vm.toggleJoin(); advanceUntilIdle()
        assertTrue(vm.state.value.deleted); assertNull(vm.state.value.circle); assertNull(vm.state.value.error)
        vm.toggleJoin(); advanceUntilIdle(); assertEquals(1, repo.calls)
        val otherMember = CircleDetailViewModel("c", GetCircle(repo), UpdateCircleMembership(repo))
        otherMember.refresh(); advanceUntilIdle(); assertTrue(otherMember.state.value.deleted)
    }
    @Test fun hostLeaveKeepsTheCircleAndClearsOldHostMembership() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles(); val vm = CircleDetailViewModel("c", GetCircle(repo), UpdateCircleMembership(repo))
        vm.refresh(); advanceUntilIdle(); vm.toggleJoin(); advanceUntilIdle()
        assertFalse(vm.state.value.deleted); assertFalse(vm.state.value.circle!!.isHost); assertFalse(vm.state.value.circle!!.joined)
        assertEquals("b", vm.state.value.circle!!.attendees.single().id); assertNull(vm.state.value.error)
    }
    @Test fun uncertainNetworkResultRequiresReloadBeforeAnotherToggle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles(); val vm = CircleDetailViewModel("c", GetCircle(repo), UpdateCircleMembership(repo))
        vm.refresh(); advanceUntilIdle(); repo.networkFailure = true
        vm.toggleJoin(); advanceUntilIdle(); assertTrue(vm.state.value.refreshRequired)
        vm.toggleJoin(); advanceUntilIdle(); assertEquals(1, repo.calls)
        repo.networkFailure = false; repo.exists = false
        vm.refresh(); advanceUntilIdle(); assertTrue(vm.state.value.deleted)
    }
    @Test fun savedDiscoverDefaultsApplyWithoutResettingTemporaryFilters() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles()
        val areas = listOf(Area("Koramangala", 12.9352, 77.6245), Area("Indiranagar", 12.9719, 77.6412))
        val defaults = UserPreferences(listOf("coffee"), "Indiranagar", 3, true, COMMUNITY_TERMS_VERSION, "1995-06-15", "male", 12.9719, 77.6412)
        val vm = DiscoverViewModel(GetNearbyCircles(repo), areas, SavedStateHandle(), defaults)
        assertEquals("Indiranagar", vm.state.value.area.name); assertEquals(3, vm.state.value.radius)
        vm.chooseRadius(7); advanceUntilIdle(); vm.applyPreferences(defaults)
        assertEquals(7, vm.state.value.radius)
        vm.applyPreferences(defaults.copy(areaName = "Koramangala", radiusKm = 2)); advanceUntilIdle()
        assertEquals("Koramangala", repo.lastQuery!!.area.name); assertEquals(2, repo.lastQuery!!.radiusKm)
    }
}
