package com.circle.app.domain.repository

import com.circle.app.domain.model.Circle
import com.circle.app.domain.model.CircleQuery

interface CircleRepository {
    suspend fun getNearbyPage(
        query: CircleQuery,
        cursor: String = "",
    ): com.circle.app.domain.model.CircleFeed =
        com.circle.app.domain.model.CircleFeed(getNearby(query))

    suspend fun getJoinedFeed(): com.circle.app.domain.model.CircleFeed =
        com.circle.app.domain.model.CircleFeed(getJoined())

    suspend fun create(draft: com.circle.app.domain.model.CircleDraft): Circle

    suspend fun getNearby(query: CircleQuery): List<Circle>

    suspend fun getCircle(id: String): Circle

    suspend fun getJoined(): List<Circle>

    suspend fun setJoined(id: String, joined: Boolean)
}
