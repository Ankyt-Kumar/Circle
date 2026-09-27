package com.circle.app.presentation.discover

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circle.app.presentation.theme.CircleTheme
import kotlin.math.cos
import kotlin.math.sin

// Ring geometry and positioning parameters
private val RingCenterInset: Dp = 32.dp
private val TextTrailingReserve: Dp = 110.dp

@Composable
fun Hero(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHigh

    // Continuous animations for signal pulse and orbital motion
    val transition = rememberInfiniteTransition(label = "heroAnimation")

    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heroPulse",
    )

    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heroOrbit",
    )

    val glowAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroLiveBadgeGlow",
    )

    val cardShape = RoundedCornerShape(22.dp)
    val backgroundBrush = Brush.linearGradient(
        colors = listOf(
            primaryContainer,
            secondaryContainer.copy(alpha = 0.85f),
            surfaceContainer.copy(alpha = 0.5f),
        ),
        start = Offset(0f, 0f),
        end = Offset(1000f, 400f),
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.35f),
                        primaryColor.copy(alpha = 0.05f),
                    )
                ),
                shape = cardShape,
            ),
        shape = cardShape,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = backgroundBrush)
                .heightIn(min = 128.dp),
        ) {
            // Signal radar graphic drawn on canvas
            Canvas(modifier = Modifier.matchParentSize()) {
                val center = Offset(size.width - RingCenterInset.toPx(), size.height * 0.5f)

                // Outer ambient glow circle
                drawCircle(
                    color = primaryColor.copy(alpha = 0.08f),
                    radius = 80.dp.toPx(),
                    center = center,
                )

                // Expanding signal ripple waves
                val maxRadius = 72.dp.toPx()
                val wave1Radius = (pulse * maxRadius)
                val wave1Alpha = ((1f - pulse) * 0.35f).coerceAtLeast(0f)

                val wave2Progress = (pulse + 0.5f) % 1f
                val wave2Radius = (wave2Progress * maxRadius)
                val wave2Alpha = ((1f - wave2Progress) * 0.35f).coerceAtLeast(0f)

                drawCircle(
                    color = onPrimaryContainer.copy(alpha = wave1Alpha),
                    radius = wave1Radius,
                    center = center,
                    style = Stroke(1.5.dp.toPx()),
                )

                drawCircle(
                    color = onPrimaryContainer.copy(alpha = wave2Alpha),
                    radius = wave2Radius,
                    center = center,
                    style = Stroke(1.5.dp.toPx()),
                )

                // Static reference rings
                val rings = listOf(
                    58.dp.toPx() to 0.12f,
                    40.dp.toPx() to 0.22f,
                    22.dp.toPx() to 0.35f,
                )
                rings.forEach { (radius, alpha) ->
                    drawCircle(
                        color = onPrimaryContainer.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(1.25.dp.toPx()),
                    )
                }

                // Orbiting gathering nodes (representing active nearby circles)
                val radians1 = Math.toRadians(orbitAngle.toDouble())
                val orbit1Radius = 40.dp.toPx()
                val node1Offset = Offset(
                    x = center.x + (orbit1Radius * cos(radians1)).toFloat(),
                    y = center.y + (orbit1Radius * sin(radians1)).toFloat(),
                )
                drawCircle(
                    color = primaryColor,
                    radius = 4.dp.toPx(),
                    center = node1Offset,
                )
                drawCircle(
                    color = onPrimaryContainer.copy(alpha = 0.25f),
                    radius = 7.dp.toPx(),
                    center = node1Offset,
                    style = Stroke(1.dp.toPx()),
                )

                val radians2 = Math.toRadians((orbitAngle + 180).toDouble())
                val orbit2Radius = 58.dp.toPx()
                val node2Offset = Offset(
                    x = center.x + (orbit2Radius * cos(radians2)).toFloat(),
                    y = center.y + (orbit2Radius * sin(radians2)).toFloat(),
                )
                drawCircle(
                    color = onPrimaryContainer.copy(alpha = 0.8f),
                    radius = 3.dp.toPx(),
                    center = node2Offset,
                )

                // Central live location dot with glow
                drawCircle(
                    color = primaryColor.copy(alpha = 0.3f),
                    radius = 9.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = onPrimaryContainer,
                    radius = 4.5.dp.toPx(),
                    center = center,
                )
            }

            // Main Text Content Column
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 16.dp, end = TextTrailingReserve, bottom = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Live nearby. Good plans, better company. Small groups, plans nearby."
                    },
            ) {
                // Pill Badge
                Surface(
                    color = onPrimaryContainer.copy(alpha = 0.12f),
                    shape = CircleShape,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(primaryColor.copy(alpha = glowAlpha)),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "DISCOVER NEARBY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = onPrimaryContainer,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Good plans\nBetter company",
                    color = onPrimaryContainer,
                    fontSize = 21.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Small groups, plans nearby.",
                    color = onPrimaryContainer.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Preview(name = "Hero — Light", showBackground = true)
@Composable
private fun HeroPreviewLight() {
    CircleTheme(darkTheme = false, dynamicColor = true) {
        Hero(modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "Hero — Dark", showBackground = true)
@Composable
private fun HeroPreviewDark() {
    CircleTheme(darkTheme = true, dynamicColor = true) {
        Hero(modifier = Modifier.padding(16.dp))
    }
}
