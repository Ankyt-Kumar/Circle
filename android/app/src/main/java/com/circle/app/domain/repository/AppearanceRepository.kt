package com.circle.app.domain.repository

import com.circle.app.domain.model.Appearance
import kotlinx.coroutines.flow.Flow

interface AppearanceRepository {
    val appearance: Flow<Appearance>
    suspend fun save(value: Appearance)
}
