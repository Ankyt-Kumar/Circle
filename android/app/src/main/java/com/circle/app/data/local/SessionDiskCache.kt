package com.circle.app.data.local

import com.circle.app.domain.model.AuthSession
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Private, non-backed-up storage. Every operation is fenced by UID and session generation.
// No tokens, passwords, chat history or continuous location history are stored here.
// The private account snapshot contains the explicitly saved location, birthday and gender.
class SessionDiskCache(private val root: File, private val session: () -> AuthSession) {
    private val lock = Any()
    private var feedEpoch = 0L

    fun feedVersion(): Long = synchronized(lock) { feedEpoch }

    private var blocked: AuthSession? = null

    fun snapshot(): AuthSession = session()

    private fun file(key: String) =
        File(
            root,
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {
                "%02x".format(it)
            } + ".json",
        )

    private fun prepare(expected: AuthSession): Boolean {
        if (
            expected == blocked ||
                expected.userId == null ||
                session().userId != expected.userId ||
                session().generation != expected.generation
        )
            return false
        root.mkdirs()
        val owner = File(root, "owner")
        if (!owner.exists() || owner.readText() != expected.userId) {
            root.listFiles()?.forEach { it.delete() }
            owner.writeText(expected.userId)
        }
        return true
    }

    suspend fun read(
        key: String,
        expected: AuthSession = snapshot(),
        maxAgeMs: Long = 86_400_000,
    ): String? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    if (!prepare(expected)) return@synchronized null
                    val target = file(key)
                    if (!target.exists()) return@synchronized null
                    val envelope = JSONObject(target.readText())
                    if (System.currentTimeMillis() - envelope.getLong("at") > maxAgeMs) {
                        target.delete()
                        return@synchronized null
                    }
                    envelope.getString("value")
                } catch (_: Exception) {
                    null
                }
            }
        }

    suspend fun write(
        key: String,
        value: String,
        expected: AuthSession = snapshot(),
        expectedFeedVersion: Long? = null,
    ) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    if (
                        (expectedFeedVersion != null && expectedFeedVersion != feedEpoch) ||
                            !prepare(expected) ||
                            value.length > 1_000_000
                    )
                        return@synchronized
                    val target = file(key)
                    val temporary = File(root, target.name + ".tmp")
                    temporary.writeText(
                        JSONObject()
                            .put("at", System.currentTimeMillis())
                            .put("value", value)
                            .toString()
                    )
                    if (!temporary.renameTo(target)) temporary.delete()
                    root
                        .listFiles()
                        ?.filter {
                            it.extension == "json" && it != file("draft") && it != file("account")
                        }
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(24)
                        ?.forEach { it.delete() }
                } catch (_: IOException) {
                    /* Disk failure must never turn a server success into a failure. */
                }
            }
        }

    suspend fun remove(key: String, expected: AuthSession = snapshot()) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    if (prepare(expected)) file(key).delete()
                } catch (_: IOException) {}
                Unit
            }
        }

    // Called synchronously at sign-out/account switch, before exposing the new session.
    fun clear() {
        synchronized(lock) {
            feedEpoch++
            blocked = session()
            root.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun invalidateFeeds(expected: AuthSession = snapshot()) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    if (prepare(expected)) {
                        feedEpoch++
                        root
                            .listFiles()
                            ?.filter {
                                it.extension == "json" &&
                                    it != file("draft") &&
                                    it != file("account")
                            }
                            ?.forEach { it.delete() }
                    }
                } catch (_: IOException) {}
            }
        }
}
