package com.circle.app.domain.usecase

import com.circle.app.domain.model.Circle
import com.circle.app.domain.model.CircleQuery
import com.circle.app.domain.repository.CircleRepository

class GetNearbyCircles(private val repository: CircleRepository) {
    suspend fun page(
        query: CircleQuery,
        cursor: String = "",
    ): com.circle.app.domain.model.CircleFeed {
        validate(query)
        return repository.getNearbyPage(query, cursor)
    }

    suspend operator fun invoke(query: CircleQuery): List<Circle> {
        validate(query)
        return repository.getNearby(query)
    }

    private fun validate(query: CircleQuery) {
        require(query.radiusKm in 1..10) { "Radius must be between 1 and 10 km" }
        require(query.area.latitude.isFinite() && query.area.latitude in -90.0..90.0)
        require(query.area.longitude.isFinite() && query.area.longitude in -180.0..180.0)
        require(query.category.isEmpty() || com.circle.app.domain.model.validCategory(query.category))
    }
}
