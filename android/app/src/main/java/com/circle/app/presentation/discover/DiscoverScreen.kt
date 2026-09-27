package com.circle.app.presentation.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circle.app.domain.model.*
import com.circle.app.platform.location.CurrentAreaButton
import com.circle.app.platform.maps.MapPickerDialog
import com.circle.app.presentation.common.*
import com.circle.app.presentation.components.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    areas: List<Area>,
    onAreaSelected: (Area) -> Unit,
    onRadiusSelected: (Int) -> Unit,
    onCategorySelected: (String) -> Unit,
    onRetry: () -> Unit,
    onCircleSelected: (String) -> Unit,
    onCreate: () -> Unit = {},
    onForYou: (Boolean) -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    var locationPicker by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var showRadiusSlider by remember { mutableStateOf(false) }
    var showSearchField by remember { mutableStateOf(false) }
    var customFilter by remember(state.category) { mutableStateOf(state.category) }

    if (locationPicker) MapPickerDialog(
        initial = state.area.takeIf { state.hasLocation }, venue = false,
        onDismiss = { locationPicker = false },
        onChoose = { chosen ->
            onAreaSelected(Area(chosen.neighborhood, chosen.latitude, chosen.longitude))
            locationPicker = false
        },
    )

    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = { Text("Your location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.hasLocation && state.area.name.isNotBlank()) state.area.name
                        else "Choose where to explore",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Saved privately to your account. Other people cannot see this location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CurrentAreaButton(
                        onArea = {
                            onAreaSelected(it)
                            showLocationDialog = false
                        },
                        enabled = !state.savingLocation,
                    )
                    OutlinedButton(
                        onClick = {
                            showLocationDialog = false
                            locationPicker = true
                        },
                        enabled = !state.savingLocation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Choose area on map")
                    }
                    if (areas.isNotEmpty()) {
                        Text(
                            "Popular areas",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            areas.forEach { area ->
                                FilterChip(
                                    selected = state.area.name.equals(area.name, ignoreCase = true),
                                    onClick = {
                                        onAreaSelected(area)
                                        showLocationDialog = false
                                    },
                                    label = { Text(area.name) },
                                    enabled = !state.savingLocation,
                                )
                            }
                        }
                    }
                    if (state.savingLocation) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLocationDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "circle",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = (-0.5).sp,
                    )
                    Surface(
                        onClick = { showLocationDialog = true },
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Outlined.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                if (state.hasLocation && state.area.name.isNotBlank()) state.area.name.uppercase() else "LOCATION",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // Hero Banner
            item { Hero() }

            // Filter Control Bar: Mode Toggle + Radius Chip
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.height(34.dp)
                        ) {
                            SegmentedButton(
                                selected = !state.forYou,
                                onClick = { onForYou(false) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                Text("Nearby", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            SegmentedButton(
                                selected = state.forYou,
                                onClick = { onForYou(true) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                Text("For You", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        FilterChip(
                            selected = showRadiusSlider,
                            onClick = { showRadiusSlider = !showRadiusSlider },
                            label = { Text("${state.radius} km", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.NearMe,
                                    contentDescription = "Travel radius",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            modifier = Modifier.height(34.dp),
                        )
                    }

                    // Compact Radius Slider Expandable Panel
                    AnimatedVisibility(
                        visible = showRadiusSlider,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        var radius by remember(state.radius) { mutableFloatStateOf(state.radius.toFloat()) }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Travel radius",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${radius.roundToInt()} km",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Slider(
                                    value = radius,
                                    onValueChange = { radius = it },
                                    onValueChangeFinished = { onRadiusSelected(radius.roundToInt()) },
                                    valueRange = 1f..10f,
                                    steps = 8,
                                    enabled = state.hasLocation && !state.savingLocation,
                                    modifier = Modifier.height(32.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Categories Row & Custom Search
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = showSearchField || (customFilter.isNotBlank() && !state.categories.contains(state.category) && state.category.isNotEmpty()),
                            onClick = { showSearchField = !showSearchField },
                            label = { Text("Search", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = "Search activity",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            modifier = Modifier.height(32.dp),
                        )

                        VerticalDivider(Modifier.height(18.dp))

                        (listOf("" to "All") + state.categories.map { it to categoryLabel(it) }).forEach { (id, name) ->
                            FilterChip(
                                selected = state.category == id,
                                onClick = {
                                    onCategorySelected(id)
                                    if (id.isEmpty() || state.categories.contains(id)) {
                                        customFilter = ""
                                        showSearchField = false
                                    }
                                },
                                label = { Text(name, fontSize = 12.sp) },
                                modifier = Modifier.height(32.dp),
                            )
                        }
                    }

                    // Search input expansion
                    AnimatedVisibility(
                        visible = showSearchField || (customFilter.isNotBlank() && !state.categories.contains(state.category) && state.category.isNotEmpty()),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = customFilter,
                                onValueChange = { customFilter = it.take(40) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("Find custom activity...", fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                trailingIcon = {
                                    if (customFilter.isNotEmpty()) {
                                        IconButton(onClick = {
                                            customFilter = ""
                                            onCategorySelected("")
                                            showSearchField = false
                                        }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = { onCategorySelected(customFilter) },
                                enabled = customFilter.isBlank() || validCategory(customFilter.trim()),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text("Apply", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Plans Header
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Plans this week",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.circles.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                            ) {
                                Text(
                                    "${state.circles.size}",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onRetry,
                        enabled = !state.loading && !state.savingLocation && state.hasLocation,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (state.offline) item { OfflineBanner(state.savedAt, onRetry) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.error != null && !state.offline) item { ErrorBox(state.error, retry = onRetry) }
            if (state.hasLocation && !state.loading && (state.error == null || state.offline) && state.circles.isEmpty())
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (state.offline) "No saved circles for these filters."
                                else "No circles here yet.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Try a wider radius or another category, or make the first plan.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            items(state.circles, key = { it.id }) { circle ->
                CircleCard(circle, true) { onCircleSelected(circle.id) }
            }
            if (state.nextCursor.isNotEmpty())
                item {
                    Column(Modifier.fillMaxWidth()) {
                        state.moreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(
                            onClick = onLoadMore,
                            enabled = !state.loadingMore && !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            if (state.loadingMore)
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else
                                Text(
                                    if (state.moreError == null) "Load more circles"
                                    else "Retry loading more"
                                )
                        }
                    }
                }
            item {
                Text(
                    "Meet in public. Check opening hours with the venue before going.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Create a circle") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}
