package com.circle.app.presentation.onboarding

import android.app.DatePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circle.app.domain.model.*
import com.circle.app.presentation.common.categoryLabel
import com.circle.app.platform.location.CurrentAreaButton
import com.circle.app.platform.maps.MapPickerDialog
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun OnboardingRoute(vm: OnboardingViewModel, editing: Boolean, onSaved: (Account) -> Unit, onCancel: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedAccount) { state.savedAccount?.let(onSaved) }
    BackHandler(enabled = state.saving || state.step > 0) { if (!state.saving) vm.back() }
    OnboardingScreen(
        state, editing, vm::name, vm::adult, vm::terms, vm::interest, vm::area, vm::radius,
        vm::next, vm::back, onCancel, vm::birthDate, vm::gender, vm::location
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState, editing: Boolean,
    onName: (String) -> Unit, onAdult: (Boolean) -> Unit, onTerms: (Boolean) -> Unit, onInterest: (String) -> Unit,
    onArea: (String) -> Unit, onRadius: (Int) -> Unit, onNext: () -> Unit, onBack: () -> Unit, onCancel: () -> Unit,
    onBirthDate: (String) -> Unit = {}, onGender: (String) -> Unit = {}, onLocation: (Area) -> Unit = {}
) {
    val context = LocalContext.current
    var guidelines by rememberSaveable { mutableStateOf(false) }
    var mapPicker by rememberSaveable { mutableStateOf(false) }
    var genders by remember { mutableStateOf(false) }
    val stepTitles = listOf("Basics", "Interests", "Nearby")

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {

        // Fixed header — stays visible while the form scrolls beneath it.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("circle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onCancel, enabled = !state.saving) { Text(if (editing) "Cancel" else "Sign out") }
        }
        HorizontalDivider()

        // Scrollable step content.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                if (editing) "Your preferences" else "Make room for good company",
                style = MaterialTheme.typography.headlineLarge
            )
            StepIndicator(state.step, stepTitles)

            when (state.step) {
                0 -> BasicsStep(
                    state = state, context = context,
                    onName = onName, onBirthDate = onBirthDate,
                    gendersOpen = genders, onGendersOpenChange = { genders = it }, onGender = onGender,
                    onAdult = onAdult, onTerms = onTerms,
                    onShowGuidelines = { guidelines = true }
                )
                1 -> InterestsStep(state = state, onInterest = onInterest)
                else -> NearbyStep(
                    state = state, onLocation = onLocation,
                    onOpenMapPicker = { mapPicker = true }, onRadius = onRadius
                )
            }
        }

        // Fixed footer — actions and errors stay reachable without scrolling.
        Column(Modifier.fillMaxWidth().imePadding()) {
            HorizontalDivider()
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.error?.let { ErrorBanner(it) }
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !state.saving) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (state.saving) "Saving…" else if (state.step < 2) "Continue" else if (editing) "Save preferences" else "Explore nearby")
                }
                if (state.step > 0) {
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !state.saving) { Text("Back") }
                }
            }
        }
    }

    if (mapPicker) {
        val d = state.draft
        MapPickerDialog(
            venue = false,
            initial = if (validCoordinates(d.latitude, d.longitude)) Area(d.areaName, d.latitude!!, d.longitude!!) else null,
            onDismiss = { mapPicker = false },
            onChoose = { choice -> onLocation(Area(choice.neighborhood, choice.latitude, choice.longitude)); mapPicker = false }
        )
    }
    if (guidelines) GuidelinesDialog(onDismiss = { guidelines = false })
}

