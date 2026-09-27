package com.circle.app

import androidx.lifecycle.SavedStateHandle
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.CircleRepository
import com.circle.app.domain.repository.CommunityRepository
import com.circle.app.domain.usecase.CreateCircle
import com.circle.app.domain.usecase.EventActions
import com.circle.app.domain.usecase.GetCircle
import com.circle.app.presentation.create.CreateCircleViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateCircleRecoveryTest {
    private val venue = PublicVenue("map-choice", "Public library", "Town centre", 12.97, 77.64,
        false, address = "Central Library, Public Square")

    private class Circles : CircleRepository {
        var failure: Exception? = null
        var created: CircleDraft? = null
        var circle = Circle("circle", "Coffee together", "coffee", "Meet for coffee and conversation",
            "Town centre", "Cafe", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
            6, listOf(Attendee("a", "Ankit")), true, 0.0, isHost = true, revision = 4,
            venueId = "original", venueFictional = false, venueLatitude = 12.95, venueLongitude = 77.62,
            venueAddress = "Cafe, Public Square")
        override suspend fun getCircle(id: String): Circle { failure?.let { throw it }; return circle }
        override suspend fun create(draft: CircleDraft): Circle { created = draft; return circle }
        override suspend fun getNearby(query: CircleQuery) = listOf(circle)
        override suspend fun getJoined() = listOf(circle)
        override suspend fun setJoined(id: String, joined: Boolean) = Unit
    }

    private class Events : CommunityRepository {
        var edits = mutableListOf<Pair<Int, CircleDraft>>()
        var suggestions = 0
        override suspend fun venues(query: String): List<PublicVenue> = error("No global catalogue required")
        override suspend fun edit(circleId: String, revision: Int, draft: CircleDraft) { edits += revision to draft }
        override suspend fun suggest(prompt: String): ActivitySuggestion { suggestions++; error("AI not configured") }
        override suspend fun messages(circleId: String, before: Long): ChatPage = error("unused")
        override suspend fun send(circleId: String, clientId: String, body: String): ChatMessage = error("unused")
        override suspend fun guide(circleId: String): ConversationGuide = error("unused")
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
        override suspend fun recommend(query: CircleQuery) = Recommendations(emptyList(), "nearby")
    }

    private fun recreate(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
    private fun model(repo: Circles, events: Events, saved: SavedStateHandle, editing: Boolean = false) =
        CreateCircleViewModel(CreateCircle(repo), emptyList(), saved, EventActions(events), GetCircle(repo),
            editId = if (editing) "circle" else null)

    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun recreatedCreateKeepsMapVenueWithoutCatalogueOrAi() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles(); val events = Events(); val saved = SavedStateHandle()
        val first = model(repo, events, saved)
        advanceUntilIdle()
        first.edit { it.copy(title = "Sketching together", description = "Meet for a public sketching session",
            category = "Urban sketching", venueId = venue.id, venues = listOf(venue)) }
        advanceUntilIdle()
        val reopened = model(repo, events, recreate(saved))
        advanceUntilIdle()
        assertTrue(reopened.state.value.catalogReady)
        assertEquals(venue, reopened.state.value.venues.single())
        assertFalse(reopened.state.value.aiAvailable)
        reopened.suggest("Sketch tomorrow"); reopened.submit(); advanceUntilIdle()
        assertEquals(0, events.suggestions)
        assertEquals(venue.id, repo.created!!.venueId)
        assertEquals("Urban sketching", repo.created!!.category)
    }

    @Test fun recreationKeepsUnsavedHostEditsAndSelectedVenue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles(); val events = Events(); val saved = SavedStateHandle()
        val first = model(repo, events, saved, true)
        advanceUntilIdle()
        first.edit { it.copy(title = "Changed by host", minimumAge = 25, maximumAge = 40,
            venueId = venue.id, venues = listOf(venue)) }
        advanceUntilIdle()
        val reopened = model(repo, events, recreate(saved), true)
        advanceUntilIdle()
        assertEquals("Changed by host", reopened.state.value.title)
        assertEquals(venue, reopened.state.value.venues.single())
        assertEquals(25, reopened.state.value.minimumAge)
        reopened.submit(); advanceUntilIdle()
        assertEquals(4, events.edits.single().first)
        assertEquals(venue.id, events.edits.single().second.venueId)
    }

    @Test fun changedServerRevisionRequiresExplicitReloadBeforeSaving() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles(); val events = Events(); val saved = SavedStateHandle()
        val first = model(repo, events, saved, true)
        advanceUntilIdle(); first.edit { it.copy(title = "Unsent local edit") }; advanceUntilIdle()
        repo.circle = repo.circle.copy(revision = 5, title = "Newer saved title")
        val reopened = model(repo, events, recreate(saved), true)
        advanceUntilIdle()
        assertTrue(reopened.state.value.editConflict)
        assertEquals("Unsent local edit", reopened.state.value.title)
        reopened.submit(); advanceUntilIdle(); assertTrue(events.edits.isEmpty())
        reopened.reload(discardEdits = true); advanceUntilIdle()
        assertFalse(reopened.state.value.editConflict)
        assertEquals("Newer saved title", reopened.state.value.title)
        reopened.submit(); advanceUntilIdle()
        assertEquals(5, events.edits.single().first)
    }

    @Test fun failedEditLoadCanRetryWithoutLeavingScreen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Circles().apply { failure = CircleException(FailureReason.NETWORK) }
        val events = Events(); val vm = model(repo, events, SavedStateHandle(), true)
        advanceUntilIdle()
        assertFalse(vm.state.value.catalogReady); assertNotNull(vm.state.value.error)
        vm.submit(); advanceUntilIdle(); assertTrue(events.edits.isEmpty())
        repo.failure = null; vm.reload(); advanceUntilIdle()
        assertTrue(vm.state.value.catalogReady); assertNull(vm.state.value.error)
        assertEquals(repo.circle.title, vm.state.value.title)
    }
}
