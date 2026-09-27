package com.circle.app.presentation.mycircles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.model.UserPreferences
import com.circle.app.domain.usecase.GetJoinedCircles
import com.circle.app.presentation.common.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MyCirclesViewModel(
    private val getJoinedCircles: GetJoinedCircles,
    initialPreferences: UserPreferences? = null,
    preferences: Flow<UserPreferences?> = flowOf(null),
) : ViewModel() {
    private val mutable =
        MutableStateFlow(MyCirclesUiState(locationName = initialPreferences?.areaName.orEmpty()))
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.collect { userPrefs ->
                if (userPrefs != null && userPrefs.areaName.isNotBlank()) {
                    mutable.update { it.copy(locationName = userPrefs.areaName) }
                }
            }
        }
    }

    fun applyPreferences(userPrefs: UserPreferences?) {
        if (userPrefs != null && userPrefs.areaName.isNotBlank()) {
            mutable.update { it.copy(locationName = userPrefs.areaName) }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                mutable.update { it.copy(loading = true, error = null) }
                try {
                    val page = getJoinedCircles.feed()
                    mutable.update {
                        it.copy(
                            circles = page.circles,
                            loading = false,
                            offline = page.isOffline,
                            savedAt = page.savedAt,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val network =
                        e is com.circle.app.domain.error.CircleException &&
                            e.reason == com.circle.app.domain.error.FailureReason.NETWORK
                    mutable.update {
                        it.copy(
                            circles = if (network) it.circles else emptyList(),
                            loading = false,
                            offline = network,
                            error = e.toUiMessage(),
                        )
                    }
                }
            }
    }
}
