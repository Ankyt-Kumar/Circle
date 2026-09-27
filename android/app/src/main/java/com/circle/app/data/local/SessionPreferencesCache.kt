package com.circle.app.data.local

import com.circle.app.domain.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Account JSON is already persisted in the UID-fenced disk cache. This observable copy is memory
// only.
class SessionPreferencesCache : PreferencesCache {
    private val values = MutableStateFlow<Map<String, UserPreferences>>(emptyMap())

    override fun observe(accountId: String) = values.map { it[accountId] }.distinctUntilChanged()

    override suspend fun store(accountId: String, preferences: UserPreferences?) {
        values.value =
            if (preferences == null) values.value - accountId
            else values.value + (accountId to preferences)
    }

    fun clear() {
        values.value = emptyMap()
    }
}
