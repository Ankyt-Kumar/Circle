package com.circle.app.domain.model

import java.time.Instant

data class CircleFeed(
    val circles: List<Circle>,
    val nextCursor: String = "",
    val isOffline: Boolean = false,
    val savedAt: Instant? = null,
)

data class SavedCircleForm(
    val title: String,
    val description: String,
    val category: String,
    val venueId: String,
    val date: String,
    val time: String,
    val capacity: Int,
    val durationMinutes: Int,
    val requestId: String,
    val minimumAge: Int = 18, val maximumAge: Int = 100, val audience: String = "everyone",
    val selectedVenue: PublicVenue? = null, val timeZone: String = java.time.ZoneId.systemDefault().id,
)
