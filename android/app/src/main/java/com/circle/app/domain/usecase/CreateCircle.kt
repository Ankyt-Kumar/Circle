package com.circle.app.domain.usecase

import com.circle.app.domain.model.CircleDraft
import com.circle.app.domain.model.PreferenceOptions
import com.circle.app.domain.repository.CircleRepository
import java.time.Clock
import java.time.Duration

class CreateCircle(private val repository: CircleRepository, private val clock: Clock = Clock.systemUTC()) {
    suspend operator fun invoke(draft: CircleDraft): com.circle.app.domain.model.Circle {
        require(draft.title.trim().length in 3..100) { "Enter a title of 3–100 characters." }
        require(draft.description.trim().length in 10..1000) { "Enter a description of 10–1000 characters." }
        require(com.circle.app.domain.model.validCategory(draft.category)) { "Choose an activity category." }
        require(draft.minimumAge in 18..75 && draft.maximumAge in draft.minimumAge..75 && draft.audience in PreferenceOptions.audiences) { "Choose a valid adult age range and audience." }
        require(draft.venueId.isNotBlank()) { "Choose a public venue." }
        require(draft.capacity in 4..8) { "Choose a capacity of 4–8 people." }
        val now = clock.instant()
        require(draft.startsAt.isAfter(now) && !draft.startsAt.isAfter(now.plus(Duration.ofDays(7)))) { "Choose a future start within the next 7 days." }
        val duration = Duration.between(draft.startsAt, draft.endsAt)
        require(!duration.isNegative && !duration.isZero && duration <= Duration.ofHours(6)) { "Choose a duration up to 6 hours." }
        return repository.create(draft.copy(title = draft.title.trim(), description = draft.description.trim()))
    }
}
