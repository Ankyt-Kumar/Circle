package com.circle.app.domain.usecase

import com.circle.app.domain.model.Circle
import com.circle.app.domain.repository.CircleRepository

class GetJoinedCircles(private val repository: CircleRepository) {
    suspend fun feed() = repository.getJoinedFeed()

    suspend operator fun invoke(): List<Circle> = repository.getJoined()
}
