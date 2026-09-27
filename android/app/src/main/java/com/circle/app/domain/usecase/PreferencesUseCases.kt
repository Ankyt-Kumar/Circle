package com.circle.app.domain.usecase

import com.circle.app.domain.model.OnboardingDraft
import com.circle.app.domain.model.PreferenceOptions
import com.circle.app.domain.repository.AccountRepository

enum class OnboardingIssue { NAME, AGE, GENDER, TERMS, INTERESTS, AREA, RADIUS }
class OnboardingException(val issue: OnboardingIssue) : Exception()

fun onboardingIssue(draft: OnboardingDraft, throughStep: Int = 2): OnboardingIssue? = when {
    !validFirstName(draft.firstName.trim()) -> OnboardingIssue.NAME
    !draft.adultConfirmed || com.circle.app.domain.model.ageOn(draft.birthDate)?.let { it in 18..100 } != true -> OnboardingIssue.AGE
    draft.gender !in PreferenceOptions.genders -> OnboardingIssue.GENDER
    !draft.termsAccepted -> OnboardingIssue.TERMS
    throughStep >= 1 && (draft.interests.size !in 1..4 || draft.interests.distinct().size != draft.interests.size ||
        draft.interests.any { it !in PreferenceOptions.interests }) -> OnboardingIssue.INTERESTS
    throughStep >= 2 && (draft.areaName.isBlank() || draft.areaName.length > 120 || !com.circle.app.domain.model.validCoordinates(draft.latitude,draft.longitude)) -> OnboardingIssue.AREA
    throughStep >= 2 && draft.radiusKm !in 1..10 -> OnboardingIssue.RADIUS
    else -> null
}

class SaveOnboarding(private val repository: AccountRepository) {
    suspend operator fun invoke(draft: OnboardingDraft): com.circle.app.domain.model.Account {
        onboardingIssue(draft)?.let { throw OnboardingException(it) }
        return repository.savePreferences(draft.copy(firstName = draft.firstName.trim()))
    }
}

class ObservePreferences(private val repository: AccountRepository) {
    operator fun invoke(accountId: String) = repository.observePreferences(accountId)
}

class UpdateDiscoveryPreferences(private val repository: AccountRepository) {
    suspend operator fun invoke(area: com.circle.app.domain.model.Area, radius: Int): com.circle.app.domain.model.Account {
        require(com.circle.app.domain.model.validCoordinates(area.latitude,area.longitude))
        require(area.name.isNotBlank() && area.name.length <= 120)
        require(radius in 1..10)
        return repository.saveLocation(area.copy(name=area.name.trim()), radius)
    }
}
