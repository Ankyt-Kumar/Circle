package com.circle.app.presentation.member

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circle.app.domain.model.CircleNotice
import com.circle.app.domain.model.MemberStats
import com.circle.app.domain.model.NoticePreferences
import com.circle.app.presentation.theme.CircleTheme

@Composable
fun MemberRoute(
    vm: MemberViewModel,
    onBack: () -> Unit,
    onCircle: (String) -> Unit,
    onDeleted: () -> Unit,
    onEnablePush: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }

    MemberScreen(
        state = state,
        onBack = onBack,
        onSavePreferences = { vm.save(it) },
        onEnablePush = onEnablePush,
        onReadNotice = { vm.read(it) },
        onSelectCircle = onCircle,
        onDeleteAccount = { vm.delete() },
        onRetry = vm::load,
    )
}

@Composable
fun MemberScreen(
    state: MemberUiState,
    onBack: () -> Unit,
    onSavePreferences: (NoticePreferences) -> Unit,
    onEnablePush: () -> Unit,
    onReadNotice: (Long) -> Unit,
    onSelectCircle: (String) -> Unit,
    onDeleteAccount: () -> Unit,
    onRetry: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmationInput by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // Compact Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                text = "Activity & Notifications",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Stats Section
            state.stats?.let { s ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeader(icon = Icons.AutoMirrored.Outlined.ShowChart, title = "Your Activity")
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StatItem(
                                        value = s.joined.toString(),
                                        label = "Joined",
                                        modifier = Modifier.weight(1f),
                                    )
                                    VerticalDivider(
                                        modifier = Modifier.height(32.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                    StatItem(
                                        value = s.hosted.toString(),
                                        label = "Hosted",
                                        modifier = Modifier.weight(1f),
                                    )
                                    VerticalDivider(
                                        modifier = Modifier.height(32.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                    StatItem(
                                        value = s.attended.toString(),
                                        label = "Attended",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(
                                    text = "Attendance is self-reported.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                )
                            }
                        }
                    }
                }
            }

            // Notification Preferences
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(icon = Icons.Outlined.Notifications, title = "Notifications")
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            NotificationRow(
                                label = "Event reminders",
                                checked = state.preferences.reminders,
                                enabled = !state.busy,
                            ) {
                                onSavePreferences(state.preferences.copy(reminders = it))
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                            NotificationRow(
                                label = "Changes and cancellations",
                                checked = state.preferences.changes,
                                enabled = !state.busy,
                            ) {
                                onSavePreferences(state.preferences.copy(changes = it))
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                            NotificationRow(
                                label = "Phone notifications",
                                checked = state.preferences.pushEnabled,
                                enabled = !state.busy,
                            ) { enabled ->
                                if (enabled) onEnablePush() else onSavePreferences(state.preferences.copy(pushEnabled = false))
                            }
                        }
                    }
                }
            }

            // Error banner
            state.error?.let { message ->
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            // Loading indicator
            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            }

            // Notices Header & List
            item {
                SectionHeader(icon = Icons.Outlined.MarkEmailUnread, title = "Activity Updates")
            }

            if (!state.loading && state.notices.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "You’re all caught up. Event updates will appear here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(state.notices, key = { it.id }) { n ->
                NoticeCard(
                    notice = n,
                    onClick = {
                        onReadNotice(n.id)
                        if (n.circleId.isNotBlank()) onSelectCircle(n.circleId)
                    },
                )
            }

            // Account Controls Section
            item {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionHeader(icon = Icons.Outlined.PersonOff, title = "Account Controls")
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Deleting your account removes your profile, memberships, messages and preferences. Remaining members keep their circles with hosting transferred.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Delete my account", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirmDelete = false },
            title = { Text("Delete your account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This action is permanent. Sign in again first if your sign-in is older than five minutes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = confirmationInput,
                        onValueChange = { confirmationInput = it },
                        singleLine = true,
                        label = { Text("Type DELETE to confirm") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAccount()
                        confirmDelete = false
                    },
                    enabled = confirmationInput == "DELETE" && !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete account") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep account") }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotificationRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun NoticeCard(
    notice: CircleNotice,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (!notice.read) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = notice.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (!notice.read) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!notice.read) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = "NEW",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Text(
                text = notice.body,
                style = MaterialTheme.typography.bodySmall,
                color = if (!notice.read) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MemberScreenPreview() {
    CircleTheme {
        MemberScreen(
            state = MemberUiState(
                stats = MemberStats(joined = 5, hosted = 2, attended = 4),
                notices = listOf(
                    CircleNotice(
                        id = 1,
                        title = "Coffee & Code meetup updated",
                        body = "The venue has been changed to Third Wave Coffee, Koramangala.",
                        read = false,
                        circleId = "circle-1",
                    ),
                    CircleNotice(
                        id = 2,
                        title = "Weekend Board Games",
                        body = "Arjun joined your circle.",
                        read = true,
                        circleId = "circle-2",
                    ),
                ),
                loading = false,
            ),
            onBack = {},
            onSavePreferences = {},
            onEnablePush = {},
            onReadNotice = {},
            onSelectCircle = {},
            onDeleteAccount = {},
            onRetry = {},
        )
    }
}
