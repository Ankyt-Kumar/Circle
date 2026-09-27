package com.circle.app.presentation.mycircles

import com.circle.app.domain.model.Circle

data class MyCirclesUiState(
    val circles: List<Circle> = emptyList(),
    val loading: Boolean = true,
    val offline: Boolean = false,
    val savedAt: java.time.Instant? = null,
    val error: String? = null,
    val locationName: String = "",
)
