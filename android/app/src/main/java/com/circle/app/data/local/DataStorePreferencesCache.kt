package com.circle.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.circle.app.data.mapper.toJson
import com.circle.app.data.mapper.toPreferences
import com.circle.app.domain.model.UserPreferences
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

// One application-scoped DataStore. Keys use the server's unique account ID.
val Context.circlePreferencesDataStore by preferencesDataStore(name = "circle_preferences")

class DataStorePreferencesCache(private val dataStore: DataStore<Preferences>) : PreferencesCache {
    override fun observe(accountId: String) = dataStore.data.map { data ->
        data[stringPreferencesKey("account_$accountId")]?.let { raw ->
            try { JSONObject(raw).toPreferences().takeIf { it.valid() } }
            catch (_: JSONException) { null }
        }
    }.catch { error -> if (error is IOException) emit(null) else throw error }.distinctUntilChanged()

    override suspend fun store(accountId: String, preferences: UserPreferences?) {
        require(accountId.isNotBlank())
        dataStore.edit { data ->
            val key = stringPreferencesKey("account_$accountId")
            if (preferences?.valid() == true) data[key] = preferences.toJson().toString() else data.remove(key)
        }
    }
}
