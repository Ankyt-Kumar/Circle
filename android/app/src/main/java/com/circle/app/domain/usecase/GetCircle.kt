package com.circle.app.domain.usecase

import com.circle.app.domain.model.Circle
import com.circle.app.domain.repository.CircleRepository

class GetCircle(private val repository: CircleRepository) {
    suspend operator fun invoke(id: String): Circle {
        require(id.isNotBlank())
        return repository.getCircle(id)
    }
}
