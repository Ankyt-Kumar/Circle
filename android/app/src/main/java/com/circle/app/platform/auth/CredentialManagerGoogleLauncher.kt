package com.circle.app.platform.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.ClearCredentialException
import com.circle.app.domain.error.AuthException
import com.circle.app.domain.error.AuthFailure
import com.circle.app.presentation.auth.GoogleSignInLauncher
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CredentialManagerGoogleLauncher(context: Context, private val webClientId: String) : GoogleSignInLauncher {
    private val manager = CredentialManager.create(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clearing: Job? = null
    override val available = webClientId.isNotBlank()

    fun clearSession() {
        // Application-scoped cleanup survives removal of the signed-in navigation tree.
        clearing = scope.launch {
            try { manager.clearCredentialState(ClearCredentialStateRequest()) }
            catch (_: ClearCredentialException) { /* Firebase is already signed out. */ }
        }
    }
    override suspend fun launch(activity: Activity): String? {
        if (!available) throw AuthException(AuthFailure.GOOGLE_UNAVAILABLE)
        clearing?.join()
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            // The activity is never retained by the launcher or the ViewModel.
            val credential = manager.getCredential(activity, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                throw AuthException(AuthFailure.GOOGLE_UNAVAILABLE)
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: GetCredentialCancellationException) { null
        } catch (_: GetCredentialException) { throw AuthException(AuthFailure.GOOGLE_UNAVAILABLE)
        } catch (_: GoogleIdTokenParsingException) { throw AuthException(AuthFailure.GOOGLE_UNAVAILABLE) }
    }
}
