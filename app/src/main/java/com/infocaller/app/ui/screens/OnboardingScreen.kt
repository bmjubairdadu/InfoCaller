package com.infocaller.app.ui.screens

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.Primary

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    var currentStage by remember { mutableIntStateOf(1) }
    
    // STAGE 1: Request ROLE_DIALER via RoleManager
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        if (PermissionManager.isDefaultDialer(context)) {
            currentStage = 2
        }
    }

    // STAGE 2: Request Essential Core Permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            currentStage = if (PermissionManager.canDrawOverlays(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6
            } else {
                3
            }
        } else {
            val permanentlyDenied = PermissionManager.CORE_PERMISSIONS.any { 
                !PermissionManager.hasPermission(context, it) && 
                !activity.shouldShowRequestPermissionRationale(it)
            }
            if (permanentlyDenied) {
                currentStage = -1 // Error stage
            }
        }
    }

    // STAGE 3: Handle returning from Overlay Settings
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (currentStage == 3 && PermissionManager.canDrawOverlays(context)) {
            currentStage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6
        }
    }

    // STAGE 6: Completion
    LaunchedEffect(currentStage) {
        if (currentStage == 6) {
            onComplete()
        }
    }

    // STAGE 5: Request POST_NOTIFICATIONS (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        currentStage = 6
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        when (currentStage) {
            1 -> RoleDialerExplanation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    intent?.let { roleLauncher.launch(it) }
                } else {
                    val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                    }
                    roleLauncher.launch(intent)
                }
            }
            2 -> CorePermissionsRationale(onGrant = {
                permissionLauncher.launch(PermissionManager.CORE_PERMISSIONS)
            })
            3 -> OverlayPermissionRationale(onGrant = {
                PermissionManager.openOverlaySettings(context)
            })
            5 -> NotificationRationale(onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onComplete()
                }
            })
            -1 -> BlockingErrorScreen(onOpenSettings = {
                PermissionManager.openAppSettings(context)
            })
        }
    }
}

@Composable
fun RoleDialerExplanation(onGrant: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Default Phone App", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To identify callers and manage your calls, InfoCaller must be set as your default Phone app.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Set as Default")
        }
    }
}

@Composable
fun CorePermissionsRationale(onGrant: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Essential Permissions", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "We need access to your phone state and calling features to handle your calls correctly.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Grant Permissions")
        }
    }
}

@Composable
fun NotificationRationale(onGrant: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Notifications", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "InfoCaller needs notification access to alert you of incoming calls while you're using other apps.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Enable Notifications")
        }
    }
}

@Composable
fun OverlayPermissionRationale(onGrant: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Display Over Apps", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To show caller ID information on top of other apps, we need the 'Display over other apps' permission.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Go to Settings")
        }
    }
}

@Composable
fun BlockingErrorScreen(onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Action Required", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Essential permissions were permanently denied. Please enable them in App Settings to continue.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Open App Settings")
        }
    }
}
