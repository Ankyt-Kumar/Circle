package com.circle.app.domain.repository

import com.circle.app.domain.model.AuthSession
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val session: StateFlow<AuthSession>
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun resetPassword(email: String)
    suspend fun signInWithGoogle(idToken: String)
    fun signOut()
}
