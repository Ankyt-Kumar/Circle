package com.circle.app.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.circle.app.di.AppContainer
import com.circle.app.di.ViewModelFactories
import com.circle.app.presentation.detail.CircleDetailRoute
import com.circle.app.presentation.detail.CircleDetailViewModel
import com.circle.app.presentation.discover.DiscoverRoute
import com.circle.app.presentation.discover.DiscoverViewModel
import com.circle.app.presentation.mycircles.MyCirclesRoute
import com.circle.app.presentation.mycircles.MyCirclesViewModel

@Composable
fun CircleApp(
    container: AppContainer,
    account: com.circle.app.domain.model.Account? = null,
    onSignOut: () -> Unit = {},
    onAccountUpdated: (com.circle.app.domain.model.Account) -> Unit = {},
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(account?.id) {
        if (account != null)
            com.circle.app.platform.notifications.PushSession.attach(context, account.id)
        onDispose {
            if (account != null) com.circle.app.platform.notifications.PushSession.clear(context)
        }
    }
    LaunchedEffect(account?.id) {
        if (account != null)
            try {
                if (container.memberActions.preferences().pushEnabled)
                    container.memberActions.registerDevice(
                        com.circle.app.platform.notifications.PushSession.token()
                    )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
    }

    val factories = remember(container) { ViewModelFactories(container) }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "discover"
    val isDetail =
        route == "circle/{circleId}" ||
            route == "create" ||
            route == "preferences" ||
            route.startsWith("safety/") ||
            route == "blocked-people" ||
            route == "activity" ||
            route.startsWith("chat/") ||
            route.startsWith("edit/")
    val selectTab: (String) -> Unit = { destination ->
        navController.navigate(destination) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val openCircle: (String) -> Unit = { id ->
        navController.navigate("circle/${Uri.encode(id)}") { launchSingleTop = true }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!isDetail)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    NavigationBarItem(
                        selected = route == "discover",
                        onClick = { selectTab("discover") },
                        icon = { Text("◎", fontSize = 25.sp) },
                        label = { Text("Discover") },
                    )
                    NavigationBarItem(
                        selected = route == "my-circles",
                        onClick = { selectTab("my-circles") },
                        icon = { Text("○", fontSize = 25.sp) },
                        label = { Text("My circles") },
                    )
                    if (account == null)
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                navController.navigate("blocked-people") { launchSingleTop = true }
                            },
                            icon = { Text("⊘", fontSize = 25.sp) },
                            label = { Text("Blocked people") },
                        )
                    if (account != null)
                        NavigationBarItem(
                            selected = route == "profile",
                            onClick = { selectTab("profile") },
                            icon = {
                                Icon(
                                    imageVector = if (route == "profile") Icons.Filled.Person else Icons.Outlined.Person,
                                    contentDescription = "Profile"
                                )
                            },
                            label = { Text("Profile") },
                        )
                }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController, startDestination = "discover") {
                composable("profile") {
                    account?.let {
                        com.circle.app.presentation.profile.ProfileScreen(
                            it,
                            container.isAuthEmulator,
                            onSignOut,
                            onEditPreferences = {
                                navController.navigate("preferences") { launchSingleTop = true }
                            },
                            onBlockedPeople = {
                                navController.navigate("blocked-people") { launchSingleTop = true }
                            },
                            onActivity = {
                                navController.navigate("activity") { launchSingleTop = true }
                            },
                        )
                    }
                }
                composable("activity") { backStackEntry ->
                    val vm: com.circle.app.presentation.member.MemberViewModel =
                        viewModel(viewModelStoreOwner = backStackEntry, factory = factories.member)
                    val enablePush =
                        com.circle.app.platform.notifications.rememberEnablePush(
                            onToken = { token ->
                                vm.save(vm.state.value.preferences.copy(pushEnabled = true), token)
                            },
                            onError = vm::error,
                        )
                    com.circle.app.presentation.member.MemberRoute(
                        vm,
                        { navController.popBackStack() },
                        openCircle,
                        onSignOut,
                        enablePush,
                    )
                }
                composable(
                    "chat/{circleId}",
                    arguments = listOf(navArgument("circleId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val vm: com.circle.app.presentation.chat.ChatViewModel =
                        viewModel(viewModelStoreOwner = backStackEntry, factory = factories.chat)
                    com.circle.app.presentation.chat.ChatRoute(
                        vm,
                        account?.id ?: "00000000-0000-0000-0000-000000000001",
                        onBlockMember = { userId ->
                            val id = checkNotNull(backStackEntry.arguments?.getString("circleId"))
                            navController.navigate("safety/${Uri.encode(id)}/${Uri.encode(userId)}")
                        },
                    ) {
                        navController.popBackStack()
                    }
                }
                composable(
                    "edit/{circleId}",
                    arguments = listOf(navArgument("circleId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val vm: com.circle.app.presentation.create.CreateCircleViewModel =
                        viewModel(viewModelStoreOwner = backStackEntry, factory = factories.edit)
                    com.circle.app.presentation.create.CreateCircleRoute(
                        vm,
                        { navController.popBackStack() },
                        { navController.popBackStack() },
                    )
                }
                composable("preferences") { backStackEntry ->
                    account?.let { current ->
                        val vm: com.circle.app.presentation.onboarding.OnboardingViewModel =
                            viewModel(
                                viewModelStoreOwner = backStackEntry,
                                factory = factories.onboarding(current),
                            )
                        com.circle.app.presentation.onboarding.OnboardingRoute(
                            vm,
                            true,
                            onSaved = { updated ->
                                onAccountUpdated(updated)
                                navController.popBackStack()
                            },
                            onCancel = { navController.popBackStack() },
                        )
                    }
                }
                composable("discover") { backStackEntry ->
                    val vm: DiscoverViewModel =
                        viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = factories.discover(account),
                        )
                    DiscoverRoute(
                        vm,
                        container.areas,
                        openCircle,
                        onCreate = { navController.navigate("create") { launchSingleTop = true } },
                        preferences = account?.preferences,
                        onAccountUpdated = onAccountUpdated,
                    )
                }
                composable("create") { backStackEntry ->
                    val vm: com.circle.app.presentation.create.CreateCircleViewModel =
                        viewModel(viewModelStoreOwner = backStackEntry, factory = factories.create)
                    com.circle.app.presentation.create.CreateCircleRoute(
                        vm,
                        onBack = { navController.popBackStack() },
                        onCreated = { id ->
                            navController.navigate("circle/${Uri.encode(id)}") {
                                popUpTo("create") { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable("my-circles") { backStackEntry ->
                    val vm: MyCirclesViewModel =
                        viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = factories.myCircles(account),
                        )
                    MyCirclesRoute(
                        vm,
                        onExplore = { selectTab("discover") },
                        onCircleSelected = openCircle,
                        preferences = account?.preferences,
                    )
                }
                composable("blocked-people") { backStackEntry ->
                    val vm: com.circle.app.presentation.safety.BlockedUsersViewModel =
                        viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = factories.blockedUsers,
                        )
                    com.circle.app.presentation.safety.BlockedUsersRoute(vm) {
                        navController.popBackStack()
                    }
                }
                composable(
                    "safety/{circleId}/{userId}",
                    arguments =
                        listOf(
                            navArgument("circleId") { type = NavType.StringType },
                            navArgument("userId") { type = NavType.StringType },
                        ),
                ) { backStackEntry ->
                    val vm: com.circle.app.presentation.safety.SafetyViewModel =
                        viewModel(viewModelStoreOwner = backStackEntry, factory = factories.safety)
                    com.circle.app.presentation.safety.SafetyRoute(
                        vm,
                        onBack = { navController.popBackStack() },
                        onBlocked = {
                            navController.navigate("discover") {
                                popUpTo("discover") { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(
                    "circle/{circleId}",
                    arguments = listOf(navArgument("circleId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val vm: CircleDetailViewModel =
                        viewModel(viewModelStoreOwner = backStackEntry, factory = factories.detail)
                    CircleDetailRoute(
                        vm,
                        onBack = { navController.popBackStack() },
                        viewerId = account?.id ?: "00000000-0000-0000-0000-000000000001",
                        onChat = { id -> navController.navigate("chat/${Uri.encode(id)}") },
                        onEdit = { id -> navController.navigate("edit/${Uri.encode(id)}") },
                        onBlockMember = { circleId, userId ->
                            navController.navigate(
                                "safety/${Uri.encode(circleId)}/${Uri.encode(userId.ifBlank { "-" })}"
                            ) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }
    }
}
