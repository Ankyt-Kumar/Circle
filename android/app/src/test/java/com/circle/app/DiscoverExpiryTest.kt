package com.circle.app

import androidx.lifecycle.SavedStateHandle
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.CircleRepository
import com.circle.app.domain.repository.CommunityRepository
import com.circle.app.domain.usecase.EventActions
import com.circle.app.domain.usecase.GetNearbyCircles
import com.circle.app.presentation.discover.DiscoverViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverExpiryTest {
    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val area = Area("Selected area", 12.97, 77.64)

    private class TestClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }

    private fun circle(id: String, start: Long, end: Long, status: String = "published") =
        Circle(id, "Meet for coffee", "coffee", "Coffee and conversation", "Selected area", "Public cafe",
            now.plusSeconds(start), now.plusSeconds(end), 6, listOf(Attendee("host", "Host")), false, 100.0,
            status = status)

    private class Circles(var first: CircleFeed) : CircleRepository {
        var next = CircleFeed(emptyList())
        var failure: Exception? = null
        var reads = 0
        override suspend fun getNearbyPage(query: CircleQuery, cursor: String): CircleFeed {
            reads++; failure?.let { throw it }; return if (cursor.isEmpty()) first else next
        }
        override suspend fun getNearby(query: CircleQuery) = first.circles
        override suspend fun getJoined() = first.circles
        override suspend fun getCircle(id: String) = first.circles.first { it.id == id }
        override suspend fun create(draft: CircleDraft): Circle = error("Unused")
        override suspend fun setJoined(id: String, joined: Boolean) = Unit
    }

    private class RecommendationsSource(val result: Recommendations) : CommunityRepository {
        override suspend fun recommend(query: CircleQuery) = result
        override suspend fun venues(query: String) = emptyList<PublicVenue>()
        override suspend fun messages(circleId: String, before: Long): ChatPage = error("Unused")
        override suspend fun send(circleId: String, clientId: String, body: String): ChatMessage = error("Unused")
        override suspend fun guide(circleId: String): ConversationGuide = error("Unused")
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
        override suspend fun suggest(prompt: String): ActivitySuggestion = error("Unused")
    }

    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun onlineAndCachedFeedsExcludeCompletedStartedAndInactiveCircles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val list = listOf(circle("completed", -7200, -3600), circle("ended-now", -3600, 0),
            circle("started-now", 0, 3600), circle("ongoing", -60, 3600),
            circle("cancelled", 60, 3600, "cancelled"), circle("archived", 60, 3600, "archived"),
            circle("upcoming", 60, 3600))
        for (offline in listOf(false, true)) {
            val repo = Circles(CircleFeed(list, isOffline = offline, savedAt = now.minusSeconds(7200)))
            val vm = DiscoverViewModel(GetNearbyCircles(repo), listOf(area), SavedStateHandle(), clock = TestClock(now))
            vm.refresh(); advanceUntilIdle()
            assertEquals(listOf("upcoming"), vm.state.value.circles.map { it.id })
            assertEquals(offline, vm.state.value.offline)
            assertEquals(list, repo.getJoined()) // Discover filtering does not delete history.
        }
    }

    @Test fun visibleCardsExpireAtTheirStartWithoutAnotherNetworkRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = TestClock(now)
        val repo = Circles(CircleFeed(listOf(circle("soon", 10, 3600), circle("later", 120, 7200))))
        val vm = DiscoverViewModel(GetNearbyCircles(repo), listOf(area), SavedStateHandle(), clock = clock)
        vm.refresh(); advanceUntilIdle()
        assertEquals(10_000L, vm.nextExpiryDelayMillis())
        clock.current = now.plusSeconds(10); vm.removeInactiveCircles()
        assertEquals(listOf("later"), vm.state.value.circles.map { it.id })
        assertEquals(1, repo.reads)
        assertEquals(60_000L, vm.nextExpiryDelayMillis())
        clock.current = now.plusSeconds(120); vm.removeInactiveCircles()
        assertTrue(vm.state.value.circles.isEmpty())
    }

    @Test fun failedRefreshCannotRetainAnExpiredInMemoryCard() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = TestClock(now)
        val repo = Circles(CircleFeed(listOf(circle("soon", 10, 20))))
        val vm = DiscoverViewModel(GetNearbyCircles(repo), listOf(area), SavedStateHandle(), clock = clock)
        vm.refresh(); advanceUntilIdle()
        clock.current = now.plusSeconds(30)
        repo.failure = CircleException(FailureReason.NETWORK)
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.circles.isEmpty()); assertTrue(vm.state.value.offline)
    }

    @Test fun paginationCannotReinsertExpiredOrCancelledCards() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = TestClock(now)
        val repo = Circles(CircleFeed(listOf(circle("soon", 10, 20)), "page-two"))
        repo.next = CircleFeed(listOf(circle("soon", 10, 20), circle("cancelled", 60, 3600, "cancelled"),
            circle("later", 120, 3600)))
        val vm = DiscoverViewModel(GetNearbyCircles(repo), listOf(area), SavedStateHandle(), clock = clock)
        vm.refresh(); advanceUntilIdle()
        clock.current = now.plusSeconds(30); vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf("later"), vm.state.value.circles.map { it.id })
        assertEquals(2, repo.reads)
    }

    @Test fun forYouFiltersBothLiveAndCachedRecommendations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val list = listOf(circle("completed", -3600, -60), circle("upcoming", 60, 3600))
        for (offline in listOf(false, true)) {
            val repo = Circles(CircleFeed(emptyList()))
            val events = EventActions(RecommendationsSource(Recommendations(list, "past_joins", offline)))
            val vm = DiscoverViewModel(GetNearbyCircles(repo), listOf(area), SavedStateHandle(),
                events = events, clock = TestClock(now))
            vm.setForYou(true); advanceUntilIdle()
            assertEquals(listOf("upcoming"), vm.state.value.circles.map { it.id })
            assertEquals(offline, vm.state.value.offline)
            assertEquals(0, repo.reads)
        }
    }
}
