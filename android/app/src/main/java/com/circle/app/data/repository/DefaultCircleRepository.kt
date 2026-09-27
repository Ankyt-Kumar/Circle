package com.circle.app.data.repository

import com.circle.app.data.mapper.toCacheJson
import com.circle.app.data.mapper.toDomain
import com.circle.app.data.remote.ApiException
import com.circle.app.data.remote.CircleApi
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.Circle
import com.circle.app.domain.model.CircleFeed
import com.circle.app.domain.model.CircleQuery
import com.circle.app.domain.repository.CircleRepository
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

class DefaultCircleRepository(
    private val api: CircleApi,
    private val cache: com.circle.app.data.local.SessionDiskCache? = null,
) : CircleRepository {
    override suspend fun create(draft: com.circle.app.domain.model.CircleDraft): Circle = mapped {
        val owner = cache?.snapshot()
        val created =
            api.create(
                    com.circle.app.data.remote.dto.CreateCircleDto(
                        draft.requestId,
                        draft.title,
                        draft.description,
                        draft.category,
                        draft.venueId,
                        draft.startsAt.toString(),
                        draft.endsAt.toString(),
                        draft.capacity, draft.minimumAge, draft.maximumAge, draft.audience,
                    )
                )
                .toDomain()
        if (owner != null) cache.invalidateFeeds(owner)
        created
    }

    override suspend fun getNearby(query: CircleQuery): List<Circle> = mapped {
        api.search(query.area.latitude, query.area.longitude, query.radiusKm, query.category).map {
            it.toDomain()
        }
    }

    override suspend fun getCircle(id: String): Circle = mapped { api.detail(id).toDomain() }

    override suspend fun getJoined(): List<Circle> = mapped { api.mine().map { it.toDomain() } }

    override suspend fun setJoined(id: String, joined: Boolean): Unit = mapped {
        val owner = cache?.snapshot()
        api.setJoined(id, joined)
        if (owner != null) cache.invalidateFeeds(owner)
        Unit
    }

    override suspend fun getNearbyPage(query: CircleQuery, cursor: String): CircleFeed {
        // SessionDiskCache hashes this key and excludes cached files from Android backup.
        val key = "feed:${query.area.latitude}:${query.area.longitude}:${query.radiusKm}:${query.category.lowercase()}"
        return cached(key, cursor.isEmpty()) {
            val page =
                api.searchPage(
                    query.area.latitude,
                    query.area.longitude,
                    query.radiusKm,
                    query.category,
                    cursor,
                )
            CircleFeed(page.circles.map { it.toDomain() }, page.nextCursor, savedAt = Instant.now())
        }
    }

    override suspend fun getJoinedFeed(): CircleFeed =
        cached("mine", true) {
            CircleFeed(api.mine().map { it.toDomain() }, savedAt = Instant.now())
        }

    private suspend fun cached(
        key: String?,
        first: Boolean,
        fetch: suspend () -> CircleFeed,
    ): CircleFeed {
        val owner = cache?.snapshot()
        val feedVersion = cache?.feedVersion()
        try {
            val result = mapped { fetch() }
            if (key != null && first && owner != null)
                cache.write(key, result.toCacheJson(), owner, feedVersion)
            return result
        } catch (e: CircleException) {
            if (e.reason != FailureReason.NETWORK || !first || key == null || owner == null) throw e
            val raw = cache.read(key, owner) ?: throw e
            return try {
                val j = JSONObject(raw)
                val list = j.getJSONArray("circles")
                val parser =
                    com.circle.app.data.remote.HttpCircleApi(
                        "http://127.0.0.1",
                        kotlinx.coroutines.Dispatchers.IO,
                    )
                CircleFeed(
                    (0 until list.length()).map { parser.parse(list.getJSONObject(it)).toDomain() },
                    "",
                    true,
                    Instant.parse(j.getString("at")),
                )
            } catch (_: Exception) {
                throw e
            }
        }
    }

    private suspend fun <T> mapped(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            val reason =
                when (e.statusCode) {
                    400 -> FailureReason.INVALID_INPUT
                    401 -> FailureReason.SIGN_IN_REQUIRED
                    403 -> FailureReason.ACCOUNT_BLOCKED
                    404,
                    410 -> FailureReason.NOT_FOUND
                    428 -> FailureReason.ONBOARDING_REQUIRED
                    422 -> FailureReason.INELIGIBLE
                    409 -> FailureReason.CONFLICT
                    423 -> FailureReason.USER_BLOCKED
                    else -> FailureReason.UNKNOWN
                }
            throw CircleException(reason, e)
        } catch (e: IOException) {
            throw CircleException(FailureReason.NETWORK, e)
        } catch (e: JSONException) {
            throw CircleException(FailureReason.INVALID_RESPONSE, e)
        } catch (e: DateTimeParseException) {
            throw CircleException(FailureReason.INVALID_RESPONSE, e)
        }
}
