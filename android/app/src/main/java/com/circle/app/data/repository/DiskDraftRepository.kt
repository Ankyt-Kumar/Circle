package com.circle.app.data.repository

import com.circle.app.data.local.SessionDiskCache
import com.circle.app.domain.model.SavedCircleForm
import com.circle.app.domain.repository.DraftRepository
import org.json.JSONObject

// Scope the repository to the session that opened this form, including queued writes.
class DiskDraftRepository(private val cache: SessionDiskCache) : DraftRepository {
    private val owner = cache.snapshot()

    override suspend fun load(): SavedCircleForm? =
        try {
            cache.read("draft", owner, 30L * 86_400_000)?.let { raw ->
                val j = JSONObject(raw)
                SavedCircleForm(
                    j.getString("title"),
                    j.getString("description"),
                    j.getString("category"),
                    j.getString("venue"),
                    j.getString("date"),
                    j.getString("time"),
                    j.getInt("capacity"),
                    j.getInt("duration"),
                    j.getString("request"),
                    j.optInt("minimum_age",18),j.optInt("maximum_age",75),j.optString("audience","everyone"),
                    j.optJSONObject("venue_data")?.let { v -> com.circle.app.domain.model.PublicVenue(
                        v.getString("id"),v.getString("name"),v.getString("area"),
                        if(v.isNull("latitude"))null else v.getDouble("latitude"),
                        if(v.isNull("longitude"))null else v.getDouble("longitude"),
                        v.optBoolean("fictional",false),address=v.optString("address")) },
                    j.optString("time_zone","Asia/Kolkata"),
                )
            }
        } catch (_: org.json.JSONException) {
            null
        }

    override suspend fun save(form: SavedCircleForm) {
        cache.write(
            "draft",
            JSONObject()
                .put("title", form.title)
                .put("description", form.description)
                .put("category", form.category)
                .put("venue", form.venueId)
                .put("date", form.date)
                .put("time", form.time)
                .put("capacity", form.capacity)
                .put("duration", form.durationMinutes)
                .put("request", form.requestId)
                .put("minimum_age",form.minimumAge).put("maximum_age",form.maximumAge).put("audience",form.audience)
                .put("time_zone",form.timeZone)
                .put("venue_data",form.selectedVenue?.let { v -> JSONObject().put("id",v.id).put("name",v.name).put("area",v.neighborhood)
                    .put("latitude",v.latitude).put("longitude",v.longitude).put("fictional",v.fictional).put("address",v.address) })
                .toString(),
            owner,
        )
    }

    override suspend fun clear() {
        cache.remove("draft", owner)
    }
}
