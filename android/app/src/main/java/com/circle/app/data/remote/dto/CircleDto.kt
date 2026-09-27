package com.circle.app.data.remote.dto

// Transport types contain wire values, not Android UI state or domain behavior.
data class AttendeeDto(val id: String, val name: String)

data class CircleDto(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val neighborhood: String,
    val venue: String,
    val startsAt: String,
    val endsAt: String,
    val capacity: Int,
    val attendees: List<AttendeeDto>,
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
)
