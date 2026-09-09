package com.infocaller.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.core.content.edit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
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
import com.infocaller.app.ui.theme.Primary
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

    val bulkProgress by com.infocaller.app.data.repository.BulkIdentityEngine.progress.collectAsState()
    val updateState by com.infocaller.app.util.AppUpdateManager.state.collectAsState()
    var updateDismissed by remember { mutableStateOf(false) }

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
    LaunchedEffect(recentCalls) {
        if (recentCalls.isNotEmpty()) {
            val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
            recentCalls.take(10).forEach { entry ->
                app.enrichmentEngine.enqueue(entry.number, priority = com.infocaller.app.data.local.entity.QueuePriority.MEDIUM)
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

    val navBarContainer = if (MaterialTheme.colorScheme.background.red * 0.299f + MaterialTheme.colorScheme.background.green * 0.587f + MaterialTheme.colorScheme.background.blue * 0.114f < 0.5f) Color(0xFF0B1322) else MaterialTheme.colorScheme.surface
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
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp)
            ) {
                NavigationBar(
                    containerColor = navBarContainer,
                    modifier = Modifier
                        .height(80.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    navContent.copy(alpha = 0.25f),
                                    navContent.copy(alpha = 0.05f)
                                )
                            ),
                            shape = RoundedCornerShape(40.dp)
                        ),
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
                                indicatorColor = navContent.copy(alpha = 0.1f),
                                selectedIconColor = navContent,
                                unselectedIconColor = navContent.copy(alpha = 0.4f),
                                selectedTextColor = navContent,
                                unselectedTextColor = navContent.copy(alpha = 0.4f)
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
            if (bulkProgress.running && bulkProgress.total > 0) {
                val pct = (bulkProgress.done.toFloat() / bulkProgress.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = innerPadding.calculateBottomPadding() + 104.dp)
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
                        CircularProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Identifying contacts ${bulkProgress.done}/${bulkProgress.total}",
                                style = MaterialTheme.typography.labelMedium,
                                color = navContent,
                            )
                            bulkProgress.lastLabel?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it.take(40),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = navContent.copy(alpha = 0.6f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
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
                                "Tap Download to update",
                                style = MaterialTheme.typography.labelSmall,
                                color = navContent.copy(alpha = 0.6f),
                            )
                        }
                        TextButton(
                            onClick = {
                                try {
                                    launchScope.launch {
                                        com.infocaller.app.util.AppUpdateManager.downloadUpdate(
                                            context,
                                            com.infocaller.app.util.AppUpdateManager.ReleaseInfo(
                                                update.version, update.notes, update.url, update.sizeBytes
                                            )
                                        )
                                    }
                                } catch (_: Exception) { }
                            }
                        ) { Text("Download", color = Primary) }
                        IconButton(onClick = { updateDismissed = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = navContent.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (updateState is com.infocaller.app.util.AppUpdateManager.UpdateState.Downloading) {
                val pct = (updateState as com.infocaller.app.util.AppUpdateManager.UpdateState.Downloading).progress
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding() + 8.dp)
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = navBarContainer.copy(alpha = 0.97f)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("Downloading update… $pct%", style = MaterialTheme.typography.labelMedium, color = navContent)
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
