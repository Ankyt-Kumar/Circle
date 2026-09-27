package com.circle.app.domain.error

enum class FailureReason {
    INELIGIBLE,
    INVALID_INPUT,
    USER_BLOCKED,
    RATE_LIMIT,
    SIGN_IN_REQUIRED,
    ACCOUNT_BLOCKED,
    ONBOARDING_REQUIRED,
    NETWORK,
    SERVICE_UNAVAILABLE,
    NOT_FOUND,
    CONFLICT,
    INVALID_RESPONSE,
    UNKNOWN,
}

// Domain callers never need to know about HTTP, JSON, or Android exceptions.
class CircleException(val reason: FailureReason, cause: Throwable? = null) :
    Exception(reason.name, cause)
