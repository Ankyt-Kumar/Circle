package com.circle.app.domain.model

import java.time.Instant

data class PublicVenue(
    val id: String,
    val name: String,
    val neighborhood: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val fictional: Boolean = true,
    val sourceUrl: String = "",
    val meetingNote: String = "",
    val address: String = "",
)

data class CircleDraft(
    val requestId: String,
    val title: String,
    val description: String,
    val category: String,
    val venueId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val minimumAge: Int = 18, val maximumAge: Int = 75, val audience: String = "everyone",
)

data class VenueDraft(val requestId: String, val name: String, val address: String,
    val neighborhood: String, val latitude: Double, val longitude: Double, val publicConfirmed: Boolean)
