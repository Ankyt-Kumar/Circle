package com.circle.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.circle.app.di.AppContainer
import com.circle.app.di.ViewModelFactories
import com.circle.app.presentation.auth.*

@Composable
fun CircleRoot(container: AppContainer) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (container.isDemo) {
            Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect your Circle account", style = MaterialTheme.typography.headlineMedium)
                Text("This version uses real member profiles and saved locations. Set circleAuthMode=firebase in gradle.properties, add your Firebase configuration, then sync and run. Follow docs/FINAL_SETUP.md.")
            }
        } else if (container.configurationError != null) {
            Text(container.configurationError, Modifier.safeDrawingPadding().padding(24.dp))
        } else {
            val factories = remember(container) { ViewModelFactories(container) }
            val vm: SessionViewModel = viewModel(factory = factories.session)
            val state by vm.state.collectAsStateWithLifecycle()
            key(state.session.userId, state.session.generation) {
                SessionScope {
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        state.session.userId == null -> {
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = "login") {
                                composable("login") { entry ->
                                    val login: AuthViewModel = viewModel(viewModelStoreOwner = entry, factory = factories.auth)
                                    AuthRoute(login, container.googleLauncher, container.isAuthEmulator, state.session.message)
                                }
                            }
                        }
                        state.account == null -> Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(state.error ?: "Couldn’t load your account.")
                            Button(vm::retry) { Text("Retry") }
                            TextButton(vm::signOut) { Text("Sign out") }
                        }
                        state.account?.onboardingComplete != true || state.account?.preferences?.valid() != true -> {
                            val account = checkNotNull(state.account)
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = "onboarding") {
                                composable("onboarding") { entry ->
                                    val onboarding: com.circle.app.presentation.onboarding.OnboardingViewModel = viewModel(
                                        viewModelStoreOwner = entry, factory = factories.onboarding(account))
                                    com.circle.app.presentation.onboarding.OnboardingRoute(onboarding, false, vm::accountUpdated, vm::signOut)
                                }
                            }
                        }
                        else -> CircleApp(container, state.account, vm::signOut, vm::accountUpdated)
                    }
                }
            }
        }
    }
}

// Discard each session's navigation and ViewModels on sign-out or account switch.
@Composable
private fun SessionScope(content: @Composable () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
