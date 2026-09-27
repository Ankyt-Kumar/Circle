package com.circle.app.data.remote

import com.circle.app.data.remote.dto.AttendeeDto
import com.circle.app.data.remote.dto.CircleDto
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject

class HttpCircleApi(private val transport: HttpTransport) : CircleApi {
    constructor(
        baseUrl: String,
        ioDispatcher: CoroutineDispatcher,
    ) : this(HttpTransport(baseUrl, dispatcher = ioDispatcher))

    override suspend fun create(draft: com.circle.app.data.remote.dto.CreateCircleDto): CircleDto =
        parse(
            JSONObject(
                request(
                    "/v1/circles",
                    "POST",
                    JSONObject()
                        .put("request_id", draft.requestId)
                        .put("title", draft.title)
                        .put("description", draft.description)
                        .put("category", draft.category)
                        .put("venue_id", draft.venueId)
                        .put("starts_at", draft.startsAt)
                        .put("ends_at", draft.endsAt)
                        .put("capacity", draft.capacity)
                        .put("minimum_age",draft.minimumAge).put("maximum_age",draft.maximumAge).put("audience",draft.audience),
                )
            )
        )

    override suspend fun search(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        category: String,
    ): List<CircleDto> =
        list(
            request(
                "/v1/circles/search",
                "POST",
                JSONObject()
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("radius_km", radiusKm)
                    .put("category", category),
            )
        )

    override suspend fun searchPage(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        category: String,
        cursor: String,
    ): CirclePageDto {
        val json =
            JSONObject(
                request(
                    "/v1/circles/search",
                    "POST",
                    JSONObject()
                        .put("latitude", latitude)
                        .put("longitude", longitude)
                        .put("radius_km", radiusKm)
                        .put("category", category)
                        .put("page_size", 20)
                        .put("cursor", cursor),
                )
            )
        val array = json.getJSONArray("circles")
        return CirclePageDto(
            (0 until array.length()).map { parse(array.getJSONObject(it)) },
            json.optString("next_cursor", ""),
        )
    }

    override suspend fun mine(): List<CircleDto> = list(request("/v1/me/circles"))

    override suspend fun detail(id: String): CircleDto =
        parse(JSONObject(request("/v1/circles/${pathId(id)}")))

    override suspend fun setJoined(id: String, joined: Boolean) {
        request("/v1/circles/${pathId(id)}/${if (joined) "join" else "leave"}", "POST")
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): String = transport.request(path, method, body)

    private fun list(text: String): List<CircleDto> {
        val array = JSONObject(text).getJSONArray("circles")
        return (0 until array.length()).map { parse(array.getJSONObject(it)) }
    }

    fun parse(json: JSONObject): CircleDto {
        val people: JSONArray = json.getJSONArray("attendees")
        return CircleDto(
            id = json.getString("id"),
            title = json.getString("title"),
            category = json.getString("category"),
            description = json.getString("description"),
            neighborhood = json.getString("neighborhood"),
            venue = json.getString("venue"),
            startsAt = json.getString("starts_at"),
            endsAt = json.getString("ends_at"),
            capacity = json.getInt("capacity"),
            joined = json.getBoolean("joined"),
            distanceM = json.getDouble("distance_m"),
            isHost = json.optBoolean("is_host", false),
            status = json.optString("status", "published"),
            revision = json.optInt("revision", 1),
            venueId = json.optString("venue_id", ""),
            venueAddress = json.optString("venue_address", ""),
            minimumAge = json.optInt("minimum_age",18), maximumAge = json.optInt("maximum_age",100),
            audience = json.optString("audience","everyone"), eligible = json.optBoolean("eligible",false),
            venueFictional = json.optBoolean("venue_fictional", true),
            venueLatitude =
                if (json.isNull("venue_latitude")) null else json.getDouble("venue_latitude"),
            venueLongitude =
                if (json.isNull("venue_longitude")) null else json.getDouble("venue_longitude"),
            attendees =
                (0 until people.length()).map {
                    people.getJSONObject(it).let { person ->
                        AttendeeDto(person.getString("id"), person.getString("name"))
                    }
                },
        )
    }

    private fun pathId(id: String): String = URLEncoder.encode(id, Charsets.UTF_8.name())
}
