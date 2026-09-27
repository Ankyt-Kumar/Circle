package com.circle.app.platform.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.circle.app.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.*

class CircleMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("circle_push", Context.MODE_PRIVATE)
            .edit()
            .putString("new_token", token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val p = getSharedPreferences("circle_push", Context.MODE_PRIVATE)
        val owner = p.getString("account", null) ?: return
        if (
            message.data["account_id"] != owner ||
                FirebaseAuth.getInstance().currentUser?.uid != p.getString("firebase_uid", null)
        )
            return
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val id = message.data["notification_id"] ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "circle_events",
                "Circle events",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending =
            PendingIntent.getActivity(
                this,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        // The same stable ID replaces retries instead of creating duplicate notices.
        manager.notify(
            id.hashCode(),
            Notification.Builder(this, "circle_events")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("A Circle update is waiting")
                .setContentText("Open your activity inbox for details.")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }
}
