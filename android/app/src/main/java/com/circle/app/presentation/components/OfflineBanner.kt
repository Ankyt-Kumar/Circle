package com.circle.app.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun OfflineBanner(savedAt: Instant?, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Text(
                if (savedAt == null) "Connection unavailable" else "You’re viewing saved circles",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Offline · " +
                    (savedAt?.let {
                        "saved " +
                            DateTimeFormatter.ofPattern("d MMM, HH:mm")
                                .withZone(ZoneId.systemDefault())
                                .format(it)
                    } ?: "connection unavailable") +
                    ". Membership and plans may have changed. Connect to join, leave, or create.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRetry) { Text("Try connection") }
        }
    }
}

// Also recovers when the API restarts while Wi-Fi remains connected. Pauses in the background.
@Composable
fun OfflineRecoveryEffect(offline: Boolean, retry: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(retry)
    LaunchedEffect(offline, owner) {
        if (offline)
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(15_000.milliseconds)
                    latest()
                }
            }
    }
}
