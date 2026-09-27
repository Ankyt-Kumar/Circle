package com.circle.app.data.mapper

import com.circle.app.domain.model.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal fun Circle.toCacheJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("category", category)
        .put("description", description)
        .put("neighborhood", neighborhood)
        .put("venue", venue)
        .put("starts_at", startsAt.toString())
        .put("ends_at", endsAt.toString())
        .put("capacity", capacity)
        .put("joined", joined)
        .put("distance_m", distanceM)
        .put("is_host", isHost)
        .put("status", status)
        .put("revision", revision)
        .put("venue_id", venueId)
        .put("venue_address",venueAddress).put("minimum_age",minimumAge).put("maximum_age",maximumAge).put("audience",audience).put("eligible",eligible)
        .put("venue_fictional", venueFictional)
        .put("venue_latitude", venueLatitude)
        .put("venue_longitude", venueLongitude)
        .put(
            "attendees",
            JSONArray().also { a ->
                attendees.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
            },
        )

internal fun CircleFeed.toCacheJson(): String =
    JSONObject()
        .put("at", (savedAt ?: Instant.now()).toString())
        .put("next", nextCursor)
        .put("circles", JSONArray().also { a -> circles.forEach { a.put(it.toCacheJson()) } })
        .toString()
