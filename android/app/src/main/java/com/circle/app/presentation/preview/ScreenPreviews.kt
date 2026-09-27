package com.circle.app.presentation.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.circle.app.domain.model.Area
import com.circle.app.domain.model.Attendee
import com.circle.app.domain.model.Circle
import com.circle.app.presentation.auth.AuthMode
import com.circle.app.presentation.auth.AuthScreen
import com.circle.app.presentation.auth.AuthUiState
import com.circle.app.presentation.detail.CircleDetailScreen
import com.circle.app.presentation.detail.CircleDetailUiState
import com.circle.app.presentation.discover.DiscoverScreen
import com.circle.app.presentation.discover.DiscoverUiState
import com.circle.app.presentation.mycircles.MyCirclesScreen
import com.circle.app.presentation.mycircles.MyCirclesUiState
import com.circle.app.presentation.theme.CircleTheme
import java.time.Instant

private val previewArea = Area("Koramangala", 12.9352, 77.6245)
private val previewCircle =
    Circle(
        id = "preview",
        title = "Coffee & new connections",
        category = "coffee",
        description = "A relaxed hour to meet a small group. Fictional preview data.",
        neighborhood = "Mangalore",
        venue = "Sample public café",
        startsAt = Instant.parse("2026-09-12T08:00:00Z"),
        endsAt = Instant.parse("2026-09-12T09:30:00Z"),
        capacity = 6,
        attendees = listOf(Attendee("1", "Riya"), Attendee("2", "Arjun")),
        joined = false,
        distanceM = 850.0,
    )

// Login Preview
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LoginPreview() {
    CircleTheme {
        AuthScreen(
            AuthUiState(),
            testing = false,
            googleAvailable = true,
            null,
            {},
            {},
            {},
            {},
            {},
            {})
    }
}

// SignIn Preview
@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun SignupPreview() {
    CircleTheme {
        AuthScreen(
            AuthUiState(mode = AuthMode.SIGNUP),
            testing = false,
            googleAvailable = true,
            null,
            {},
            {},
            {},
            {},
            {},
            {})
    }
}

// Discover Screen
@Preview(showBackground = true, name = "Discover", widthDp = 390, heightDp = 844)
@Composable
private fun DiscoverPreview() {
    CircleTheme {
        DiscoverScreen(
            DiscoverUiState(area = previewArea, circles = listOf(previewCircle), loading = false),
            listOf(previewArea),
            {},
            {},
            {},
            {},
            {},
        )
    }
}

// Circle Detail Screen Preview
@Preview(showBackground = true, name = "Circle detail", widthDp = 390, heightDp = 844)
@Composable
private fun DetailPreview() {
    CircleTheme {
        CircleDetailScreen(CircleDetailUiState(circle = previewCircle, loading = false), {}, {}, {})
    }
}

// My Circle Screen preview
@Preview(showBackground = true, name = "My circles", widthDp = 390, heightDp = 844)
@Composable
private fun MyCirclesPreview() {
    CircleTheme {
        MyCirclesScreen(
            MyCirclesUiState(
                circles = listOf(previewCircle.copy(joined = true)),
                loading = false,
                locationName = previewArea.name,
            ),
            {},
            {},
            {},
        )
    }
}

// Empty My circle preview
@Preview(showBackground = true, name = "My circles empty", widthDp = 390, heightDp = 844)
@Composable
private fun MyCirclesEmptyPreview() {
    CircleTheme {
        MyCirclesScreen(
            MyCirclesUiState(emptyList(), loading = false, locationName = previewArea.name),
            {},
            {},
            {},
        )
    }
}

// Create Circle Preview
@Preview(showBackground = true, name = "Create circle", widthDp = 390, heightDp = 844)
@Composable
private fun CreatePreview() {
    CircleTheme {
        com.circle.app.presentation.create.CreateCircleScreen(
            com.circle.app.presentation.create.CreateCircleUiState(
                title = "Coffee and conversation",
                venueId = "preview",
                date = "2026-09-12",
                venues =
                    listOf(
                        com.circle.app.domain.model.PublicVenue(
                            "preview",
                            "Sample public café",
                            "Koramangala",
                        )
                    ),
            ),
            {},
            {},
            {},
        )
    }
}
