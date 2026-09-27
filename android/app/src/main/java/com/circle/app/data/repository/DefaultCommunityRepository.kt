package com.circle.app.data.repository

import com.circle.app.data.mapper.toDomain
import com.circle.app.data.remote.*
import com.circle.app.domain.error.*
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.CommunityRepository
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class DefaultCommunityRepository(
    private val http: HttpTransport,
    private val cache: com.circle.app.data.local.SessionDiskCache? = null,
) : CommunityRepository {
    private fun path(id: String) = URLEncoder.encode(id, "UTF-8")

    override suspend fun suggestionsAvailable() = request("/v1/features").optBoolean("ai_suggestions", false)

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject =
        try {
            http.request(path, method, body).let {
                if (it.isBlank()) JSONObject() else JSONObject(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw CircleException(
                when (e.statusCode) {
                    400 -> FailureReason.INVALID_INPUT
                    401 -> FailureReason.SIGN_IN_REQUIRED
                    403 -> FailureReason.ACCOUNT_BLOCKED
                    404,
                    410 -> FailureReason.NOT_FOUND
                    422 -> FailureReason.INELIGIBLE
                    409 -> FailureReason.CONFLICT
                    423 -> FailureReason.USER_BLOCKED
                    428 -> FailureReason.ONBOARDING_REQUIRED
                    429 -> FailureReason.RATE_LIMIT
                    503 -> FailureReason.SERVICE_UNAVAILABLE
                    else -> FailureReason.UNKNOWN
                },
                e,
            )
        } catch (e: IOException) {
            throw CircleException(FailureReason.NETWORK, e)
        }

    private fun message(o: JSONObject) =
        ChatMessage(
            o.getLong("id"),
            o.getString("client_id"),
            o.getString("author_id"),
            o.getString("name"),
            o.getString("body"),
            o.getString("status"),
            Instant.parse(o.getString("created_at")),
        )

    override suspend fun venues(query: String): List<PublicVenue> {
        val a = request("/v1/venues?q=${path(query)}").getJSONArray("venues")
        return (0 until a.length()).map {
            a.getJSONObject(it).let { v ->
                PublicVenue(
                    v.getString("id"),
                    v.getString("name"),
                    v.getString("neighborhood"),
                    v.getDouble("latitude"),
                    v.getDouble("longitude"),
                    v.getBoolean("fictional"),
                    v.optString("source_url"),
                    v.optString("meeting_note"),
                    v.optString("address"),
                )
            }
        }
    }

    override suspend fun categories(): List<String> {
        val a = request("/v1/categories").getJSONArray("categories")
        return (0 until a.length()).map { a.getString(it) }
    }
    override suspend fun createVenue(draft: com.circle.app.domain.model.VenueDraft): com.circle.app.domain.model.PublicVenue {
        val v=request("/v1/venues","POST",JSONObject().put("request_id",draft.requestId).put("name",draft.name)
            .put("address",draft.address).put("neighborhood",draft.neighborhood).put("latitude",draft.latitude)
            .put("longitude",draft.longitude).put("public_confirmed",draft.publicConfirmed))
        return com.circle.app.domain.model.PublicVenue(v.getString("id"),v.getString("name"),v.getString("neighborhood"),
            v.getDouble("latitude"),v.getDouble("longitude"),false,address=v.getString("address"))
    }

    override suspend fun messages(circleId: String, before: Long): ChatPage {
        val o = request("/v1/circles/${path(circleId)}/messages?before=$before")
        val a = o.getJSONArray("messages")
        return ChatPage(
            (0 until a.length()).map { message(a.getJSONObject(it)) },
            o.getBoolean("archived"),
            o.getBoolean("private_ai"),
            o.getBoolean("has_older"),
        )
    }

    override suspend fun send(circleId: String, clientId: String, body: String) =
        message(
            request(
                "/v1/circles/${path(circleId)}/messages",
                "POST",
                JSONObject().put("client_id", clientId).put("body", body),
            )
        )

    override suspend fun guide(circleId: String): ConversationGuide {
        val o = request("/v1/circles/${path(circleId)}/conversation-guide")
        val a = o.getJSONArray("icebreakers")
        return ConversationGuide(
            (0 until a.length()).map { a.getString(it) },
            o.getBoolean("ended"),
            o.getInt("joined"),
            o.getInt("self_reported_attendance"),
        )
    }

    override suspend fun edit(circleId: String, revision: Int, draft: CircleDraft) {
        val owner = cache?.snapshot()
        request(
            "/v1/circles/${path(circleId)}",
            "PUT",
            JSONObject()
                .put("revision", revision)
                .put("title", draft.title)
                .put("description", draft.description)
                .put("category", draft.category)
                .put("venue_id", draft.venueId)
                .put("starts_at", draft.startsAt.toString())
                .put("ends_at", draft.endsAt.toString())
                .put("capacity", draft.capacity).put("minimum_age",draft.minimumAge).put("maximum_age",draft.maximumAge).put("audience",draft.audience),
        )
        if (owner != null) cache.invalidateFeeds(owner)
    }

    override suspend fun cancel(circleId: String, revision: Int) {
        val owner = cache?.snapshot()
        request(
            "/v1/circles/${path(circleId)}/cancel",
            "POST",
            JSONObject().put("revision", revision),
        )
        if (owner != null) cache.invalidateFeeds(owner)
    }

    override suspend fun feedback(circleId: String, attended: Boolean, rating: String) {
        request(
            "/v1/circles/${path(circleId)}/feedback",
            "POST",
            JSONObject().put("attended", attended).put("rating", rating),
        )
    }

    override suspend fun notices(): List<CircleNotice> {
        val a = request("/v1/me/notifications").getJSONArray("notifications")
        return (0 until a.length()).map {
            a.getJSONObject(it).let { n ->
                CircleNotice(
                    n.getLong("id"),
                    n.getString("circle_id"),
                    n.getString("title"),
                    n.getString("body"),
                    n.getBoolean("read"),
                )
            }
        }
    }

    override suspend fun readNotice(id: Long) {
        request("/v1/me/notifications/$id/read", "POST")
    }

    override suspend fun noticePreferences(): NoticePreferences {
        val o = request("/v1/me/notification-preferences")
        return NoticePreferences(
            o.getBoolean("reminders"),
            o.getBoolean("changes"),
            o.getBoolean("push_enabled"),
        )
    }

    override suspend fun saveNoticePreferences(value: NoticePreferences) {
        request(
            "/v1/me/notification-preferences",
            "PUT",
            JSONObject()
                .put("reminders", value.reminders)
                .put("changes", value.changes)
                .put("push_enabled", value.pushEnabled),
        )
    }

    override suspend fun registerDevice(token: String) {
        request("/v1/me/device-token", "PUT", JSONObject().put("token", token))
    }

    override suspend fun removeDevice(token: String) {
        request("/v1/me/device-token", "DELETE", JSONObject().put("token", token))
    }

    override suspend fun stats(): MemberStats {
        val o = request("/v1/me/stats")
        return MemberStats(o.getInt("joined"), o.getInt("hosted"), o.getInt("attended"))
    }

    override suspend fun deleteAccount() {
        request("/v1/me", "DELETE")
    }

    override suspend fun suggest(prompt: String): ActivitySuggestion {
        val o = request("/v1/ai/draft", "POST", JSONObject().put("prompt", prompt))
        return ActivitySuggestion(
            o.getString("title"),
            o.getString("description"),
            o.getString("category"),
            o.getString("venue_id"),
            o.getString("time_suggestion"),
        )
    }

    override suspend fun recommend(query: CircleQuery): Recommendations {
        val key = "recommended:${query.area.latitude}:${query.area.longitude}:${query.radiusKm}:${query.category.lowercase()}"
        val owner = cache?.snapshot()
        val feedVersion = cache?.feedVersion()
        var offline = false
        val o =
            try {
                request(
                        "/v1/circles/recommended",
                        "POST",
                        JSONObject()
                            .put("latitude", query.area.latitude)
                            .put("longitude", query.area.longitude)
                            .put("radius_km", query.radiusKm)
                            .put("category", query.category),
                    )
                    .also {
                        it.put("saved_at", Instant.now().toString())
                        if (key != null && owner != null)
                            cache.write(key, it.toString(), owner, feedVersion)
                    }
            } catch (e: CircleException) {
                if (e.reason != FailureReason.NETWORK || key == null || owner == null) throw e
                val raw = cache.read(key, owner) ?: throw e
                offline = true
                try {
                    JSONObject(raw)
                } catch (_: org.json.JSONException) {
                    throw e
                }
            }
        val a = o.getJSONArray("circles")
        return Recommendations(
            (0 until a.length()).map { HttpCircleApi(http).parse(a.getJSONObject(it)).toDomain() },
            o.getString("basis"),
            offline,
            Instant.parse(o.getString("saved_at")),
        )
    }
}
