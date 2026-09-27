package com.circle.app

import androidx.lifecycle.SavedStateHandle
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.AccountRepository
import com.circle.app.domain.usecase.*
import com.circle.app.presentation.onboarding.OnboardingViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTest {
    private val complete = OnboardingDraft(" Ankit ", true, true, listOf("coffee"), "Indiranagar", 3, "1995-06-15", "male", 12.9719, 77.6412)
    private class Accounts : AccountRepository {
        var calls = 0
        var saved: OnboardingDraft? = null
        var pending: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var incompleteResponse = false
        override suspend fun getAccount() = Account("a", "Member", false, "password")
        override suspend fun saveLocation(area: Area, radiusKm: Int) = error("Not used")
        override suspend fun saveFirstName(name: String) = error("Not used")
        override fun observePreferences(accountId: String) = flowOf<UserPreferences?>(null)
        override suspend fun savePreferences(draft: OnboardingDraft): Account {
            calls++; pending?.await(); failure?.let { throw it }; saved = draft
            return Account("a", draft.firstName, true, "password", onboardingComplete = !incompleteResponse,
                preferences = UserPreferences(draft.interests, draft.areaName, draft.radiusKm, draft.adultConfirmed, COMMUNITY_TERMS_VERSION, draft.birthDate, draft.gender, draft.latitude, draft.longitude))
        }
    }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun invalidPreferencesNeverReachRepository() = runTest {
        val repo = Accounts(); val save = SaveOnboarding(repo)
        val invalid = listOf(
            complete.copy(firstName = "123"), complete.copy(adultConfirmed = false),
            complete.copy(termsAccepted = false), complete.copy(interests = emptyList()),
            complete.copy(interests = listOf("coffee", "coffee")), complete.copy(interests = listOf("unknown")),
            complete.copy(areaName = " "), complete.copy(radiusKm = 0), complete.copy(radiusKm = 11),
        )
        invalid.forEach { try { save(it); fail("Invalid onboarding sent") } catch (_: OnboardingException) { } }
        assertEquals(0, repo.calls)
        assertTrue(save(complete).onboardingComplete)
        assertEquals("Ankit", repo.saved!!.firstName)
    }

    @Test fun stagedOnboardingRetainsDraftAndPreventsDuplicateSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Accounts(); val handle = SavedStateHandle()
        var vm = OnboardingViewModel(repo.getAccount(), SaveOnboarding(repo), handle)
        vm.next(); assertEquals(0, vm.state.value.step); assertNotNull(vm.state.value.error)
        vm.name("Ankit"); vm.birthDate("1995-06-15"); vm.gender("male"); vm.adult(true); vm.terms(true); vm.next()
        assertEquals(1, vm.state.value.step)
        vm.next(); assertEquals(1, vm.state.value.step)
        vm.interest("games"); vm.next(); vm.location(Area("Indiranagar",12.9719,77.6412)); vm.radius(3)
        // Restoring the ViewModel uses the saved draft and step.
        vm = OnboardingViewModel(repo.getAccount(), SaveOnboarding(repo), handle)
        assertEquals(2, vm.state.value.step); assertEquals(listOf("games"), vm.state.value.draft.interests)
        repo.pending = CompletableDeferred()
        vm.next(); runCurrent(); vm.next(); vm.name("Changed")
        assertEquals(1, repo.calls); assertTrue(vm.state.value.saving)
        repo.pending!!.complete(Unit); advanceUntilIdle()
        assertEquals("Ankit", vm.state.value.savedAccount!!.firstName)
        assertEquals(3, vm.state.value.savedAccount!!.preferences!!.radiusKm)
    }

    @Test fun failedSaveKeepsChoicesAndOnlyServerCompletionUnlocksApp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Accounts().apply { failure = CircleException(FailureReason.NETWORK) }
        val account = Account("a", "Ankit", true, "password", preferences = UserPreferences(listOf("coffee"), "Indiranagar", 3, true, COMMUNITY_TERMS_VERSION, "1995-06-15", "male", 12.9719, 77.6412))
        val vm = OnboardingViewModel(account, SaveOnboarding(repo), SavedStateHandle(mapOf("step" to 2)))
        vm.next(); advanceUntilIdle()
        assertNull(vm.state.value.savedAccount); assertNotNull(vm.state.value.error)
        assertEquals("Indiranagar", vm.state.value.draft.areaName)
        repo.failure = null; repo.incompleteResponse = true
        vm.next(); advanceUntilIdle(); assertNull(vm.state.value.savedAccount)
        repo.incompleteResponse = false
        vm.next(); advanceUntilIdle(); assertNotNull(vm.state.value.savedAccount)
        assertEquals(3, repo.calls)
    }
}
