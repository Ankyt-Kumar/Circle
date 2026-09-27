package com.circle.app.platform.notifications

import android.app.NotificationManager
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object PushSession {
    fun attach(context: Context, accountId: String) {
        val p = context.getSharedPreferences("circle_push", Context.MODE_PRIVATE)
        p.edit()
            .putString("account", accountId)
            .putString("firebase_uid", FirebaseAuth.getInstance().currentUser?.uid)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences("circle_push", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    suspend fun token(): String = FirebaseMessaging.getInstance().token.await()
}
