package com.infocaller.app.ui.screens

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
import androidx.compose.runtime.saveable.rememberSaveable
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
    // Stages: 1 = dialer role, 3 = overlay, 5 = notifications, 6 = done.
    // Unused numbers are never produced; -1 is the permanent-denial escape hatch.
    var currentStage by rememberSaveable { mutableIntStateOf(1) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }


    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        if (PermissionManager.isDefaultDialer(context)) {
            currentStage = 3
        } else {
            // User dismissed without choosing: stay on stage 1 with guidance
            // instead of advancing or stalling silently.
            permanentlyDenied = false
        }
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        // Re-evaluate on return from Settings: role granted -> overlay stage;
        // overlay granted while on stage 3 -> notifications/done.
        if (PermissionManager.isDefaultDialer(context) && currentStage == 1) {
            currentStage = 3
        }
        if (currentStage == 3 && PermissionManager.canDrawOverlays(context)) {
            currentStage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6
        }
    }
    LaunchedEffect(currentStage) { if (currentStage == 6) onComplete() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) currentStage = 6
        else permanentlyDenied = true
    }

    Box(modifier = Modifier.fillMaxSize().background(Background).padding(24.dp), contentAlignment = Alignment.Center) {
        when (currentStage) {
            1 -> RoleDialerExplanation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    if (intent != null) roleLauncher.launch(intent)
                    else permanentlyDenied = true
                } else {
                    val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                    }
                    try { roleLauncher.launch(intent) } catch (_: Exception) { permanentlyDenied = true }
                }
            }
            3 -> OverlayPermissionRationale(
                onGrant = { PermissionManager.openOverlaySettings(context) },
                onSkip = { currentStage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6 }
            )
            5 -> NotificationRationale(onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) else currentStage = 6
            })
            -1 -> BlockingErrorScreen(onOpenSettings = { PermissionManager.openAppSettings(context) })
        }
        if (permanentlyDenied && currentStage != -1) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = { permanentlyDenied = false; currentStage = -1 }) {
                Text("Trouble continuing? Open app settings", color = Color.White.copy(alpha = 0.7f))
            }
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
fun NotificationRationale(onGrant: () -> Unit) {    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
fun OverlayPermissionRationale(onGrant: () -> Unit, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Display Over Apps", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text("To show caller ID on top of other apps, we need 'Display over other apps'. You can grant it later when a call arrives.", textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Go to Settings") }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = Color.White.copy(alpha = 0.7f)) }
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
