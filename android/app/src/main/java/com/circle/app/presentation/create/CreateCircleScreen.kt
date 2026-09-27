package com.circle.app.presentation.create

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.circle.app.domain.model.PreferenceOptions
import com.circle.app.domain.model.audienceLabel
import com.circle.app.presentation.common.categoryLabel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCircleScreen(
    state: CreateCircleUiState,
    onEdit: ((CreateCircleUiState) -> CreateCircleUiState) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onSuggest: (String) -> Unit = {},
    onDiscard: () -> Unit = {},
    onChooseVenue: () -> Unit = {},
    onReload: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var prompt by remember { mutableStateOf("") }
    val busy = state.saving || state.loading || state.suggesting
    val formDisabled = busy || !state.catalogReady
    BackHandler(enabled = state.saving) {}

    Column(
        modifier =
            Modifier.fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.offset(x = (-8).dp),
            ) {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    if (state.editing) "Edit plan" else "Make a plan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Bring a small group together around something you enjoy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (!state.catalogReady && !state.loading) {
            NoticeBanner(text = state.error ?: "Couldn't load this plan.", tone = NoticeTone.ERROR) {
                OutlinedButton(
                    onClick = onReload,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (state.editConflict) "Reload latest plan" else "Retry loading plan",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        if (state.aiAvailable) {
            SectionCard(title = "Suggest with Gemini", icon = Icons.Outlined.AutoAwesome) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Describe a plan in one line") },
                    placeholder = { Text("A relaxed coffee meetup tomorrow evening") },
                    enabled = !formDisabled,
                )
                Button(
                    onClick = { onSuggest(prompt) },
                    enabled = prompt.trim().length >= 3 && !formDisabled,
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    if (state.suggesting) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.suggesting) "Suggesting…" else "Suggest", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "Use public activity details only. Avoid personal information.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!state.editing) {
            NoticeBanner(
                text = state.draftNotice ?: "Your unfinished plan is saved on this device.",
                tone = NoticeTone.NEUTRAL,
            ) {
                TextButton(
                    onClick = onDiscard,
                    enabled = !formDisabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Clear draft", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        state.aiNote?.let { note -> NoticeBanner(text = note, tone = NoticeTone.NEUTRAL) }

        SectionCard(title = "Basics") {
            OutlinedTextField(
                value = state.title,
                onValueChange = { value -> onEdit { it.copy(title = value.take(100)) } },
                label = { Text("Circle title") },
                placeholder = { Text("Coffee and conversation") },
                singleLine = true,
                enabled = !formDisabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PreferenceOptions.interests.forEach { value ->
                    FilterChip(
                        selected = state.category == value,
                        onClick = { onEdit { it.copy(category = value) } },
                        label = { Text(categoryLabel(value), style = MaterialTheme.typography.labelMedium) },
                        enabled = !formDisabled,
                    )
                }
            }
            OutlinedTextField(
                value = state.category,
                onValueChange = { value -> onEdit { it.copy(category = value.take(40)) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Or type your own category") },
                placeholder = { Text("e.g. Photography or Book club") },
                singleLine = true,
                enabled = !formDisabled,
            )
        }

        SectionCard(title = "Who can join") {
            Text(
                "Ages ${state.minimumAge}–${state.maximumAge} on the event date",
                style = MaterialTheme.typography.bodyMedium,
            )
            AgeInputRow(
                minimumAge = state.minimumAge,
                maximumAge = state.maximumAge,
                enabled = !formDisabled,
                onAgeRangeChanged = { minAge, maxAge ->
                    onEdit { it.copy(minimumAge = minAge, maximumAge = maxAge) }
                },
            )
            Choice(
                label = "Audience",
                selected = state.audience,
                options = PreferenceOptions.audiences,
                enabled = !formDisabled,
                text = { value -> audienceLabel(value) },
            ) { value ->
                onEdit { it.copy(audience = value) }
            }
            Text(
                "Existing members must remain eligible when you edit a plan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Description") {
            OutlinedTextField(
                value = state.description,
                onValueChange = { value -> onEdit { it.copy(description = value.take(1000)) } },
                label = { Text("What's the plan?") },
                minLines = 2,
                enabled = !formDisabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = "Venue", icon = Icons.Outlined.LocationOn) {
            val selected = state.venues.firstOrNull { it.id == state.venueId }
            OutlinedButton(
                onClick = onChooseVenue,
                modifier = Modifier.fillMaxWidth(),
                enabled = !formDisabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    if (selected == null) "Choose venue in Google Maps" else "Change venue on map",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            selected?.let { venue ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(venue.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            venue.address.ifBlank { venue.neighborhood },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (venue.fictional) {
                            Text(
                                "Fictional demo venue. Do not travel here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        venue.meetingNote.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        venue.sourceUrl.takeIf { it.startsWith("https://") }?.let { url ->
                            TextButton(
                                onClick = {
                                    try {
                                        uriHandler.openUri(url)
                                    } catch (_: IllegalArgumentException) {
                                        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text("Check venue details & hours", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Text(
                "Meet in public. Never add a home address or personal contact details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Date & time · ${state.timeZone}", icon = Icons.Outlined.Event) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val date = runCatching { LocalDate.parse(state.date) }.getOrDefault(LocalDate.now())
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> onEdit { it.copy(date = LocalDate.of(y, m + 1, d).toString()) } },
                            date.year,
                            date.monthValue - 1,
                            date.dayOfMonth,
                        ).show()
                    },
                    enabled = !formDisabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(state.date, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        val time = runCatching { LocalTime.parse(state.time) }.getOrDefault(LocalTime.of(18, 0))
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                onEdit {
                                    it.copy(time = LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("HH:mm")))
                                }
                            },
                            time.hour,
                            time.minute,
                            true,
                        ).show()
                    },
                    enabled = !formDisabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(state.time, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    Choice(
                        label = "Duration",
                        selected = state.durationMinutes,
                        options = listOf(30, 60, 90, 120, 180),
                        enabled = !formDisabled,
                        text = { "$it mins" },
                    ) { value -> onEdit { it.copy(durationMinutes = value) } }
                }
                Box(Modifier.weight(1f)) {
                    Choice(
                        label = "Group size",
                        selected = state.capacity,
                        options = (4..8).toList(),
                        enabled = !formDisabled,
                        text = { "$it people" },
                    ) { value -> onEdit { it.copy(capacity = value) } }
                }
            }
        }

        if (state.catalogReady) {
            state.error?.let { message -> NoticeBanner(text = message, tone = NoticeTone.ERROR) }
        }

        Button(
            onClick = onSubmit,
            enabled = !busy && state.catalogReady && state.venueId.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (state.saving) "Submitting…" else if (state.editing) "Save changes" else "Create circle",
                style = MaterialTheme.typography.titleSmall,
            )
        }

        Text(
            "Your circle appears as soon as it is created. You'll automatically join as host.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

private enum class NoticeTone { NEUTRAL, ERROR }

@Composable
private fun NoticeBanner(
    text: String,
    tone: NoticeTone,
    action: (@Composable () -> Unit)? = null,
) {
    val (container, content) =
        when (tone) {
            NoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tone == NoticeTone.ERROR) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            action?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    label: String,
    selected: T,
    options: List<T>,
    enabled: Boolean,
    text: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(text(selected), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.rotate(rotation))
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        leadingIcon =
                            if (option == selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgeInputRow(
    minimumAge: Int,
    maximumAge: Int,
    enabled: Boolean,
    onAgeRangeChanged: (minAge: Int, maxAge: Int) -> Unit,
) {
    var minAgeText by remember(minimumAge) { mutableStateOf(minimumAge.toString()) }
    var maxAgeText by remember(maximumAge) { mutableStateOf(maximumAge.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = minAgeText,
            onValueChange = { input ->
                val digitsOnly = input.filter { it.isDigit() }.take(2)
                val parsed = digitsOnly.toIntOrNull()
                if (parsed != null) {
                    if (parsed > 75) {
                        minAgeText = "75"
                        val validMin = 75
                        val validMax = maximumAge.coerceIn(validMin, 75)
                        onAgeRangeChanged(validMin, validMax)
                    } else {
                        minAgeText = digitsOnly
                        val validMin = parsed.coerceIn(18, 75)
                        val validMax = maximumAge.coerceIn(validMin, 75)
                        onAgeRangeChanged(validMin, validMax)
                    }
                } else {
                    minAgeText = digitsOnly
                }
            },
            modifier = Modifier.weight(1f).onFocusChanged { focusState ->
                if (!focusState.isFocused) {
                    minAgeText = minimumAge.toString()
                }
            },
            label = { Text("Min age (18+)") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        OutlinedTextField(
            value = maxAgeText,
            onValueChange = { input ->
                val digitsOnly = input.filter { it.isDigit() }.take(2)
                val parsed = digitsOnly.toIntOrNull()
                if (parsed != null) {
                    if (parsed > 75) {
                        maxAgeText = "75"
                        val validMax = 75
                        val validMin = minimumAge.coerceIn(18, validMax)
                        onAgeRangeChanged(validMin, validMax)
                    } else {
                        maxAgeText = digitsOnly
                        val validMax = parsed.coerceIn(minimumAge, 75)
                        val validMin = minimumAge.coerceIn(18, validMax)
                        onAgeRangeChanged(validMin, validMax)
                    }
                } else {
                    maxAgeText = digitsOnly
                }
            },
            modifier = Modifier.weight(1f).onFocusChanged { focusState ->
                if (!focusState.isFocused) {
                    maxAgeText = maximumAge.toString()
                }
            },
            label = { Text("Max age (≤75)") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
