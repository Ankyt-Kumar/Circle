package com.circle.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.circle.app.domain.model.*
import com.circle.app.domain.repository.AppearanceRepository
import java.io.IOException
import kotlinx.coroutines.flow.*

private val Context.appearanceStore by preferencesDataStore(name = "circle_appearance")
class DataStoreAppearanceRepository(context: Context) : AppearanceRepository {
    private val store = context.appearanceStore
    private val mode = stringPreferencesKey("mode")
    private val dynamic = booleanPreferencesKey("dynamic_colors")
    override val appearance = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { data -> Appearance(ThemeMode.entries.firstOrNull { it.name == data[mode] } ?: ThemeMode.SYSTEM,
            data[dynamic] ?: true) }.distinctUntilChanged()
    override suspend fun save(value: Appearance) { store.edit { it[mode] = value.mode.name; it[dynamic] = value.dynamicColors } }
}
