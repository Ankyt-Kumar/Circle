package com.circle.app.di

import android.content.Context
import com.circle.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

object FirebaseSetup {
    fun auth(context: Context): FirebaseAuth {
        if (BuildConfig.AUTH_MODE == "emulator") {
            check(BuildConfig.DEBUG)
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == "circle-emulator" }
                ?: FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                    .setProjectId("demo-circle").setApiKey("fake-api-key")
                    .setApplicationId("1:1234567890:android:0000000000000000").build(), "circle-emulator")
            return FirebaseAuth.getInstance(app).apply { useEmulator(BuildConfig.AUTH_EMULATOR_HOST, 9099) }
        }
        check(BuildConfig.AUTH_MODE == "firebase")
        val app = checkNotNull(FirebaseApp.initializeApp(context)) { "Add the Firebase Android configuration" }
        return FirebaseAuth.getInstance(app)
    }
}
