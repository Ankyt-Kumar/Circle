package com.circle.app.domain.repository

import com.circle.app.domain.model.*

interface SafetyRepository {
    suspend fun block(circleId: String, userId: String)

    suspend fun unblock(userId: String)

    suspend fun blockedUsers(): List<BlockedUser>
}
