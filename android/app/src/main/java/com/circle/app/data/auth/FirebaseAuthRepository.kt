package com.circle.app.data.auth

import com.circle.app.data.remote.ApiException
import com.circle.app.data.remote.CredentialSnapshot
import com.circle.app.data.remote.RequestCredentials
import com.circle.app.domain.error.AuthException
import com.circle.app.domain.error.AuthFailure
import com.circle.app.domain.model.AuthSession
import com.circle.app.domain.repository.AuthRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// Application-scoped. The SDK owns token persistence and refreshing.
class FirebaseAuthRepository(
    private val auth: FirebaseAuth,
    private val clearCredentialSession: () -> Unit = {},
    private val clearLocalData: () -> Unit = {},
) : AuthRepository, RequestCredentials {
    private val mutableSession = MutableStateFlow(AuthSession(auth.currentUser?.uid))
    override val session = mutableSession.asStateFlow()
    private val listener =
        FirebaseAuth.AuthStateListener { firebase ->
            val previous = mutableSession.value
            val uid = firebase.currentUser?.uid
            if (previous.userId != uid) {
                clearLocalData()
                mutableSession.value = AuthSession(uid, previous.generation + 1)
            }
        }

    init {
        auth.addAuthStateListener(listener)
    }

    override suspend fun signUp(email: String, password: String) = mapped {
        auth.createUserWithEmailAndPassword(email, password).await()
    }

    override suspend fun signIn(email: String, password: String) = mapped {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    override suspend fun signInWithGoogle(idToken: String) = mapped {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun resetPassword(email: String) = mapped {
        try {
            auth.sendPasswordResetEmail(email).await()
        } catch (e: FirebaseAuthInvalidUserException) {
            // Same response for an unknown email, including projects without enumeration
            // protection.
            if (e.errorCode != "ERROR_USER_NOT_FOUND") throw e
        }
    }

    private suspend fun mapped(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AuthException(
                when (e) {
                    is FirebaseNetworkException -> AuthFailure.NETWORK
                    is FirebaseTooManyRequestsException -> AuthFailure.THROTTLED
                    is FirebaseAuthWeakPasswordException -> AuthFailure.WEAK_PASSWORD
                    is FirebaseAuthUserCollisionException -> AuthFailure.ACCOUNT_CONFLICT
                    is FirebaseAuthInvalidCredentialsException,
                    is FirebaseAuthInvalidUserException -> AuthFailure.INVALID_CREDENTIALS
                    is FirebaseAuthException ->
                        if (e.errorCode == "ERROR_OPERATION_NOT_ALLOWED") AuthFailure.UNAVAILABLE
                        else AuthFailure.INVALID_CREDENTIALS
                    else -> AuthFailure.UNAVAILABLE
                }
            )
        }
    }

    override fun signOut() = endSession(null)

    private fun endSession(message: String?) {
        val old = mutableSession.value
        clearLocalData()
        mutableSession.value = AuthSession(null, old.generation + 1, message)
        auth.signOut()
        clearCredentialSession()
    }

    override suspend fun snapshot(): CredentialSnapshot {
        val started = mutableSession.value
        val user = auth.currentUser
        if (user == null || user.uid != started.userId)
            throw CancellationException("Account changed")
        val token =
            try {
                user.getIdToken(false).await().token
            } catch (e: FirebaseAuthInvalidUserException) {
                rejected(CredentialSnapshot(generation = started.generation), 401)
                throw ApiException(401)
            } catch (e: FirebaseNetworkException) {
                throw IOException("Sign-in connection unavailable", e)
            }
        val snapshot = CredentialSnapshot(token, started.generation)
        if (!isCurrent(snapshot)) throw CancellationException("Account changed")
        if (token == null) {
            rejected(snapshot, 401)
            throw ApiException(401)
        }
        return snapshot
    }

    override fun isCurrent(snapshot: CredentialSnapshot) =
        snapshot.generation == mutableSession.value.generation

    override suspend fun rejected(snapshot: CredentialSnapshot, status: Int) =
        withContext(Dispatchers.Main.immediate) {
            if (isCurrent(snapshot))
                endSession(
                    if (status == 403) "Your account is unavailable. Contact Circle support."
                    else "Your session expired. Please sign in again."
                )
        }
}
