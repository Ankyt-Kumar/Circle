package com.circle.app

import com.circle.app.data.local.SessionDiskCache
import com.circle.app.data.remote.HttpTransport
import com.circle.app.data.repository.DefaultAccountRepository
import com.circle.app.domain.model.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineAccountTest {
    @Test
    fun completingOnboardingThenRestartingOfflineKeepsTheConfirmedProfile() = runTest {
        val root = Files.createTempDirectory("circle-account").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/me") { exchange ->
            val saving = exchange.requestMethod == "PUT"
            exchange.requestBody.use { it.readBytes() }
            val body =
                JSONObject()
                    .put("id", "server-account")
                    .put("first_name", if (saving) "Ankit" else "")
                    .put("profile_complete", saving)
                    .put("onboarding_complete", saving)
                    .put("auth_provider", "password")
                    .put("email", "test@example.test")
                    .put("email_verified", false)
            if (saving)
                body.put(
                    "preferences",
                    JSONObject()
                        .put("interests", JSONArray(listOf("coffee")))
                        .put("area_name", "Indiranagar")
                        .put("radius_km", 3)
                        .put("adult_confirmed", true)
                        .put("terms_version", COMMUNITY_TERMS_VERSION)
                        .put("birth_date","1995-06-15").put("gender","male").put("latitude",12.9719).put("longitude",77.6412),
                )
            val bytes = body.toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val repo =
                DefaultAccountRepository(
                    HttpTransport(url),
                    offline = SessionDiskCache(root) { AuthSession("firebase-a") },
                )
            assertFalse(repo.getAccount().onboardingComplete)
            val saved =
                repo.savePreferences(
                    OnboardingDraft("Ankit", true, true, listOf("coffee"), "Indiranagar", 3, "1995-06-15", "male", 12.9719, 77.6412)
                )
            assertTrue(saved.onboardingComplete)
            server.stop(0)
            val restarted =
                DefaultAccountRepository(
                    HttpTransport(url),
                    offline = SessionDiskCache(root) { AuthSession("firebase-a") },
                )
            val cached = restarted.getAccount()
            assertTrue(cached.offline)
            assertTrue(cached.onboardingComplete)
            assertEquals("Ankit", cached.firstName)
            assertEquals("Indiranagar", cached.preferences!!.areaName)
            assertEquals(3, cached.preferences!!.radiusKm)
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }
}
