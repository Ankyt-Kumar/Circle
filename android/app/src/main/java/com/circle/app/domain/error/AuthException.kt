package com.circle.app.domain.error

enum class AuthFailure {
    INVALID_EMAIL, WEAK_PASSWORD, PASSWORD_MISMATCH, EMPTY_PASSWORD,
    INVALID_CREDENTIALS, ACCOUNT_CONFLICT, THROTTLED, NETWORK, UNAVAILABLE, GOOGLE_UNAVAILABLE
}
class AuthException(val reason: AuthFailure) : Exception(reason.name)
