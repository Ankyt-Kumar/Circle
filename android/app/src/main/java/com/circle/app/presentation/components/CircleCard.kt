package com.circle.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circle.app.domain.model.*
import com.circle.app.presentation.common.*
import androidx.compose.ui.platform.LocalLocale

@Composable
fun CircleCard(circle: Circle, showDistance: Boolean, open: () -> Unit) {
    val isAvailable = circle.status == "published"
    val fillRatio =
        if (circle.capacity > 0) circle.attendees.size / circle.capacity.toFloat() else 0f

    Card(
        onClick = open,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box {
            ActivityBanner(circle.category, compact = true)
            if (!isAvailable) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                )
            }
        }

        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {

            // Eyebrow row: start time, plus an availability flag when relevant
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    formatTime(circle.startsAt),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                if (!isAvailable) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "NO LONGER AVAILABLE",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Text(
                circle.title,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    circle.neighborhood +
                            if (showDistance)
                                " · ${String.format(LocalLocale.current.platformLocale, "%.1f", circle.distanceM / 1000)} km away"
                            else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                "Ages ${circle.minimumAge}–${circle.maximumAge} · ${audienceLabel(circle.audience)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${circle.attendees.size}/${circle.capacity} going",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CapacityBar(fillRatio = fillRatio, isAvailable = isAvailable)
                }

                CircleStatusPill(circle = circle, isAvailable = isAvailable)
            }
        }
    }
}

@Composable
private fun CapacityBar(fillRatio: Float, isAvailable: Boolean) {
    Box(
        Modifier
            .width(72.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fillRatio.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (isAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
        )
    }
}

@Composable
private fun CircleStatusPill(circle: Circle, isAvailable: Boolean) {
    data class PillStyle(val label: String, val container: Color, val content: Color)

    val style = when {
        !isAvailable -> PillStyle(
            "Unavailable",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        circle.joined -> PillStyle(
            "You're in",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        circle.spots == 0 -> PillStyle(
            "Full",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        circle.spots <= 3 -> PillStyle(
            "${circle.spots} spots left",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> PillStyle(
            "${circle.spots} spots left",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    Surface(shape = RoundedCornerShape(50), color = style.container, contentColor = style.content) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (circle.joined && isAvailable) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(style.label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}