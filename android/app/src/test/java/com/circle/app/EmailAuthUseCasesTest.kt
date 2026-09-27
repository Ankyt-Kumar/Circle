package com.circle.app

import com.circle.app.domain.error.AuthException
import com.circle.app.domain.error.AuthFailure
import com.circle.app.domain.model.AuthSession
import com.circle.app.domain.repository.AuthRepository
import com.circle.app.domain.usecase.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmailAuthUseCasesTest {
    private class Repository : AuthRepository {
        override val session = MutableStateFlow(AuthSession())
        val calls = mutableListOf<List<String>>()
        override suspend fun signUp(email: String, password: String) { calls += listOf("signup", email, password) }
        override suspend fun signIn(email: String, password: String) { calls += listOf("login", email, password) }
        override suspend fun resetPassword(email: String) { calls += listOf("reset", email) }
        override suspend fun signInWithGoogle(idToken: String) { calls += listOf("google", idToken) }
        override fun signOut() { session.value = AuthSession() }
    }
    private suspend fun rejects(reason: AuthFailure, block: suspend () -> Unit) {
        try { block(); fail("Invalid input reached the repository") }
        catch (e: AuthException) { assertEquals(reason, e.reason) }
    }
    @Test fun normalizesEmailButPreservesPasswordExactly() = runBlocking {
        val r = Repository()
        SignUp(r)("  Member@example.test  ", "  long phrase  ", "  long phrase  ")
        assertEquals(listOf("signup", "Member@example.test", "  long phrase  "), r.calls.single())
    }
    @Test fun rejectsInvalidEmailBeforeNetwork() = runBlocking {
        val r = Repository()
        for (email in listOf("", "missing-at", "a@", "a b@example.test", "a@@example.test")) {
            rejects(AuthFailure.INVALID_EMAIL) { SignUp(r)(email, "long-passphrase", "long-passphrase") }
            rejects(AuthFailure.INVALID_EMAIL) { SignIn(r)(email, "long-passphrase") }
            rejects(AuthFailure.INVALID_EMAIL) { ResetPassword(r)(email) }
        }
        assertTrue(r.calls.isEmpty())
    }
    @Test fun rejectsWeakOrMismatchedSignupPasswords() = runBlocking {
        val r = Repository()
        for (password in listOf("short", "        ", "a".repeat(4097)))
            rejects(AuthFailure.WEAK_PASSWORD) { SignUp(r)("a@example.test", password, password) }
        rejects(AuthFailure.PASSWORD_MISMATCH) { SignUp(r)("a@example.test", "long-password", "long-password ") }
        assertTrue(r.calls.isEmpty())
    }
    @Test fun loginDoesNotReapplySignupPolicyToExistingAccount() = runBlocking {
        val r = Repository()
        SignIn(r)(" a@example.test ", "oldpwd")
        assertEquals(listOf("login", "a@example.test", "oldpwd"), r.calls.single())
        rejects(AuthFailure.EMPTY_PASSWORD) { SignIn(r)("a@example.test", "") }
    }
    @Test fun resetUsesOnlyEmailAndGoogleUsesOnlyToken() = runBlocking {
        val r = Repository()
        ResetPassword(r)(" a@example.test ")
        SignInWithGoogle(r)("test-id-token")
        assertEquals(listOf(listOf("reset", "a@example.test"), listOf("google", "test-id-token")), r.calls)
        rejects(AuthFailure.INVALID_CREDENTIALS) { SignInWithGoogle(r)(" ") }
    }
}
