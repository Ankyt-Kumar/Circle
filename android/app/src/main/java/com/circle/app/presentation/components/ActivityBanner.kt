package com.circle.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circle.app.presentation.common.categoryLabel

@Composable
fun ActivityBanner(
    category: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val tint = MaterialTheme.colorScheme.secondaryContainer
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    val symbol =
        when (category) {
            "coffee" -> "☕"
            "games" -> "✦"
            "fitness" -> "↗"
            else -> "❋"
        }

    val badgeSize = if (compact) 48.dp else 104.dp
    val hPad = if (compact) 16.dp else 22.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 70.dp else 170.dp),
        shape = RoundedCornerShape(if (compact) 20.dp else 28.dp),
        color = tint,
        shadowElevation = 6.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize()) {
                val ringCenter = Offset(size.width * .82f, size.height * .5f)
                // Soft concentric rings radiating from behind the icon medallion.
                drawCircle(ink.copy(alpha = .05f), size.height * .95f, ringCenter, style = Stroke(1.5.dp.toPx()))
                drawCircle(ink.copy(alpha = .08f), size.height * .70f, ringCenter, style = Stroke(2.dp.toPx()))
                drawCircle(ink.copy(alpha = .12f), size.height * .46f, ringCenter, style = Stroke(2.5.dp.toPx()))
            }

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = hPad, vertical = if (compact) 8.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    categoryLabel(category).uppercase(),
                    color = ink,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold,
                )

                Box(
                    Modifier
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(ink.copy(alpha = .12f))
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        symbol,
                        fontSize = if (compact) 22.sp else 48.sp,
                        color = ink,
                    )
                }
            }
        }
    }
}