package com.circle.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.circle.app.data.local.DataStorePreferencesCache
import com.circle.app.domain.model.COMMUNITY_TERMS_VERSION
import com.circle.app.domain.model.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PreferencesCacheTest {
    @Test fun preferencesSurviveReopenAndRemainIsolatedByAccount() = runBlocking {
        val directory = Files.createTempDirectory("circle-preferences-test").toFile()
        val file = directory.resolve("preferences.preferences_pb")
        val a = UserPreferences(listOf("coffee"), "Indiranagar", 3, true, COMMUNITY_TERMS_VERSION, "1995-06-15", "male", 12.9719, 77.6412)
        val b = a.copy(interests = listOf("games"), areaName = "BTM Layout", radiusKm = 8)
        var job = SupervisorJob()
        try {
            var store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            var cache = DataStorePreferencesCache(store)
            cache.store("a", a); cache.store("b", b)
            assertEquals(a, cache.observe("a").first())
            assertEquals(b, cache.observe("b").first())
            assertNull(cache.observe("new-account").first())
            job.cancelAndJoin()
            job = SupervisorJob()
            store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            cache = DataStorePreferencesCache(store)
            assertEquals(a, cache.observe("a").first())
            assertEquals(b, cache.observe("b").first())
            cache.store("a", null)
            assertNull(cache.observe("a").first()); assertEquals(b, cache.observe("b").first())
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