@Composable
private fun StepIndicator(step: Int, titles: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            titles.indices.forEach { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
        Text(
            "Step ${step + 1} of ${titles.size} · ${titles[step]}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun BasicsStep(
    state: OnboardingUiState, context: Context,
    onName: (String) -> Unit, onBirthDate: (String) -> Unit,
    gendersOpen: Boolean, onGendersOpenChange: (Boolean) -> Unit, onGender: (String) -> Unit,
    onAdult: (Boolean) -> Unit, onTerms: (Boolean) -> Unit,
    onShowGuidelines: () -> Unit
) {
    val d = state.draft
    val saving = state.saving
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("About you", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = d.firstName, onValueChange = onName, modifier = Modifier.fillMaxWidth(),
            label = { Text("First name") }, singleLine = true, enabled = !saving
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    val date = runCatching { LocalDate.parse(d.birthDate) }.getOrDefault(LocalDate.now().minusYears(25))
                    DatePickerDialog(
                        context,
                        { _, year, month, day -> onBirthDate(LocalDate.of(year, month + 1, day).toString()) },
                        date.year, date.monthValue - 1, date.dayOfMonth
                    ).apply {
                        datePicker.maxDate = LocalDate.now().minusYears(18).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        datePicker.minDate = LocalDate.now().minusYears(101).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.show()
                },
                modifier = Modifier.weight(1f), enabled = !saving
            ) { Text(d.birthDate.ifBlank { "Date of birth" }, maxLines = 1) }

            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { onGendersOpenChange(true) }, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
                    Text(genderLabel(d.gender), maxLines = 1)
                }
                DropdownMenu(expanded = gendersOpen, onDismissRequest = { onGendersOpenChange(false) }) {
                    PreferenceOptions.genders.forEach { value ->
                        DropdownMenuItem(text = { Text(genderLabel(value)) }, onClick = { onGender(value); onGendersOpenChange(false) })
                    }
                }
            }
        }
        if (d.birthDate.isNotBlank()) {
            Text(
                "Age ${ageOn(d.birthDate) ?: "—"} today",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            "Your birthday stays private and is only used to check a circle’s age range on the event date. Everyone circles welcome all genders; male-only and female-only circles use the gender you provide.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
                PreferenceCheck("I confirm I am 18 or older", d.adultConfirmed, !saving, onAdult)
                PreferenceCheck("I agree to the community guidelines", d.termsAccepted, !saving, onTerms)
                TextButton(onClick = onShowGuidelines, modifier = Modifier.padding(start = 36.dp)) { Text("Read the guidelines") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterestsStep(state: OnboardingUiState, onInterest: (String) -> Unit) {
    val d = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What brings you outside?", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick as many as you like — you can also create circles with your own activity category.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreferenceOptions.interests.forEach { value ->
                FilterChip(
                    selected = value in d.interests,
                    onClick = { onInterest(value) },
                    label = { Text(categoryLabel(value)) },
                    enabled = !state.saving
                )
            }
        }
        if (d.interests.isEmpty()) {
            Text(
                "Choose at least one to see circles that match.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NearbyStep(
    state: OnboardingUiState, onLocation: (Area) -> Unit,
    onOpenMapPicker: () -> Unit, onRadius: (Int) -> Unit
) {
    val d = state.draft
    val saving = state.saving
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Find your nearby circles", style = MaterialTheme.typography.titleLarge)
        Text(
            "Allow location while using the app, or choose your area on the map. We save this location privately to find nearby events.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (validCoordinates(d.latitude, d.longitude)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(d.areaName.ifBlank { "Selected location" }, style = MaterialTheme.typography.titleMedium)
                        Text("Private to your account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    TextButton(onClick = onOpenMapPicker, enabled = !saving) { Text("Change") }
                }
            }
        }
        CurrentAreaButton(onLocation, enabled = !saving)
        OutlinedButton(onClick = onOpenMapPicker, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
            Text(if (validCoordinates(d.latitude, d.longitude)) "Choose a different spot on the map" else "Choose location on map")
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Travel distance · up to ${d.radiusKm} km", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = d.radiusKm.toFloat(), onValueChange = { onRadius(it.roundToInt()) },
                valueRange = 1f..10f, steps = 8, enabled = !saving
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("10 km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "Distances are measured in a straight line from your saved location. Approximate permission can reduce accuracy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GuidelinesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Community guidelines") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Circle is for adults aged 18 and above. Age and gender are self-declared.")
                Text("Meet in public places. Check the venue is open and can welcome your group. Keep personal addresses and contact details out of plans and chat.")
                Text("Be respectful. You can block another member from their circle or message.")
                Text("When a host leaves, hosting transfers to the earliest remaining member. Only the last departure deletes the circle.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun PreferenceCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}