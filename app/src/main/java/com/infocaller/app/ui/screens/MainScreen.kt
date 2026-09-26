package com.infocaller.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.Elevations
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.Radii
import com.infocaller.app.ui.theme.Space
import com.infocaller.app.ui.theme.strokeSoft
import com.infocaller.app.ui.viewmodel.CallerViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    parentNavController: NavHostController,
    viewModel: CallerViewModel,
    onMakeCall: (String) -> Unit,
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val launchScope = rememberCoroutineScope()

    val updateState by com.infocaller.app.util.AppUpdateManager.state.collectAsState()
    var updateDismissed by remember { mutableStateOf(false) }

    // Default caller-ID / spam-app roles can be lost (app upgraded in place, user skipped onboarding,
    // another dialer took over). Surface a persistent banner + on-demand system prompt on the main
    // screen so the role popups are always reachable, not only during first-run onboarding.
    var roleBannerDismissed by remember { mutableStateOf(false) }
    var roleCheckKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { roleCheckKey++ }
    val needsDialerRole = remember(roleCheckKey) {
        !com.infocaller.app.permissions.PermissionManager.isDefaultDialer(context)
    }
    val needsSpamRole = remember(roleCheckKey) {
        !com.infocaller.app.permissions.PermissionManager.isCallScreeningRoleHeld(context)
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { roleCheckKey++ }

    LaunchedEffect(Unit) {
        val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
        try {
            viewModel.loadSimInfos(context)
            val sims = com.infocaller.app.util.SimManager.getSimInfos(context)
            app.operatorLogoManager.initialize(sims)
        } catch (_: Exception) { }

        try {
            launchScope.launch {
                try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.infocaller.app.util.AppUpdateManager.checkForUpdate(context, force = false)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        if (com.infocaller.app.permissions.PermissionManager.hasPermissions(
                context, com.infocaller.app.permissions.PermissionManager.CONTACTS_PERMISSIONS
            )
        ) {
            try {
                launchScope.launch {
                    try {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            kotlinx.coroutines.delay(20000)
                            com.infocaller.app.data.repository.BulkIdentityEngine.runFullPass(context)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        } else {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val isFirstSyncDone = prefs.getBoolean("is_first_sync_done", false)
            if (!isFirstSyncDone) {
                viewModel.triggerThrottledSync(context)
                prefs.edit { putBoolean("is_first_sync_done", true) }
            }
        }
    }

    val recentCalls by viewModel.recentCalls.collectAsState()
    // Enqueue each recent number only once per session — recentCalls re-emits on
    // every tick/data change and blind re-enqueue spams the enrichment queue.
    val enqueuedRecent = remember { mutableSetOf<String>() }
    LaunchedEffect(recentCalls) {
        if (recentCalls.isNotEmpty()) {
            val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
            recentCalls.take(5).forEach { entry ->
                try {
                    if (enqueuedRecent.add(entry.number)) {
                        app.enrichmentEngine.enqueue(entry.number, priority = com.infocaller.app.data.local.entity.QueuePriority.MEDIUM)
                    }
                } catch (_: Exception) { } catch (_: Error) { }
            }
        }
    }

    val tabs = remember {
        listOf(
            BottomNavItem("recents", "Recent", Icons.Default.History),
            BottomNavItem("contacts", "Contacts", Icons.Default.ContactPhone),
            BottomNavItem("settings", "Settings", Icons.Default.Settings)
        )
    }

    val navBarContainer = if (MaterialTheme.colorScheme.background.red * 0.299f + MaterialTheme.colorScheme.background.green * 0.587f + MaterialTheme.colorScheme.background.blue * 0.114f < 0.5f) Color(0xFF0B0B0E) else MaterialTheme.colorScheme.surfaceContainer
    val navContent = MaterialTheme.colorScheme.onBackground
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Space.lg)
                    .padding(bottom = Space.md)
                    .shadow(
                        elevation = Elevations.mid,
                        shape = RoundedCornerShape(Radii.pill),
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.35f)
                    )
            ) {
                NavigationBar(
                    containerColor = navBarContainer,
                    modifier = Modifier
                        .height(76.dp)
                        .clip(RoundedCornerShape(Radii.pill))
                        .border(1.dp, strokeSoft, RoundedCornerShape(Radii.pill)),
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
                                    tint = if (isSelected) navContent else navContent.copy(alpha = 0.4f)
                                )
                            },
                            label = {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) navContent else navContent.copy(alpha = 0.4f)
                                )
                            },
                            selected = isSelected,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = if (isSelected) Primary.copy(alpha = 0.18f) else Color.Transparent,
                                selectedIconColor = Primary,
                                unselectedIconColor = navContent.copy(alpha = 0.38f),
                                selectedTextColor = Primary,
                                unselectedTextColor = navContent.copy(alpha = 0.38f)
                            ),
                            onClick = {
                                if (currentRoute != item.route) {
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
        Box(modifier = Modifier.fillMaxSize()) {
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
                            parentNavController.navigate("details/" + android.net.Uri.encode(number))
                        }
                    )
                }
                composable("contacts") {
                    ContactsScreen(
                        viewModel = viewModel,
                        innerPadding = innerPadding,
                        onMakeCall = onMakeCall,
                        onNavigateToDetails = { number ->
                            viewModel.searchNumber(number)
                            parentNavController.navigate("details/" + android.net.Uri.encode(number))
                        }
                    )
                }
            }
            val update = updateState as? com.infocaller.app.util.AppUpdateManager.UpdateState.Available
            if (update != null && !updateDismissed) {
                val mb = if (update.sizeBytes > 0) " • ${(update.sizeBytes / 1048576)} MB" else ""
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding() + 8.dp)
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = navBarContainer.copy(alpha = 0.97f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Update v${update.version} available$mb",
                                style = MaterialTheme.typography.labelMedium,
                                color = navContent,
                            )
                            Text(
                                "Tap Open to get it from GitHub",
                                style = MaterialTheme.typography.labelSmall,
                                color = navContent.copy(alpha = 0.6f),
                            )
                        }
                        TextButton(
                            onClick = {
                                try {
                                    com.infocaller.app.util.AppUpdateManager.openReleasePage(context)
                                } catch (_: Exception) { }
                            }
                        ) { Text("Open", color = Primary) }
                        IconButton(onClick = { updateDismissed = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = navContent.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if ((needsDialerRole || needsSpamRole) && !roleBannerDismissed) {
                val updateShown = update != null && !updateDismissed
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding() + 8.dp + if (updateShown) 84.dp else 0.dp)
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = navBarContainer.copy(alpha = 0.97f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Set InfoCaller as default",
                                style = MaterialTheme.typography.labelMedium,
                                color = navContent,
                            )
                            Text(
                                if (needsDialerRole && needsSpamRole)
                                    "Caller ID & spam app and default Phone app are off — tap to turn on"
                                else if (needsDialerRole)
                                    "Default Phone app is off — tap to turn on caller ID"
                                else
                                    "Caller ID & spam app is off — tap to turn on spam detection",
                                style = MaterialTheme.typography.labelSmall,
                                color = navContent.copy(alpha = 0.6f),
                            )
                        }
                        TextButton(
                            onClick = {
                                try {
                                    val intent = if (needsDialerRole) {
                                        com.infocaller.app.permissions.PermissionManager.createDefaultDialerIntent(context)
                                    } else {
                                        com.infocaller.app.permissions.PermissionManager.createCallScreeningRoleIntent(context)
                                    }
                                    if (intent != null) roleLauncher.launch(intent)
                                    else com.infocaller.app.permissions.PermissionManager.openAppSettings(context)
                                } catch (_: Exception) {
                                    try {
                                        com.infocaller.app.permissions.PermissionManager.openAppSettings(context)
                                    } catch (_: Exception) { }
                                }
                            }
                        ) { Text("Set up", color = Primary) }
                        IconButton(onClick = { roleBannerDismissed = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = navContent.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
