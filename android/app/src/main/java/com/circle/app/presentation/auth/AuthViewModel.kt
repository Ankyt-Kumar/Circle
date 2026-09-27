package com.circle.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.error.AuthException
import com.circle.app.domain.error.AuthFailure
import com.circle.app.domain.usecase.ResetPassword
import com.circle.app.domain.usecase.SignIn
import com.circle.app.domain.usecase.SignInWithGoogle
import com.circle.app.domain.usecase.SignUp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, SIGNUP, RESET }
data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val confirmation: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)
class AuthViewModel(
    private val signIn: SignIn,
    private val signUp: SignUp,
    private val resetPassword: ResetPassword,
    private val signInWithGoogle: SignInWithGoogle,
) : ViewModel() {
    // In-memory only: passwords and ID tokens never enter SavedStateHandle or disk.
    private val mutable = MutableStateFlow(AuthUiState())
    val state = mutable.asStateFlow()
    fun email(value: String) { if (!state.value.busy) mutable.value = state.value.copy(email = value, error = null, notice = null) }
    fun password(value: String) { if (!state.value.busy) mutable.value = state.value.copy(password = value, error = null) }
    fun confirmation(value: String) { if (!state.value.busy) mutable.value = state.value.copy(confirmation = value, error = null) }
    fun mode(value: AuthMode) { if (!state.value.busy) mutable.value = AuthUiState(mode = value, email = state.value.email) }
    fun submit() {
        val form = state.value
        if (form.busy) return
        mutable.value = form.copy(busy = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                when (form.mode) {
                    AuthMode.LOGIN -> signIn(form.email, form.password)
                    AuthMode.SIGNUP -> signUp(form.email, form.password, form.confirmation)
                    AuthMode.RESET -> resetPassword(form.email)
                }
                mutable.value = state.value.copy(password = "", confirmation = "", busy = false,
                    notice = if (form.mode == AuthMode.RESET) "If this email has a password account, you’ll receive a reset link. You can also try Google sign-in." else null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { failed(e) }
        }
    }
    fun beginGoogle(): Boolean {
        if (state.value.busy) return false
        mutable.value = state.value.copy(busy = true, error = null, notice = null, password = "", confirmation = "")
        return true
    }
    fun finishGoogle(idToken: String?) {
        if (idToken == null) { mutable.value = state.value.copy(busy = false); return }
        viewModelScope.launch {
            try { signInWithGoogle(idToken); mutable.value = state.value.copy(busy = false) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
        }
    }
    fun failed(error: Exception) {
        mutable.value = state.value.copy(busy = false, error = error.authMessage())
    }
    override fun onCleared() { mutable.value = AuthUiState(); super.onCleared() }
}
internal fun Exception.authMessage(): String = when ((this as? AuthException)?.reason) {
    AuthFailure.INVALID_EMAIL -> "Enter a valid email address."
    AuthFailure.WEAK_PASSWORD -> "Choose a stronger password with at least 8 characters, up to 4096."
    AuthFailure.PASSWORD_MISMATCH -> "The passwords don’t match."
    AuthFailure.EMPTY_PASSWORD -> "Enter your password."
    AuthFailure.INVALID_CREDENTIALS -> "Couldn’t sign in. Check your email and password, or try Google."
    AuthFailure.ACCOUNT_CONFLICT -> "Couldn’t create this account. Try signing in with the method you used before, or reset your password."
    AuthFailure.THROTTLED -> "Too many attempts. Please wait a while before trying again."
    AuthFailure.NETWORK -> "Couldn’t connect. Check your internet connection and retry."
    AuthFailure.GOOGLE_UNAVAILABLE -> "Google sign-in couldn’t open. Try again, or use email and password."
    else -> "Sign-in is unavailable right now. Please try again shortly."
}
