package com.circle.app.presentation.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.circle.app.domain.model.Area
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DiscoverRoute(
    viewModel: DiscoverViewModel,
    areas: List<Area>,
    onCircleSelected: (String) -> Unit,
    onCreate: () -> Unit,
    preferences: com.circle.app.domain.model.UserPreferences? = null,
    onAccountUpdated: (com.circle.app.domain.model.Account) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    // A loaded/offline card must disappear when its time arrives, even if the
    // member leaves Discover open. This performs no network polling.
    LaunchedEffect(viewModel, lifecycleOwner, state.circles) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive && viewModel.state.value.circles.isNotEmpty()) {
                viewModel.removeInactiveCircles()
                if (viewModel.state.value.circles.isEmpty()) break
                delay(viewModel.nextExpiryDelayMillis())
            }
        }
    }
    com.circle.app.presentation.components.OfflineRecoveryEffect(state.offline, viewModel::refresh)
    LaunchedEffect(preferences) { viewModel.applyPreferences(preferences) }
    LaunchedEffect(state.updatedAccount) {
        state.updatedAccount?.let { onAccountUpdated(it); viewModel.accountUpdateHandled() }
    }
    // Returning from details reloads attendee counts after join/leave.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    DiscoverScreen(
        state,
        areas,
        viewModel::chooseArea,
        viewModel::chooseRadius,
        viewModel::chooseCategory,
        viewModel::refresh,
        onCircleSelected,
        onCreate,
        viewModel::setForYou,
        viewModel::loadMore,
    )
}
