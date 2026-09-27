package com.circle.app.presentation.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatTime(time: Instant): String = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a z", Locale.getDefault())
    .withZone(ZoneId.systemDefault()).format(time)
