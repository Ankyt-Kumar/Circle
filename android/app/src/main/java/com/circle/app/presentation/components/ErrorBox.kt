package com.circle.app.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.circle.app.domain.model.*
import com.circle.app.presentation.theme.*
import com.circle.app.presentation.components.*
import com.circle.app.presentation.common.*

@Composable
fun ErrorBox(message: String, enabled: Boolean = true, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = retry, enabled = enabled) { Text("Retry") }
        }
    }
}

