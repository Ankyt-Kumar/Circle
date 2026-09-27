package com.circle.app

import com.circle.app.domain.error.AuthException
import com.circle.app.domain.error.AuthFailure
import com.circle.app.domain.model.AuthSession
import com.circle.app.domain.repository.AuthRepository
import com.circle.app.domain.usecase.*
import com.circle.app.presentation.auth.AuthMode
import com.circle.app.presentation.auth.AuthViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private class Repository : AuthRepository {
        override val session = MutableStateFlow(AuthSession())
        var calls = 0
        var pending: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        private suspend fun request() { calls++; pending?.await(); failure?.let { throw it } }
        override suspend fun signUp(email: String, password: String) = request()
        override suspend fun signIn(email: String, password: String) = request()
        override suspend fun resetPassword(email: String) = request()
        override suspend fun signInWithGoogle(idToken: String) = request()
        override fun signOut() { }
    }
    private fun vm(r: Repository) = AuthViewModel(SignIn(r), SignUp(r), ResetPassword(r), SignInWithGoogle(r))
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun pendingSubmissionBlocksDuplicatesAndClearsSecretsOnSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val r = Repository().apply { pending = CompletableDeferred() }; val vm = vm(r)
        vm.email("member@example.test"); vm.password("private passphrase")
        vm.submit(); runCurrent()
        vm.submit(); vm.mode(AuthMode.SIGNUP); vm.email("another@example.test")
        assertTrue(vm.state.value.busy); assertEquals(AuthMode.LOGIN, vm.state.value.mode)
        assertEquals("member@example.test", vm.state.value.email); assertEquals(1, r.calls)
        r.pending!!.complete(Unit); advanceUntilIdle()
        assertFalse(vm.state.value.busy); assertEquals("", vm.state.value.password)
    }
    @Test fun switchingFormsClearsPasswordsButKeepsEmail() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vm(Repository())
        vm.mode(AuthMode.SIGNUP); vm.email("member@example.test")
        vm.password("private passphrase"); vm.confirmation("private passphrase")
        vm.mode(AuthMode.LOGIN)
        assertEquals("", vm.state.value.password); assertEquals("", vm.state.value.confirmation)
        assertEquals("member@example.test", vm.state.value.email)
    }
    @Test fun googleCancellationAllowsAnotherSignInWithoutCallingFirebase() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val r = Repository(); val vm = vm(r)
        assertTrue(vm.beginGoogle()); assertFalse(vm.beginGoogle())
        vm.finishGoogle(null)
        assertFalse(vm.state.value.busy); assertNull(vm.state.value.error); assertEquals(0, r.calls)
        assertTrue(vm.beginGoogle()); vm.finishGoogle("test-google-token"); advanceUntilIdle()
        assertEquals(1, r.calls); assertFalse(vm.state.value.busy)
    }
    @Test fun failuresRemainRetryableAndDoNotDisplayRawSdkErrors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val r = Repository().apply { failure = IllegalStateException("raw token or sensitive SDK detail") }; val vm = vm(r)
        vm.email("member@example.test"); vm.password("private passphrase")
        vm.submit(); advanceUntilIdle()
        assertFalse(vm.state.value.busy); assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.error!!.contains("raw token"))
        r.failure = null; vm.submit(); advanceUntilIdle()
        assertNull(vm.state.value.error); assertEquals(2, r.calls)
    }
    @Test fun resetShowsGenericConfirmationAndCollisionOffersExistingLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val r = Repository(); val vm = vm(r)
        vm.mode(AuthMode.RESET); vm.email("member@example.test"); vm.submit(); advanceUntilIdle()
        assertTrue(vm.state.value.notice!!.startsWith("If this email"))
        vm.mode(AuthMode.SIGNUP); vm.password("long password"); vm.confirmation("long password")
        r.failure = AuthException(AuthFailure.ACCOUNT_CONFLICT); vm.submit(); advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("method you used before"))
        assertFalse(vm.state.value.busy)
    }
}
