package com.circle.app.data.mapper

import com.circle.app.data.remote.dto.CircleDto
import com.circle.app.domain.model.Attendee
import com.circle.app.domain.model.Circle
import java.time.Instant

fun CircleDto.toDomain(): Circle =
    Circle(
        id = id,
        title = title,
        category = category,
        description = description,
        neighborhood = neighborhood,
        venue = venue,
        startsAt = Instant.parse(startsAt),
        endsAt = Instant.parse(endsAt),
        capacity = capacity,
        attendees = attendees.map { Attendee(it.id, it.name) },
        joined = joined,
        distanceM = distanceM,
        isHost = isHost,
        status = status,
        revision = revision,
        venueId = venueId,
        venueAddress = venueAddress, minimumAge = minimumAge, maximumAge = maximumAge, audience = audience, eligible = eligible,
        venueFictional = venueFictional,
        venueLatitude = venueLatitude,
        venueLongitude = venueLongitude,
    )
