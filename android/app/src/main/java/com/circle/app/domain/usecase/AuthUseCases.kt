package com.circle.app.domain.usecase

import com.circle.app.domain.error.AuthException
import com.circle.app.domain.error.AuthFailure
import com.circle.app.domain.repository.AuthRepository

private fun emailAddress(raw: String): String {
    val email = raw.trim()
    if (email.length > 254 || !Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email))
        throw AuthException(AuthFailure.INVALID_EMAIL)
    return email
}
class SignUp(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String, confirmation: String) {
        val normalizedEmail = emailAddress(email)
        if (password.length !in 8..4096 || password.isBlank()) throw AuthException(AuthFailure.WEAK_PASSWORD)
        if (password != confirmation) throw AuthException(AuthFailure.PASSWORD_MISMATCH)
        // Passwords are never trimmed, lowercased or persisted by the app.
        repository.signUp(normalizedEmail, password)
    }
}
class SignIn(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String) {
        val normalizedEmail = emailAddress(email)
        // Existing accounts may have a different password policy.
        if (password.isEmpty()) throw AuthException(AuthFailure.EMPTY_PASSWORD)
        repository.signIn(normalizedEmail, password)
    }
}
class ResetPassword(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String) = repository.resetPassword(emailAddress(email))
}
class SignInWithGoogle(private val repository: AuthRepository) {
    suspend operator fun invoke(idToken: String) {
        if (idToken.isBlank()) throw AuthException(AuthFailure.INVALID_CREDENTIALS)
        repository.signInWithGoogle(idToken)
    }
}
