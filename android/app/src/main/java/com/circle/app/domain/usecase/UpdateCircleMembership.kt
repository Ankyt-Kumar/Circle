package com.circle.app.domain.usecase

import com.circle.app.domain.repository.CircleRepository

class UpdateCircleMembership(private val repository: CircleRepository) {
    suspend operator fun invoke(id: String, joined: Boolean) {
        require(id.isNotBlank())
        // The server owns capacity and eligibility. Never infer success from a local count.
        repository.setJoined(id, joined)
    }
}
