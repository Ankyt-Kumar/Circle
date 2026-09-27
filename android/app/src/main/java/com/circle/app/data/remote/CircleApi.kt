package com.circle.app.data.remote

import com.circle.app.data.remote.dto.CircleDto

data class CirclePageDto(val circles: List<CircleDto>, val nextCursor: String = "")

interface CircleApi {
    suspend fun searchPage(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        category: String,
        cursor: String,
    ): CirclePageDto = CirclePageDto(search(latitude, longitude, radiusKm, category))

    suspend fun create(draft: com.circle.app.data.remote.dto.CreateCircleDto): CircleDto

    suspend fun search(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        category: String,
    ): List<CircleDto>

    suspend fun detail(id: String): CircleDto

    suspend fun mine(): List<CircleDto>

    suspend fun setJoined(id: String, joined: Boolean)
}
