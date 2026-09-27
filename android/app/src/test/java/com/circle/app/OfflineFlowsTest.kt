package com.circle.app

import androidx.lifecycle.SavedStateHandle
import com.circle.app.data.local.SessionDiskCache
import com.circle.app.data.remote.*
import com.circle.app.data.remote.dto.*
import com.circle.app.data.repository.DefaultCircleRepository
import com.circle.app.domain.error.*
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.*
import com.circle.app.domain.usecase.*
import com.circle.app.presentation.create.CreateCircleViewModel
import com.circle.app.presentation.discover.DiscoverViewModel
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFlowsTest {
    private class Api : CircleApi {
        var failure: Exception? = null
        private val circle =
            CircleDto(
                "c",
                "Coffee",
                "coffee",
                "Meet for coffee",
                "Indiranagar",
                "Cafe",
                "2026-09-20T10:00:00Z",
                "2026-09-20T11:00:00Z",
                6,
                listOf(AttendeeDto("a", "A")),
                true,
                100.0,
            )

        override suspend fun search(
            latitude: Double,
            longitude: Double,
            radiusKm: Int,
            category: String,
        ): List<CircleDto> {
            failure?.let { throw it }
            return listOf(circle)
        }

        override suspend fun mine() = search(0.0, 0.0, 1, "")

        override suspend fun detail(id: String) = mine().single()

        override suspend fun create(draft: CreateCircleDto) = detail("c")

        override suspend fun setJoined(id: String, joined: Boolean) {
            failure?.let { throw it }
        }
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun onlyNetworkFailureUsesOwnSavedFeedAndMutationsStillRequireServer() = runTest {
        val root = Files.createTempDirectory("circle-repository").toFile()
        try {
            var session = AuthSession("a", 1)
            val api = Api()
            val repo = DefaultCircleRepository(api, SessionDiskCache(root) { session })
            val query = CircleQuery(Area("Indiranagar", 12.97, 77.64), 5, "coffee")
            assertFalse(repo.getNearbyPage(query, "").isOffline)
            repo.getJoinedFeed()
            api.failure = IOException("offline")
            val cached = repo.getNearbyPage(query, "")
            assertTrue(cached.isOffline)
            assertEquals("c", cached.circles.single().id)
            assertNotNull(cached.savedAt)
            assertEquals("", cached.nextCursor)
            assertTrue(repo.getJoinedFeed().isOffline)
            try {
                repo.setJoined("c", false)
                fail("Membership must require backend")
            } catch (e: CircleException) {
                assertEquals(FailureReason.NETWORK, e.reason)
            }
            try {
                repo.getNearbyPage(query, "next")
                fail("Later page must not use page one")
            } catch (e: CircleException) {
                assertEquals(FailureReason.NETWORK, e.reason)
            }
            api.failure = ApiException(401)
            try {
                repo.getNearbyPage(query, "")
                fail("Auth rejection must not use cache")
            } catch (e: CircleException) {
                assertEquals(FailureReason.SIGN_IN_REQUIRED, e.reason)
            }
            session = AuthSession("b", 2)
            api.failure = IOException("offline")
            try {
                repo.getJoinedFeed()
                fail("B must not see A's circles")
            } catch (e: CircleException) {
                assertEquals(FailureReason.NETWORK, e.reason)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun privateCacheKeysDoNotExposeCoordinatesAndLeaveInvalidatesSavedMemberships() = runTest {
        val root = Files.createTempDirectory("circle-gps").toFile()
        try {
            val api = Api()
            val repo = DefaultCircleRepository(api, SessionDiskCache(root) { AuthSession("a") })
            repo.getNearbyPage(CircleQuery(Area("Current area", 12.971234, 77.641234), 5, ""), "")
            assertTrue(root.walkTopDown().filter { it.isFile }.none { "12.971234" in it.name || "77.641234" in it.readText() })
            api.failure = IOException("offline")
            try {
                repo.getNearbyPage(CircleQuery(Area("Current area", 13.0, 77.641234), 5, ""), "")
                fail("A different saved location must not reuse old results")
            } catch (e: CircleException) { assertEquals(FailureReason.NETWORK, e.reason) }
            api.failure = null
            repo.getJoinedFeed()
            repo.setJoined("c", false)
            api.failure = IOException("offline")
            try {
                repo.getJoinedFeed()
                fail("Left circle must not reappear from disk")
            } catch (e: CircleException) {
                assertEquals(FailureReason.NETWORK, e.reason)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private class Drafts : DraftRepository {
        var form: SavedCircleForm? = null

        override suspend fun load() = form

        override suspend fun save(form: SavedCircleForm) {
            this.form = form
        }

        override suspend fun clear() {
            form = null
        }
    }

    private class Circles : CircleRepository {
        var disconnected = true
        val ids = mutableListOf<String>()
        val queries = mutableListOf<Pair<String, String>>()

        fun circle(id: String) =
            Circle(
                id,
                "Coffee",
                "coffee",
                "Meet for coffee",
                "Indiranagar",
                "Cafe",
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200),
                6,
                emptyList(),
                true,
                0.0,
            )

        override suspend fun create(draft: CircleDraft): Circle {
            ids += draft.requestId
            if (disconnected) throw CircleException(FailureReason.NETWORK)
            return circle("created")
        }

        override suspend fun getNearbyPage(query: CircleQuery, cursor: String): CircleFeed {
            queries += query.category to cursor
            if (cursor.isEmpty()) return CircleFeed(listOf(circle("a")), "next")
            if (disconnected) throw CircleException(FailureReason.NETWORK)
            return CircleFeed(listOf(circle("a"), circle("b")))
        }

        override suspend fun getNearby(query: CircleQuery) = emptyList<Circle>()

        override suspend fun getJoined() = emptyList<Circle>()

        override suspend fun getCircle(id: String) = circle(id)

        override suspend fun setJoined(id: String, joined: Boolean) = Unit
    }

    @Test
    fun restartingAfterUncertainCreationKeepsRequestIdUntilConfirmed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val drafts = Drafts()
        val circles = Circles()
        val venues = listOf(PublicVenue("venue", "Cafe", "Indiranagar"))
        val first =
            CreateCircleViewModel(
                CreateCircle(circles),
                venues,
                SavedStateHandle(),
                drafts = drafts,
            )
        advanceUntilIdle()
        first.edit {
            it.copy(title = "Coffee together", description = "Meet for coffee and a conversation")
        }
        advanceUntilIdle()
        first.submit()
        advanceUntilIdle()
        assertNotNull(first.state.value.error)
        assertNotNull(drafts.form)
        val reopened =
            CreateCircleViewModel(
                CreateCircle(circles),
                venues,
                SavedStateHandle(),
                drafts = drafts,
            )
        advanceUntilIdle()
        assertEquals("Coffee together", reopened.state.value.title)
        circles.disconnected = false
        reopened.submit()
        advanceUntilIdle()
        assertEquals(2, circles.ids.size)
        assertEquals(circles.ids[0], circles.ids[1])
        assertEquals("created", reopened.state.value.createdId)
        assertNull(drafts.form)
    }

    @Test
    fun loadMoreRetriesWithoutLosingFirstPageAndFilterResetsCursor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val circles = Circles()
        val vm =
            DiscoverViewModel(
                GetNearbyCircles(circles),
                listOf(Area("Indiranagar", 12.97, 77.64)),
                SavedStateHandle(),
            )
        vm.refresh()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.state.value.circles.map { it.id })
        assertNotNull(vm.state.value.moreError)
        assertEquals("next", vm.state.value.nextCursor)
        circles.disconnected = false
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.state.value.circles.map { it.id })
        assertNull(vm.state.value.moreError)
        assertEquals("", vm.state.value.nextCursor)
        vm.chooseCategory("games")
        advanceUntilIdle()
        assertEquals("games" to "", circles.queries.last())
        assertEquals(listOf("a"), vm.state.value.circles.map { it.id })
    }
}
