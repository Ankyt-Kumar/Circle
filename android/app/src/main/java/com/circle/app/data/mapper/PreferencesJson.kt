package com.circle.app.data.mapper

import com.circle.app.domain.model.UserPreferences
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.toPreferences(): UserPreferences {
    val list = getJSONArray("interests")
    return UserPreferences((0 until list.length()).map { list.getString(it) },
        getString("area_name"), getInt("radius_km"), getBoolean("adult_confirmed"), getString("terms_version"),
        optString("birth_date", "").takeUnless { it == "null" }.orEmpty(),
        optString("gender", "").takeUnless { it == "null" }.orEmpty(),
        if (isNull("latitude")) null else getDouble("latitude"),
        if (isNull("longitude")) null else getDouble("longitude"))
}

internal fun UserPreferences.toJson(): JSONObject = JSONObject()
    .put("interests", JSONArray(interests)).put("area_name", areaName).put("radius_km", radiusKm)
    .put("adult_confirmed", adultConfirmed).put("terms_version", termsVersion)
    .put("birth_date",birthDate).put("gender",gender).put("latitude",latitude).put("longitude",longitude)
