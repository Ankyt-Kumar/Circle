package com.circle.app.data.repository

import com.circle.app.data.local.NoPreferencesCache
import com.circle.app.data.local.PreferencesCache
import com.circle.app.data.mapper.toJson
import com.circle.app.data.mapper.toPreferences
import com.circle.app.data.remote.ApiException
import com.circle.app.data.remote.HttpTransport
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.Account
import com.circle.app.domain.model.COMMUNITY_TERMS_VERSION
import com.circle.app.domain.model.OnboardingDraft
import com.circle.app.domain.model.UserPreferences
import com.circle.app.domain.repository.AccountRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

class DefaultAccountRepository(
    private val transport: HttpTransport,
    private val cache: PreferencesCache = NoPreferencesCache,
    private val offline: com.circle.app.data.local.SessionDiskCache? = null,
) : AccountRepository {
    override suspend fun getAccount(): Account {
        val owner = offline?.snapshot()
        try {
            var raw = ""
            val account = mapped { transport.request("/v1/me").also { raw = it } }
            if (owner != null) offline.write("account", raw, owner)
            return account
        } catch (e: CircleException) {
            if (e.reason != FailureReason.NETWORK || owner == null) throw e
            val raw = offline.read("account", owner) ?: throw e
            return mapped { raw }.copy(offline = true)
        }
    }

    override suspend fun saveFirstName(name: String) = saveAndCache {
        transport.request("/v1/me", "PUT", JSONObject().put("first_name", name))
    }

    override suspend fun savePreferences(draft: OnboardingDraft) = saveAndCache {
        val preferences =
            UserPreferences(
                draft.interests,
                draft.areaName,
                draft.radiusKm,
                draft.adultConfirmed,
                if (draft.termsAccepted) COMMUNITY_TERMS_VERSION else "",
                draft.birthDate, draft.gender, draft.latitude, draft.longitude,
            )
        transport.request(
            "/v1/me/preferences",
            "PUT",
            preferences.toJson().put("first_name", draft.firstName),
        )
    }

    override fun observePreferences(accountId: String) = cache.observe(accountId)

    override suspend fun saveLocation(area: com.circle.app.domain.model.Area, radiusKm: Int) = saveAndCache {
        transport.request("/v1/me/location", "PUT", JSONObject()
            .put("area_name", area.name).put("latitude", area.latitude)
            .put("longitude", area.longitude).put("radius_km", radiusKm))
    }

    private suspend fun saveAndCache(request: suspend () -> String): Account {
        val owner = offline?.snapshot()
        var raw = ""
        val account = mapped { request().also { raw = it } }
        if (owner != null) offline.invalidateFeeds(owner)
        if (owner != null) offline.write("account", raw, owner)
        return account
    }

    private suspend fun mapped(block: suspend () -> String): Account =
        try {
            val json = JSONObject(block())
            val account =
                Account(
                    json.getString("id"),
                    json.getString("first_name"),
                    json.getBoolean("profile_complete"),
                    json.getString("auth_provider"),
                    json.getString("email"),
                    json.getBoolean("email_verified"),
                    json.optBoolean("onboarding_complete", false),
                    json.optJSONObject("preferences")?.toPreferences(),
                )
            // A full disk cannot turn a committed server save into an apparent failure.
            // This cache never decides whether authentication/onboarding is complete.
            try {
                cache.store(account.id, account.preferences)
            } catch (_: IOException) {}
            account
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw CircleException(
                when (e.statusCode) {
                    400 -> FailureReason.INVALID_INPUT
                    401 -> FailureReason.SIGN_IN_REQUIRED
                    403 -> FailureReason.ACCOUNT_BLOCKED
                    428 -> FailureReason.ONBOARDING_REQUIRED
                    503 -> FailureReason.SERVICE_UNAVAILABLE
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
