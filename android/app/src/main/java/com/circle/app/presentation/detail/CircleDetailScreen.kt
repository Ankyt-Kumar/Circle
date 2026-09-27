package com.circle.app.presentation.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circle.app.domain.model.audienceLabel
import com.circle.app.platform.maps.PublicVenueMap
import com.circle.app.presentation.common.*
import com.circle.app.presentation.components.*
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleDetailScreen(
    state: CircleDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleJoin: () -> Unit,
    viewerId: String = "",
    onBlockMember: (String, String) -> Unit = { _, _ -> },
    onChat: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onCancel: () -> Unit = {},
    onFeedback: (Boolean, String) -> Unit = { _, _ -> },
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val circle = state.circle
    val started = circle?.startsAt?.isAfter(Instant.now()) == false

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Circle Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.joining) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to circles",
                        )
                    }
                },
                actions = {
                    if (circle != null && circle.isHost && circle.startsAt.isAfter(Instant.now()) && circle.status !in listOf("cancelled", "rejected", "archived")) {
                        IconButton(onClick = { onEdit(circle.id) }, enabled = !state.joining) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit plan")
                        }
                        IconButton(
                            onClick = { confirmCancel = true },
                            enabled = !state.joining,
                        ) {
                            Icon(
                                Icons.Outlined.Cancel,
                                contentDescription = "Cancel event",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (circle != null && !state.deleted && !state.loading) {
                Surface(
                    shadowElevation = 8.dp,
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = {
                                if (circle.joined && (circle.isHost || circle.attendees.size == 1))
                                    confirmDelete = true
                                else onToggleJoin()
                            },
                            enabled =
                                !state.joining &&
                                        !state.refreshRequired &&
                                        (circle.joined ||
                                                (circle.status == "published" && circle.eligible && !started && circle.spots > 0)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = if (circle.joined) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) {
                            Text(
                                when {
                                    state.joining -> "Updating…"
                                    circle.joined && circle.attendees.size == 1 -> "Leave & delete circle"
                                    circle.joined && circle.isHost -> "Leave & transfer hosting"
                                    circle.joined -> "Leave circle"
                                    circle.status != "published" -> "Not open for joining"
                                    !circle.eligible -> "Not eligible to join"
                                    started -> "Event has started"
                                    circle.spots == 0 -> "Circle is full"
                                    else -> "Join circle"
                                },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            when {
                                circle.joined && circle.isHost ->
                                    "When host leaves, earliest member becomes host. Empty circle is deleted."
                                !circle.joined && !circle.eligible ->
                                    "Your profile does not meet this circle’s requirements."
                                !circle.joined && circle.spots == 0 ->
                                    "This circle has reached max capacity."
                                else ->
                                    "Safe and respectful community · Verified members"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.deleted -> item {
                    Text(
                        "This circle has been deleted.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                circle == null -> item {
                    ErrorBox(state.error ?: "Circle unavailable") { onRetry() }
                }
                else -> {
                    // Activity Banner
                    item {
                        ActivityBanner(category = circle.category, compact = true)
                    }

                    // Title & Date Header
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.CalendarToday,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    formatTime(circle.startsAt),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            Text(
                                circle.title,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 24.sp,
                                    lineHeight = 30.sp,
                                ),
                                fontWeight = FontWeight.Bold,
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (circle.isHost) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            "You’re hosting",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        "${circle.attendees.size}/${circle.capacity} going",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Status warning banner if not published
                    if (circle.status != "published") {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        when (circle.status) {
                                            "pending_review" ->
                                                "This circle is pending review. Refresh to check for updates."
                                            "rejected" ->
                                                "This circle was rejected. You can leave it and make a new plan."
                                            else ->
                                                "This circle is no longer published."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                    // The Plan (Description)
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "The plan",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    circle.description,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    // Summary Card (Meeting spot + Audience & Age)
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Venue Row
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        Icons.Outlined.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp).size(20.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            circle.venue,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            "${circle.neighborhood} · Public place",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (circle.venueFictional) {
                                            Text(
                                                "Fictional demo venue. Do not travel here.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Audience & Age Row
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        Icons.Outlined.People,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp).size(20.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "Ages ${circle.minimumAge}–${circle.maximumAge} · ${audienceLabel(circle.audience)}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (!circle.joined && !circle.eligible) {
                                            Text(
                                                "Profile requirements not met",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Interactive Chat / Post-Event Action Buttons
                    if (circle.joined && circle.status !in listOf("archived", "rejected")) {
                        item {
                            Button(
                                onClick = { onChat(circle.id) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Chat,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                )
                                Text(
                                    if (circle.endsAt.isAfter(Instant.now()) && circle.status == "published")
                                        "Open group chat"
                                    else "Open archived chat",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    if (
                        circle.joined &&
                        !circle.endsAt.isAfter(Instant.now()) &&
                        circle.status in listOf("published", "archived")
                    ) {
                        item {
                            OutlinedButton(
                                onClick = { feedback = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Star,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                )
                                Text("Share attendance & feedback")
                            }
                        }
                    }

                    // Map surface
                    item {
                        PublicVenueMap(circle)
                    }

                    // Notice if present
                    state.notice?.let { notice ->
                        item {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    notice,
                                    modifier = Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }



                    // Attendees List ("Who's going")
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Who’s going",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "${circle.attendees.size} / ${circle.capacity} members",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column {
                                    circle.attendees.forEachIndexed { index, attendee ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                            ) {
                                                Box(
                                                    Modifier.size(36.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        attendee.name.take(1),
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                }
                                            }
                                            Text(
                                                attendee.name,
                                                Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            if (attendee.id != viewerId) {
                                                TextButton(
                                                    onClick = { onBlockMember(circle.id, attendee.id) },
                                                    enabled = !state.joining,
                                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                                ) {
                                                    Text(
                                                        "Block",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.outline,
                                                    )
                                                }
                                            }
                                        }
                                        if (index < circle.attendees.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 64.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Error Box if any error
                    if (state.error != null) {
                        item { ErrorBox(state.error, enabled = !state.joining) { onRetry() } }
                    }
                }
            }
        }
    }

    if (confirmCancel)
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this event?") },
            text = { Text("Members will see a cancellation in their inbox. Chat becomes read-only.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        onCancel()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Cancel event") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep event") } },
        )

    if (feedback)
        AlertDialog(
            onDismissRequest = { feedback = false },
            title = { Text("How did it go?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("good", "okay", "poor").forEach { rating ->
                            OutlinedButton(
                                onClick = {
                                    feedback = false
                                    onFeedback(true, rating)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(rating.replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            feedback = false
                            onFeedback(false, "okay")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("I didn’t attend") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { feedback = false }) { Text("Later") } },
        )

    if (confirmDelete) {
        val last = state.circle?.attendees?.size == 1
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(if (last) "Leave and delete this circle?" else "Leave and transfer hosting?")
            },
            text = {
                Text(
                    if (last) "You’re the last member. Leaving permanently removes this circle."
                    else
                        "Hosting passes to whoever joined earliest and is still a member when you leave. The circle stays open for the remaining members."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onToggleJoin()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (last) "Leave and delete" else "Leave and transfer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Stay in circle") } },
        )
    }
}