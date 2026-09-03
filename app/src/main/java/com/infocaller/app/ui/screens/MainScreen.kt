package com.infocaller.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.core.content.edit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.glassy
import com.infocaller.app.ui.viewmodel.CallerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    parentNavController: NavHostController,
    viewModel: CallerViewModel,
    onMakeCall: (String) -> Unit,
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    LaunchedEffect(Unit) {
        val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
        viewModel.loadSimInfos(context)
        
        val sims = com.infocaller.app.util.SimManager.getSimInfos(context)
        app.operatorLogoManager.initialize(sims)

        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val isFirstSyncDone = prefs.getBoolean("is_first_sync_done", false)
        if (!isFirstSyncDone) {
            viewModel.performMasterSync()
            viewModel.triggerThrottledSync(context)
            viewModel.syncWhatsAppPhotos()
            prefs.edit { putBoolean("is_first_sync_done", true) }
        }
    }

    // Continuous Monitoring of Recent Calls
    val recentCalls by viewModel.recentCalls.collectAsState()
    LaunchedEffect(recentCalls) {
        if (recentCalls.isNotEmpty()) {
            val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
            recentCalls.take(10).forEach { entry ->
                // Check if already enriched in background
                app.enrichmentEngine.enqueue(entry.number, priority = com.infocaller.app.data.local.entity.QueuePriority.MEDIUM)
            }
        }
    }

    val tabs = remember {
        listOf(
            BottomNavItem("recents", "Recents", Icons.Default.History),
            BottomNavItem("contacts", "Contacts", Icons.Default.ContactPhone),
            BottomNavItem("dialer", "Dialer", Icons.Default.Call),
            BottomNavItem("nid", "NID", Icons.Default.Fingerprint),
            BottomNavItem("settings", "Settings", Icons.Default.Settings)
        )
    }

    Scaffold(
        containerColor = Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // Use 0 to allow full screen behind glassy bars
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            Box(
                modifier = Modifier
                    .navigationBarsPadding() // Ensure bar stays above system nav
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp)
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    modifier = Modifier
                        .height(80.dp)
                        .glassy(radius = 40.dp, blur = 20.dp),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    tabs.forEach { item ->
                        val isSelected = currentRoute == item.route
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f)
                                )
                            },
                            label = {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f)
                                )
                            },
                            selected = isSelected,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.White.copy(alpha = 0.1f),
                                selectedIconColor = Color.White,
                                unselectedIconColor = Color.White.copy(alpha = 0.4f),
                                selectedTextColor = Color.White,
                                unselectedTextColor = Color.White.copy(alpha = 0.4f)
                            ),
                            onClick = {
                                if (currentRoute != item.route) {
                                    if (item.route == "contacts") {
                                        viewModel.syncWhatsAppPhotos()
                                    }
                                    if (item.route == "settings") {
                                        parentNavController.navigate("settings")
                                    } else {
                                        try {
                                            navController.navigate(item.route) {
                                                navController.graph.startDestinationRoute?.let { startRoute ->
                                                    popUpTo(startRoute) {
                                                        saveState = true
                                                    }
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        } catch (_: Exception) {
                                            navController.navigate(item.route) {
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "recents",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) }
        ) {
            composable("recents") {
                RecentsScreen(
                    viewModel = viewModel,
                    innerPadding = innerPadding,
                    onNavigateToDetails = { number ->
                        viewModel.searchNumber(number)
                        parentNavController.navigate("details")
                    }
                )
            }
            composable("contacts") {
                ContactsScreen(
                    viewModel = viewModel,
                    innerPadding = innerPadding,
                    onNavigateToDetails = { number ->
                        viewModel.searchNumber(number)
                        parentNavController.navigate("details")
                    }
                )
            }
            composable("dialer") {
                DialerScreen(
                    viewModel = viewModel,
                    onCall = { number ->
                        onMakeCall(number)
                    },
                    innerPadding = innerPadding
                )
            }
            composable("nid") {
                NidLookupScreen(viewModel = viewModel)
            }
        }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
