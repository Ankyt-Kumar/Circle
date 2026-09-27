package com.circle.app.presentation.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circle.app.domain.model.*

data class AppearanceControls(val appearance: Appearance = Appearance(), val error: String? = null,
    val onChange: (Appearance) -> Unit = {})
val LocalAppearanceControls = staticCompositionLocalOf { AppearanceControls() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings() {
    val controls = LocalAppearanceControls.current
    val appearance = controls.appearance
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(selected = appearance.mode == mode,
                    onClick = { controls.onChange(appearance.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) { Text(mode.label) }
            }
        }
        controls.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
