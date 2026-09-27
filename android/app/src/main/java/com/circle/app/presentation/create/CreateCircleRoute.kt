package com.circle.app.presentation.create

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CreateCircleRoute(
    viewModel: CreateCircleViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }
    androidx.activity.compose.BackHandler(enabled = !state.saving) { viewModel.close(onBack) }
    CreateCircleScreen(
        state,
        viewModel::edit,
        viewModel::submit,
        { viewModel.close(onBack) },
        viewModel::suggest,
        viewModel::discard,
        viewModel::showVenuePicker,
        { viewModel.reload(discardEdits = state.editConflict) },
    )
    if(state.venuePickerOpen) com.circle.app.platform.maps.MapPickerDialog(
        venue=true,initial=state.locationCenter,onDismiss=viewModel::hideVenuePicker,onChoose=viewModel::saveVenue,
        saving=state.savingVenue,serverError=state.venueError)
}
