package com.circle.app

import com.circle.app.data.remote.*
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.model.Account
import com.circle.app.domain.repository.AccountRepository
import com.circle.app.domain.usecase.SaveFirstName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AuthContractTest {
    private class Credentials : RequestCredentials {
        var generation = 1L
        var rejected = 0
        override suspend fun snapshot() = CredentialSnapshot("token-a", generation)
        override fun isCurrent(snapshot: CredentialSnapshot) = snapshot.generation == generation
        override suspend fun rejected(snapshot: CredentialSnapshot, status: Int) { if (isCurrent(snapshot)) rejected++ }
    }
    private fun server(status: String = "200 OK", headers: String = "", beforeResponse: (String) -> Unit = {}, block: (String) -> Unit) {
        val socket = ServerSocket(0)
        socket.soTimeout = 5000
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try { socket.accept().use { client ->
                client.soTimeout = 5000
                val reader = client.getInputStream().bufferedReader()
                val request = buildString { while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; appendLine(line) } }
                beforeResponse(request)
                client.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: 2\r\nConnection: close\r\n$headers\r\n{}").toByteArray())
            } } catch (e: Throwable) { failure.set(e) }
        }
        try { block("http://127.0.0.1:${socket.localPort}") }
        finally { worker.join(6000); socket.close() }
        failure.get()?.let { throw AssertionError("Test server failed", it) }
    }
    @Test fun bearerHeaderIsSent() {
        val seen = AtomicReference<String>()
        server(beforeResponse = { seen.set(it) }) { url -> runBlocking {
            assertEquals("{}", HttpTransport(url, Credentials()).request("/v1/me"))
        } }
        assertTrue(seen.get().contains("Authorization: Bearer token-a", ignoreCase = true))
    }
    @Test fun rejectedCurrentSessionIsInvalidated() {
        val credentials = Credentials()
        server("401 Unauthorized") { url -> runBlocking {
            try { HttpTransport(url, credentials).request("/v1/me"); fail("401 was accepted") }
            catch (e: ApiException) { assertEquals(401, e.statusCode) }
        } }
        assertEquals(1, credentials.rejected)
    }
    @Test fun lateRejectionCannotInvalidateAnotherAccount() {
        val credentials = Credentials()
        server("401 Unauthorized", beforeResponse = { credentials.generation++ }) { url -> runBlocking {
            try { HttpTransport(url, credentials).request("/v1/me"); fail("Stale response delivered") }
            catch (_: CancellationException) { }
        } }
        assertEquals(0, credentials.rejected)
    }
    @Test fun bearerRequestsDoNotFollowRedirects() {
        server("307 Temporary Redirect", "Location: http://127.0.0.1:1/another-host\r\n") { url -> runBlocking {
            try { HttpTransport(url, Credentials()).request("/v1/me"); fail("Redirect accepted") }
            catch (e: ApiException) { assertEquals(307, e.statusCode) }
        } }
    }
    @Test fun firstNameValidatedBeforeSending() = runBlocking {
        val names = mutableListOf<String>()
        val repo = object : AccountRepository {
            override suspend fun saveLocation(area: com.circle.app.domain.model.Area, radiusKm: Int): Account = error("Not used")
            override suspend fun getAccount() = Account("a", "Member", false, "password")
            override suspend fun saveFirstName(name: String): Account { names.add(name); return Account("a", name, true, "password") }
            override suspend fun savePreferences(draft: com.circle.app.domain.model.OnboardingDraft): Account = error("Not used")
            override fun observePreferences(accountId: String) = kotlinx.coroutines.flow.flowOf<com.circle.app.domain.model.UserPreferences?>(null)
        }
        val save = SaveFirstName(repo)
        for (name in listOf("", "123", "---", "a\nb")) {
            try { save(name); fail("Invalid name sent") } catch (_: CircleException) { }
        }
        assertTrue(names.isEmpty())
        assertEquals("Ankit", save("  Ankit  ").firstName)
        assertEquals(listOf("Ankit"), names)
    }
}
