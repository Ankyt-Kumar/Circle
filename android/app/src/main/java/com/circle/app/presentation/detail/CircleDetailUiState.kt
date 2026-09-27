package com.circle.app.presentation.detail

import com.circle.app.domain.model.Circle

data class CircleDetailUiState(
    val circle: Circle? = null,
    val loading: Boolean = true,
    val joining: Boolean = false,
    val error: String? = null,
    val refreshRequired: Boolean = false,
    val notice: String? = null,
    val deleted: Boolean = false,
)
