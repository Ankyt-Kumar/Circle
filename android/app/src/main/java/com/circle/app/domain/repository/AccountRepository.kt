package com.circle.app.domain.repository

import com.circle.app.domain.model.Account
import com.circle.app.domain.model.OnboardingDraft
import com.circle.app.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    suspend fun getAccount(): Account
    suspend fun saveFirstName(name: String): Account
    suspend fun savePreferences(draft: OnboardingDraft): Account
    suspend fun saveLocation(area: com.circle.app.domain.model.Area, radiusKm: Int): Account
    fun observePreferences(accountId: String): Flow<UserPreferences?>
}
