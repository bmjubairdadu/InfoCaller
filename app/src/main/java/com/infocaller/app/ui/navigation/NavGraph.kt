package com.infocaller.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.infocaller.app.ui.screens.*
import com.infocaller.app.ui.viewmodel.AuthViewModel
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.permissions.PermissionManager
import androidx.compose.ui.platform.LocalContext

@Composable
fun NavGraph(
    navController: NavHostController,
    viewModel: CallerViewModel,
    authViewModel: AuthViewModel,
    onMakeCall: (String) -> Unit
) {
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = "splash",
        enterTransition = { fadeIn(animationSpec = tween(200)) },
        exitTransition = { fadeOut(animationSpec = tween(200)) },
        popEnterTransition = { fadeIn(animationSpec = tween(200)) },
        popExitTransition = { fadeOut(animationSpec = tween(200)) }
    ) {
        composable("splash") {
            SplashScreen(onSplashFinished = {
                val nextDest = if (PermissionManager.isDefaultDialer(context) && 
                                 PermissionManager.hasPermissions(context, PermissionManager.CORE_PERMISSIONS)) {
                    "main"
                } else {
                    "onboarding"
                }
                navController.navigate(nextDest) {
                    popUpTo("splash") { inclusive = true }
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
                onNavigateToDetails = {
                    navController.navigate("details")
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("details") {
            DetailsScreen(
                viewModel = viewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("login") {
            LoginScreen(
                viewModel = authViewModel,
                onNavigateToRegister = { navController.navigate("register") },
                onLoginSuccess = { navController.popBackStack("main", inclusive = false) }
            )
        }
        composable("register") {
            RegisterScreen(
                viewModel = authViewModel,
                onNavigateToLogin = { navController.navigate("login") },
                onRegisterSuccess = { navController.popBackStack("main", inclusive = false) }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
                onNavigateToWhatsAppLookup = { navController.navigate("whatsapp_lookup") },
                onNavigateToDeveloperTools = { navController.navigate("developer_tools") }
            )
        }
        composable("whatsapp_lookup") {
            WhatsAppLookupScreen()
        }
        composable("developer_tools") {
            DeveloperToolsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
