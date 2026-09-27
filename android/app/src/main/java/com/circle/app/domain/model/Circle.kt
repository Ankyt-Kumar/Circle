package com.circle.app.domain.model

import java.time.Instant

data class Circle(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val neighborhood: String,
    val venue: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val attendees: List<Attendee>,
    val joined: Boolean,
    val distanceM: Double,
    val isHost: Boolean = false,
    val status: String = "published",
    val revision: Int = 1,
    val venueId: String = "",
    val venueFictional: Boolean = true,
    val venueLatitude: Double? = null,
    val venueLongitude: Double? = null,
    val venueAddress: String = "",
    val minimumAge: Int = 18, val maximumAge: Int = 100,
    val audience: String = "everyone", val eligible: Boolean = true,
) {
    // Discover mirrors the backend's upcoming-only policy, including cached data.
    // History/details keep the original record after it starts or finishes.
    fun isDiscoverableAt(now: Instant): Boolean =
        status == "published" && startsAt.isAfter(now) && endsAt.isAfter(now)

    val spots: Int
        get() = (capacity - attendees.size).coerceAtLeast(0)
}
