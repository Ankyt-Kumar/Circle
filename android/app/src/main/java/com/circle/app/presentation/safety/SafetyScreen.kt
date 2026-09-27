package com.circle.app.presentation.safety

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circle.app.presentation.components.ErrorBox

@Composable
fun SafetyRoute(viewModel: SafetyViewModel, onBack: () -> Unit, onBlocked: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.blocked) { if (state.blocked) onBlocked() }
    BackHandler(state.busy) {}
    val name =
        state.circle?.attendees?.firstOrNull { it.id == viewModel.targetUserId }?.name
            ?: "this person"
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onBack, enabled = !state.busy) { Text("‹ Back") }
        Text("Block $name", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Circles hosted by $name, and circles they have joined, will disappear from your Discover feed."
        )
        Text(
            "You’ll leave any circles you share. Hosting passes to the earliest remaining member; a circle is deleted only when its last member leaves."
        )
        Text(
            "You can unblock them from Profile → Blocked people.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorBox(it, enabled = !state.busy) { viewModel.refresh() } }
        Button(
            { confirm = true },
            enabled = !state.busy && !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.busy) "Blocking…" else "Block $name")
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Block $name?") },
            text = {
                Text(
                    "Their circles will be hidden and you’ll leave shared circles. Unblocking won’t automatically rejoin them."
                )
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    viewModel.block()
                }) {
                    Text("Block")
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
}

@Composable
fun BlockedUsersRoute(viewModel: BlockedUsersViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<com.circle.app.domain.model.BlockedUser?>(null) }
    BackHandler(state.busy) {}
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onBack, enabled = !state.busy) { Text("← Back") }
        Text("Blocked people", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Unblocking allows shared circles again, unless this person also blocked you. It does not rejoin circles you left."
        )
        if (state.loading) CircularProgressIndicator()
        else if (state.users.isEmpty() && state.error == null) Text("You haven’t blocked anyone.")
        state.error?.let { ErrorBox(it, enabled = !state.busy) { viewModel.refresh() } }
        state.users.forEach { person ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(person.name, Modifier.weight(1f))
                    TextButton({ selected = person }, enabled = !state.busy && !state.loading) {
                        Text("Unblock")
                    }
                }
            }
        }
    }
    selected?.let { person ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Unblock ${person.name}?") },
            text = { Text("You may see their circles again.") },
            confirmButton = {
                TextButton({
                    selected = null
                    viewModel.unblock(person.id)
                }) {
                    Text("Unblock")
                }
            },
            dismissButton = { TextButton({ selected = null }) { Text("Cancel") } },
        )
    }
}
