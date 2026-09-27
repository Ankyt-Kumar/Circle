package com.circle.app.data.repository

import com.circle.app.data.remote.*
import com.circle.app.domain.error.*
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.SafetyRepository
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

class DefaultSafetyRepository(
    private val transport: HttpTransport,
    private val cache: com.circle.app.data.local.SessionDiskCache? = null,
) : SafetyRepository {
    override suspend fun block(circleId: String, userId: String): Unit = mapped {
        val owner = cache?.snapshot()
        transport.request(
            "/v1/me/blocks/${encode(userId)}",
            "PUT",
            JSONObject().put("circle_id", circleId),
        )
        if (owner != null) cache.invalidateFeeds(owner)
        Unit
    }

    override suspend fun unblock(userId: String): Unit = mapped {
        val owner = cache?.snapshot()
        transport.request("/v1/me/blocks/${encode(userId)}", "DELETE")
        if (owner != null) cache.invalidateFeeds(owner)
        Unit
    }

    override suspend fun blockedUsers(): List<BlockedUser> = mapped {
        val list = JSONObject(transport.request("/v1/me/blocks")).getJSONArray("users")
        List(list.length()) { i ->
            list.getJSONObject(i).let { BlockedUser(it.getString("id"), it.getString("name")) }
        }
    }

    private fun encode(id: String) = URLEncoder.encode(id, "UTF-8")

    private suspend fun <T> mapped(block: suspend () -> T): T =
        try {
            block()
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
                    409 -> FailureReason.CONFLICT
                    423 -> FailureReason.USER_BLOCKED
                    429 -> FailureReason.RATE_LIMIT
                    else -> FailureReason.UNKNOWN
                },
                e,
            )
        } catch (e: IOException) {
            throw CircleException(FailureReason.NETWORK, e)
        } catch (e: JSONException) {
            throw CircleException(FailureReason.INVALID_RESPONSE, e)
        }
}
