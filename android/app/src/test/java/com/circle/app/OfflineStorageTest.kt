package com.circle.app

import com.circle.app.data.local.SessionDiskCache
import com.circle.app.data.repository.DiskDraftRepository
import com.circle.app.domain.model.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OfflineStorageTest {
    @Test
    fun restartAccountSwitchAndLateWrite() = runTest {
        val dir = Files.createTempDirectory("circle").toFile()
        try {
            var session = AuthSession("a", 1)
            val cache = SessionDiskCache(dir) { session }
            val old = cache.snapshot()
            cache.write("feed", "A", old)
            assertEquals("A", SessionDiskCache(dir) { session }.read("feed"))
            cache.clear()
            cache.write("feed", "late A", old)
            assertNull(cache.read("feed", old))
            session = AuthSession("b", 2)
            assertNull(cache.read("feed"))
            cache.write("feed", "B")
            cache.write("feed", "late A", old)
            assertEquals("B", cache.read("feed"))
            session = AuthSession("a", 3)
            assertNull(cache.read("feed"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blockInvalidationStopsInflightResponsesAndKeepsDraft() = runTest {
        val dir = Files.createTempDirectory("circle-block").toFile()
        try {
            val cache = SessionDiskCache(dir) { AuthSession("a") }
            val epoch = cache.feedVersion()
            val owner = cache.snapshot()
            cache.write("feed", "contains blocked user", owner, epoch)
            cache.write("draft", "my draft", owner)
            cache.invalidateFeeds(owner)
            cache.write("feed", "late stale response", owner, epoch)
            assertNull(cache.read("feed"))
            assertEquals("my draft", cache.read("draft"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun draftSurvivesProcessRestartAndClears() = runTest {
        val dir = Files.createTempDirectory("circle-draft").toFile()
        try {
            val cache = SessionDiskCache(dir) { AuthSession("a") }
            val draft =
                SavedCircleForm(
                    "Coffee",
                    "Meet nearby",
                    "coffee",
                    "venue",
                    "2026-09-20",
                    "18:00",
                    6,
                    90,
                    "same-request-key",
                )
            DiskDraftRepository(cache).save(draft)
            val reopened = DiskDraftRepository(SessionDiskCache(dir) { AuthSession("a") })
            assertEquals(draft, reopened.load())
            reopened.clear()
            assertNull(DiskDraftRepository(cache).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun expiredCacheIsNotShown() = runTest {
        val dir = Files.createTempDirectory("circle-expiry").toFile()
        try {
            val cache = SessionDiskCache(dir) { AuthSession("a") }
            cache.write("feed", "old")
            dir.listFiles()!!
                .first { it.extension == "json" }
                .writeText("{\"at\":0,\"value\":\"old\"}")
            assertNull(cache.read("feed"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
