package com.circle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.circle.app.domain.model.ThemeMode
import com.circle.app.navigation.CircleRoot
import com.circle.app.presentation.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CircleApplication).container
        setContent {
            val vm: AppearanceViewModel = viewModel(factory = remember { viewModelFactory {
                initializer { AppearanceViewModel(container.appearance) }
            } })
            val appearance by vm.appearance.collectAsStateWithLifecycle()
            val error by vm.error.collectAsStateWithLifecycle()
            val dark = when (appearance.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(LocalAppearanceControls provides AppearanceControls(appearance, error, vm::save)) {
                CircleTheme(darkTheme = dark) { CircleRoot(container) }
            }
        }
    }
}
