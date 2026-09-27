package com.circle.app.platform.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import kotlinx.coroutines.*

@Composable
fun rememberEnablePush(onToken: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val enable: () -> Unit = {
        scope.launch {
            try {
                onToken(PushSession.token())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onError(
                    "Couldn’t register phone notifications. Check your Firebase setup and connection."
                )
            }
        }
        Unit
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) enable()
            else onError("Notifications are disabled. Updates still appear in your activity inbox.")
        }
    return {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else enable()
    }
}
