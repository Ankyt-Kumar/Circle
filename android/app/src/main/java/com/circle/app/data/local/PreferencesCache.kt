package com.circle.app.data.local

import com.circle.app.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface PreferencesCache {
    fun observe(accountId: String): Flow<UserPreferences?>
    suspend fun store(accountId: String, preferences: UserPreferences?)
}

object NoPreferencesCache : PreferencesCache {
    override fun observe(accountId: String) = flowOf<UserPreferences?>(null)
    override suspend fun store(accountId: String, preferences: UserPreferences?) = Unit
}
