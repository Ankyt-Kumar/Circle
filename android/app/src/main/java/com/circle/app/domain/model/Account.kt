package com.circle.app.domain.model

data class Account(
    val id: String,
    val firstName: String,
    val profileComplete: Boolean,
    val authProvider: String,
    val email: String = "",
    val emailVerified: Boolean = false,
    val onboardingComplete: Boolean = false,
    val preferences: UserPreferences? = null,
    val offline: Boolean = false,
)

data class AuthSession(
    val userId: String? = null,
    val generation: Long = 0,
    val message: String? = null,
)
