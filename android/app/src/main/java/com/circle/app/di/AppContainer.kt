package com.circle.app.di

import android.annotation.SuppressLint
import android.content.Context
import com.circle.app.BuildConfig
import com.circle.app.data.auth.FirebaseAuthRepository
import com.circle.app.data.remote.*
import com.circle.app.data.repository.DefaultAccountRepository
import com.circle.app.data.repository.DefaultCircleRepository
import com.circle.app.domain.repository.CircleRepository
import com.circle.app.domain.usecase.*
import com.circle.app.platform.auth.CredentialManagerGoogleLauncher
import com.google.firebase.auth.FirebaseAuth

class AppContainer(context: Context) {
    val isDemo = BuildConfig.AUTH_MODE == "demo"
    val isAuthEmulator = BuildConfig.AUTH_MODE == "emulator"
    private val firebaseResult: Result<FirebaseAuth>? =
        if (isDemo) null else runCatching { FirebaseSetup.auth(context) }
    val configurationError: String? =
        firebaseResult?.exceptionOrNull()?.let {
            "Sign-in is not configured. Follow docs/AUTH_SETUP.md and rebuild the app."
        }
    val googleLauncher =
        CredentialManagerGoogleLauncher(
            context,
            if (BuildConfig.AUTH_MODE == "firebase") googleWebClientId(context) else "",
        )
    private val preferences = com.circle.app.data.local.SessionPreferencesCache()

    init {
        // Retire the old per-account preferences file; the new cache is excluded from backup.
        java.io.File(context.filesDir, "datastore/circle_preferences.preferences_pb").delete()
    }

    val auth: FirebaseAuthRepository? =
        firebaseResult?.getOrNull()?.let { firebase ->
            FirebaseAuthRepository(firebase, googleLauncher::clearSession) {
                offline.clear()
                preferences.clear()
            }
        }
    val offline: com.circle.app.data.local.SessionDiskCache by lazy {
        com.circle.app.data.local.SessionDiskCache(
            java.io.File(
                context.noBackupFilesDir,
                "circle-offline-" + BuildConfig.API_BASE_URL.hashCode(),
            ),
            {
                if (isDemo) com.circle.app.domain.model.AuthSession("demo")
                else auth?.session?.value ?: com.circle.app.domain.model.AuthSession()
            },
        )
    }

    fun drafts() = com.circle.app.data.repository.DiskDraftRepository(offline)

    val areas = emptyList<com.circle.app.domain.model.Area>()
    val venues = emptyList<com.circle.app.domain.model.PublicVenue>()
    val appearance = com.circle.app.data.local.DataStoreAppearanceRepository(context.applicationContext)
    val updateDiscoveryPreferences by lazy { UpdateDiscoveryPreferences(accounts) }
    private val transport by lazy {
        HttpTransport(BuildConfig.API_BASE_URL, if (isDemo) DemoCredentials else checkNotNull(auth))
    }
    private val repository: CircleRepository by lazy {
        DefaultCircleRepository(HttpCircleApi(transport), offline)
    }
    private val accounts by lazy { DefaultAccountRepository(transport, preferences, offline) }
    private val community by lazy {
        com.circle.app.data.repository.DefaultCommunityRepository(transport, offline)
    }
    val chatActions by lazy { ChatActions(community) }
    val eventActions by lazy { EventActions(community) }
    val memberActions by lazy { MemberActions(community) }
    val getAccount by lazy { GetAccount(accounts) }
    val saveFirstName by lazy { SaveFirstName(accounts) }
    val saveOnboarding by lazy { SaveOnboarding(accounts) }
    val observePreferences by lazy { ObservePreferences(accounts) }
    val signIn by lazy { SignIn(checkNotNull(auth)) }
    val signUp by lazy { SignUp(checkNotNull(auth)) }
    val resetPassword by lazy { ResetPassword(checkNotNull(auth)) }
    val signInWithGoogle by lazy { SignInWithGoogle(checkNotNull(auth)) }
    private val safety by lazy {
        com.circle.app.data.repository.DefaultSafetyRepository(transport, offline)
    }
    val blockUser by lazy { BlockUser(safety) }
    val unblockUser by lazy { UnblockUser(safety) }
    val getBlockedUsers by lazy { GetBlockedUsers(safety) }
    val createCircle by lazy { CreateCircle(repository) }
    val getNearbyCircles by lazy { GetNearbyCircles(repository) }
    val getCircle by lazy { GetCircle(repository) }
    val getJoinedCircles by lazy { GetJoinedCircles(repository) }
    val updateMembership by lazy { UpdateCircleMembership(repository) }
}

// google-services generates this resource only in configured Firebase builds.
// Looking it up here lets demo/emulator builds compile without a Google project.
@SuppressLint("DiscouragedApi")
private fun googleWebClientId(context: Context): String {
    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    return if (id == 0) "" else context.getString(id)
}
