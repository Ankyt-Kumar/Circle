package com.circle.app.presentation.mycircles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circle.app.domain.model.UserPreferences
import com.circle.app.presentation.components.OfflineRecoveryEffect

@Composable
fun MyCirclesRoute(
    viewModel: MyCirclesViewModel,
    onExplore: () -> Unit,
    onCircleSelected: (String) -> Unit,
    preferences: UserPreferences? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OfflineRecoveryEffect(state.offline, viewModel::refresh)
    LaunchedEffect(preferences) { viewModel.applyPreferences(preferences) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    MyCirclesScreen(state, viewModel::refresh, onExplore, onCircleSelected)
}
