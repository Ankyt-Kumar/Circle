package com.circle.app.presentation.detail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CircleDetailRoute(
    viewModel: CircleDetailViewModel,
    onBack: () -> Unit,
    viewerId: String = "",
    onBlockMember: (String, String) -> Unit = { _, _ -> },
    onChat: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    BackHandler(enabled = state.joining) { /* Wait for the membership request. */ }
    CircleDetailScreen(
        state,
        onBack,
        viewModel::refresh,
        viewModel::toggleJoin,
        viewerId,
        onBlockMember,
        onChat,
        onEdit,
        viewModel::cancelEvent,
        viewModel::feedback,
    )
}
