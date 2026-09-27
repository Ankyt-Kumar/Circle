package com.circle.app.presentation.discover

import com.circle.app.domain.model.Area
import com.circle.app.domain.model.Circle

data class DiscoverUiState(
    val forYou: Boolean = false,
    val nextCursor: String = "",
    val loadingMore: Boolean = false,
    val moreError: String? = null,
    val basis: String = "nearby",
    val categories: List<String> = com.circle.app.domain.model.PreferenceOptions.interests,
    val hasLocation: Boolean = true,
    val savingLocation: Boolean = false,
    val updatedAccount: com.circle.app.domain.model.Account? = null,
    val area: Area,
    val radius: Int = 5,
    val category: String = "",
    val circles: List<Circle> = emptyList(),
    val loading: Boolean = true,
    val offline: Boolean = false,
    val savedAt: java.time.Instant? = null,
    val error: String? = null,
)
