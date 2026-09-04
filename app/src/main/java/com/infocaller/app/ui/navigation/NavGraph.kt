package com.infocaller.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.infocaller.app.ui.screens.*
import com.infocaller.app.ui.viewmodel.AuthViewModel
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.permissions.PermissionManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize

@Composable
fun NavGraph(
    navController: NavHostController,
    viewModel: CallerViewModel,
    authViewModel: AuthViewModel,
    onMakeCall: (String) -> Unit
) {
    val context = LocalContext.current
    
    val isCoreOk = PermissionManager.isDefaultDialer(context) && 
                  PermissionManager.hasPermissions(context, PermissionManager.CORE_PERMISSIONS)
    val isOverlayOk = PermissionManager.canDrawOverlays(context)
    val isNotificationsOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        PermissionManager.hasPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
    } else true

    val authState by authViewModel.authState.collectAsState()
    val isAuthorized = authState is com.infocaller.app.ui.viewmodel.AuthUiState.Authenticated

    val startDest = "launcher"

    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDest,
            enterTransition = { fadeIn(animationSpec = tween(200)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
            popEnterTransition = { fadeIn(animationSpec = tween(200)) },
            popExitTransition = { fadeOut(animationSpec = tween(200)) }
        ) {
            composable("launcher") {
                InfoCallerLauncherScreen(onLauncherComplete = {
                    val nextDest = when {
                        !isAuthorized -> "login"
                        !isCoreOk || !isOverlayOk || !isNotificationsOk -> "onboarding"
                        else -> "main"
                    }
                    navController.navigate(nextDest) {
                        popUpTo("launcher") { inclusive = true }
                    }
                })
            }
            composable("onboarding") {
                OnboardingScreen(onComplete = {
                    navController.navigate("main") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                })
            }
            composable("main") {
                MainScreen(
                    parentNavController = navController,
                    viewModel = viewModel,
                    onMakeCall = onMakeCall
                )
            }
            composable("search") {
                SearchScreen(
                    viewModel = viewModel,
                    onNavigateToDetails = { number ->
                        viewModel.searchNumber(number)
                        navController.navigate("details/" + android.net.Uri.encode(number))
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(
                route = "details/{number}",
                arguments = listOf(navArgument("number") { type = NavType.StringType; nullable = true })
            ) { backStackEntry ->
                val argNumber = backStackEntry.arguments?.getString("number")?.let {
                    try { android.net.Uri.decode(it) } catch (_: Exception) { it }
                }.orEmpty()
                androidx.compose.runtime.LaunchedEffect(argNumber) {
                    if (argNumber.isNotBlank()) viewModel.searchNumber(argNumber)
                }
                DetailsScreen(
                    viewModel = viewModel,
                    onBack = {
                        navController.popBackStack()
                    },
                    onMakeCall = onMakeCall
                )
            }
            // Legacy no-arg route: kept so older in-app links do not crash.
            composable("details") {
                DetailsScreen(
                    viewModel = viewModel,
                    onBack = {
                        navController.popBackStack()
                    },
                    onMakeCall = onMakeCall
                )
            }
            composable("login") {
                LoginScreen(
                    viewModel = authViewModel,
                    onLoginSuccess = {
                        val nextDest = if (!isCoreOk || !isOverlayOk || !isNotificationsOk) "onboarding" else "main"
                        navController.navigate(nextDest) {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                    onNavigateToPrivacy = { navController.navigate("privacy") },
                    onNavigateToDetails = { number ->
                        viewModel.searchNumber(number)
                        navController.navigate("details/" + android.net.Uri.encode(number))
                    },
                    onNavigateToOwnerProfile = { navController.navigate("owner_profile") }
                )
            }
            composable("owner_profile") {
                OwnerProfileScreen(onBack = { navController.popBackStack() })
            }
            composable("privacy") {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }
        }

        val simSelectionPhone by viewModel.showSimSelection.collectAsState()
        if (simSelectionPhone != null) {
            com.infocaller.app.ui.dialogs.SimSelectionBottomSheet(
                phoneNumber = simSelectionPhone!!,
                onSimSelected = { sim ->
                    com.infocaller.app.util.SimManager.placeCall(context, simSelectionPhone!!, sim.phoneAccountHandle)
                    viewModel.dismissSimSelection()
                },
                onDismiss = { viewModel.dismissSimSelection() }
            )
        }
    }
}
