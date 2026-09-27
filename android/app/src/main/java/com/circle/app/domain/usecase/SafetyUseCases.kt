package com.circle.app.domain.usecase

import com.circle.app.domain.error.*
import com.circle.app.domain.repository.SafetyRepository

class BlockUser(private val repository: SafetyRepository) {
    suspend operator fun invoke(circleId: String, userId: String) =
        repository.block(circleId, userId)
}

class UnblockUser(private val repository: SafetyRepository) {
    suspend operator fun invoke(userId: String) = repository.unblock(userId)
}

class GetBlockedUsers(private val repository: SafetyRepository) {
    suspend operator fun invoke() = repository.blockedUsers()
}
