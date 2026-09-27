package com.circle.app.presentation.auth

import android.app.Activity

// Activity-bound UI is implemented in platform; the ViewModel receives only a token.
interface GoogleSignInLauncher {
    val available: Boolean
    suspend fun launch(activity: Activity): String?
}
