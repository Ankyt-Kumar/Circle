package com.circle.app.presentation.create

import com.circle.app.domain.model.PublicVenue

data class CreateCircleUiState(
    val editing: Boolean = false,
    val loading: Boolean = false,
    val editConflict: Boolean = false,
    val minimumAge: Int = 18, val maximumAge: Int = 75, val audience: String = "everyone",
    val locationCenter: com.circle.app.domain.model.Area? = null,
    val venuePickerOpen: Boolean = false, val savingVenue: Boolean = false, val venueError: String? = null,
    val timeZone: String = java.time.ZoneId.systemDefault().id,
    val draftNotice: String? = null,
    val aiAvailable: Boolean = false,
    val suggesting: Boolean = false,
    val aiNote: String? = null,
    val catalogReady: Boolean = true,
    val title: String = "",
    val description: String = "",
    val category: String = "coffee",
    val venueId: String = "",
    val date: String = "",
    val time: String = "18:00",
    val capacity: Int = 6,
    val durationMinutes: Int = 90,
    val venues: List<PublicVenue> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val createdId: String? = null,
)
