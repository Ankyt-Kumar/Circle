package com.circle.app

import com.circle.app.data.mapper.toDomain
import com.circle.app.data.remote.ApiException
import com.circle.app.data.remote.CircleApi
import com.circle.app.data.remote.HttpCircleApi
import com.circle.app.data.remote.dto.AttendeeDto
import com.circle.app.data.remote.dto.CircleDto
import com.circle.app.data.repository.DefaultCircleRepository
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.Area
import com.circle.app.domain.model.CircleQuery
import com.circle.app.domain.usecase.GetNearbyCircles
import com.circle.app.domain.usecase.UpdateCircleMembership
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArchitectureContractTest {
    private val area = Area("Koramangala", 12.9352, 77.6245)

    @Test
    fun invalidRadiusDoesNotReachDataSource() = runBlocking {
        val api = FakeApi()
        val useCase = GetNearbyCircles(DefaultCircleRepository(api))
        try {
            useCase(CircleQuery(area, radiusKm = 11))
            fail("Invalid radius must be rejected before a network request")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, api.searchCount)
    }

    @Test
    fun queryAndDomainModelCrossTheRepositoryBoundary() = runBlocking {
        val api = FakeApi()
        val circles = GetNearbyCircles(DefaultCircleRepository(api))(CircleQuery(area, 3, "coffee"))
        assertEquals(listOf(12.9352, 77.6245, 3, "coffee"), api.lastQuery)
        val circle = circles.single()
        assertEquals(Instant.parse("2026-09-11T08:00:00Z"), circle.startsAt)
        assertEquals("Riya", circle.attendees.single().name)
        assertEquals(5, circle.spots)
        assertFalse(circle.joined)
    }

    @Test
    fun conflictBecomesADomainFailure() = runBlocking {
        val api = FakeApi().apply { failure = ApiException(409) }
        try {
            UpdateCircleMembership(DefaultCircleRepository(api))("coffee-01", true)
            fail("Expected domain conflict")
        } catch (e: CircleException) {
            assertEquals(FailureReason.CONFLICT, e.reason)
        }
    }

    @Test
    fun cancellationIsNotConvertedToAnError() = runBlocking {
        val original = CancellationException("Search superseded")
        val api = FakeApi().apply { failure = original }
        try {
            GetNearbyCircles(DefaultCircleRepository(api))(CircleQuery(area))
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertSame(original, e)
        }
    }

    @Test
    fun mapperDoesNotShareMutableTransportAttendees() {
        val people = mutableListOf(AttendeeDto("1", "Riya"))
        val dto = sample().copy(attendees = people)
        val domain = dto.toDomain()
        people.clear()
        assertEquals(1, domain.attendees.size)
    }

    @Test
    fun realHttpDataSourceAgainstLocalDemoServer() = runBlocking {
        val url = System.getenv("CIRCLE_TEST_API_URL")
        assumeTrue("Set CIRCLE_TEST_API_URL to a disposable local demo API", !url.isNullOrBlank())
        val repository = DefaultCircleRepository(HttpCircleApi(checkNotNull(url), Dispatchers.IO))
        val nearby = GetNearbyCircles(repository)(CircleQuery(area, 5))
        assertEquals("coffee-01", nearby.first().id)
        val before = repository.getCircle("coffee-01")
        try {
            repository.setJoined(before.id, true)
            repository.setJoined(before.id, true)
            val joined = repository.getCircle(before.id)
            assertTrue(joined.joined)
            assertEquals(before.attendees.size + if (before.joined) 0 else 1, joined.attendees.size)
            assertTrue(repository.getJoined().any { it.id == before.id })
            repository.setJoined(before.id, false)
            assertFalse(repository.getCircle(before.id).joined)
        } finally {
            repository.setJoined(before.id, before.joined)
        }
    }

    @Test
    fun invalidCreationDoesNotReachApi() = runBlocking {
        val api = FakeApi()
        val useCase = com.circle.app.domain.usecase.CreateCircle(DefaultCircleRepository(api))
        val start = Instant.now().plusSeconds(3600)
        try {
            useCase(
                com.circle.app.domain.model.CircleDraft(
                    "test-request-123456",
                    "",
                    "A relaxed sample meetup.",
                    "coffee",
                    "indiranagar-cafe",
                    start,
                    start.plusSeconds(3600),
                    6,
                )
            )
            fail("Invalid creation reached repository")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, api.createCount)
    }

    @Test
    fun createThroughRealHttpAndRetry() = runBlocking {
        val url = System.getenv("CIRCLE_TEST_API_URL")
        assumeTrue("Requires disposable Go API", !url.isNullOrBlank())
        val repository = DefaultCircleRepository(HttpCircleApi(checkNotNull(url), Dispatchers.IO))
        val create = com.circle.app.domain.usecase.CreateCircle(repository)
        val start =
            Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val draft =
            com.circle.app.domain.model.CircleDraft(
                java.util.UUID.randomUUID().toString(),
                "Coffee together",
                "A relaxed sample meetup for new neighbors.",
                "coffee",
                "indiranagar-cafe",
                start,
                start.plusSeconds(3600),
                6,
            )
        val circle = create(draft)
        assertTrue(circle.joined)
        assertTrue(circle.isHost)
        assertEquals(5, circle.spots)
        assertEquals(circle.id, create(draft).id)
        assertTrue(repository.getJoined().any { it.id == circle.id })
        assertEquals("published", circle.status)
        assertTrue(
            repository
                .getNearby(CircleQuery(Area("Indiranagar", 12.9719, 77.6412), 1, "coffee"))
                .any { it.id == circle.id }
        )
        assertEquals(draft.title, repository.getCircle(circle.id).title)
        repository.setJoined(circle.id, false)
        repository.setJoined(circle.id, false)
        assertFalse(repository.getJoined().any { it.id == circle.id })
        assertFalse(
            repository
                .getNearby(CircleQuery(Area("Indiranagar", 12.9719, 77.6412), 1, "coffee"))
                .any { it.id == circle.id }
        )
        try {
            repository.getCircle(circle.id)
            fail("Deleted circle still available")
        } catch (e: CircleException) {
            assertEquals(FailureReason.NOT_FOUND, e.reason)
        }
        try {
            create(draft)
            fail("Old create request resurrected circle")
        } catch (e: CircleException) {
            assertEquals(FailureReason.NOT_FOUND, e.reason)
        }
    }

    @Test
    fun safetyRepositoryUsesRealHttpTransport() = runBlocking {
        val url = System.getenv("CIRCLE_TEST_API_URL")
        assumeTrue("Requires disposable Go API", !url.isNullOrBlank())
        val transport = com.circle.app.data.remote.HttpTransport(checkNotNull(url))
        val circles = DefaultCircleRepository(HttpCircleApi(transport))
        val safety = com.circle.app.data.repository.DefaultSafetyRepository(transport)
        val circle = circles.getCircle("coffee-01")
        val target = circle.attendees.first { it.id != "00000000-0000-0000-0000-000000000001" }
        try {
            safety.block(circle.id, target.id)
            safety.block(circle.id, target.id)
            assertTrue(safety.blockedUsers().any { it.id == target.id })
            try {
                circles.getCircle(circle.id)
                fail("Blocked detail exposed")
            } catch (e: CircleException) {
                assertEquals(FailureReason.NOT_FOUND, e.reason)
            }
            try {
                circles.setJoined(circle.id, true)
                fail("Blocked join accepted")
            } catch (e: CircleException) {
                assertEquals(FailureReason.USER_BLOCKED, e.reason)
            }
        } finally {
            safety.unblock(target.id)
        }
        assertFalse(safety.blockedUsers().any { it.id == target.id })
        assertEquals(circle.id, circles.getCircle(circle.id).id)
    }

    private class FakeApi : CircleApi {
        var createCount = 0

        override suspend fun create(
            draft: com.circle.app.data.remote.dto.CreateCircleDto
        ): CircleDto {
            createCount++
            return sample()
        }

        var searchCount = 0
        var lastQuery: List<Any> = emptyList()
        var failure: Exception? = null

        override suspend fun search(
            latitude: Double,
            longitude: Double,
            radiusKm: Int,
            category: String,
        ): List<CircleDto> {
            failure?.let { throw it }
            searchCount++
            lastQuery = listOf(latitude, longitude, radiusKm, category)
            return listOf(sample())
        }

        override suspend fun detail(id: String): CircleDto = sample()

        override suspend fun mine(): List<CircleDto> = listOf(sample())

        override suspend fun setJoined(id: String, joined: Boolean) {
            failure?.let { throw it }
        }
    }

    companion object {
        private fun sample() =
            CircleDto(
                "coffee-01",
                "Coffee",
                "coffee",
                "Sample",
                "Koramangala",
                "Sample café",
                "2026-09-11T08:00:00Z",
                "2026-09-11T09:30:00Z",
                6,
                listOf(AttendeeDto("1", "Riya")),
                false,
                250.0,
            )
    }
}
