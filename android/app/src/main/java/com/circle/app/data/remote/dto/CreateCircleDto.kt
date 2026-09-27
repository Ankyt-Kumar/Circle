package com.circle.app.data.remote.dto

data class CreateCircleDto(
    val requestId: String, val title: String, val description: String,
    val category: String, val venueId: String, val startsAt: String,
    val endsAt: String, val capacity: Int,
    val minimumAge: Int = 18, val maximumAge: Int = 75, val audience: String = "everyone"
)
