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
    // Stages: 1 = dialer role, 2 = essential call permissions, 3 = overlay,
    // 5 = notifications, 6 = done. -1 is the settings escape hatch.
    var currentStage by rememberSaveable { mutableIntStateOf(1) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var roleAttempted by rememberSaveable { mutableStateOf(false) }
    var roleError by rememberSaveable { mutableStateOf<String?>(null) }
    var callPermsError by rememberSaveable { mutableStateOf(false) }


    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        if (PermissionManager.isDefaultDialer(context)) {
            roleError = null
            currentStage = 2
        } else if (roleAttempted) {
            // User dismissed or picked another app: say so instead of stalling silently.
            roleError = "Still not set — pick InfoCaller in the system list, or skip for now."
        }
    }

    val callPermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            callPermsError = false
            currentStage = 3
        } else {
            callPermsError = true
        }
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        // Re-evaluate on return from system Settings: role granted -> permissions stage;
        // overlay granted while on stage 3 -> notifications/done.
        if (PermissionManager.isDefaultDialer(context) && currentStage == 1) {
            roleError = null
            currentStage = 2
        }
        if (currentStage == 3 && PermissionManager.canDrawOverlays(context)) {
            currentStage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6
        }
    }
    LaunchedEffect(currentStage) {
        if (currentStage == 6) {
            try {
                context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("onboarding_completed", true).apply()
            } catch (_: Exception) { }
            onComplete()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notifications are optional: denial still completes onboarding.
        currentStage = 6
    }

    Box(modifier = Modifier.fillMaxSize().background(Background).padding(24.dp), contentAlignment = Alignment.Center) {
        when (currentStage) {
            1 -> RoleDialerExplanation(
                error = roleError,
                onGrant = {
                    roleAttempted = true
                    roleError = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val roleManager = context.getSystemService(RoleManager::class.java)
                            val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                            if (intent != null) roleLauncher.launch(intent)
                            else roleError = "Your system didn't return a request screen. Use system settings instead."
                        } catch (_: Exception) {
                            roleError = "The system blocked the request. Use system settings instead."
                        }
                    } else {
                        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                        }
                        try { roleLauncher.launch(intent) } catch (_: Exception) {
                            roleError = "The system blocked the request. Use system settings instead."
                        }
                    }
                },
                onOpenDefaultApps = {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) { PermissionManager.openAppSettings(context) }
                },
                onSkip = { currentStage = 2 }
            )
            2 -> CallPermissionsExplanation(
                showError = callPermsError,
                onGrant = {
                    callPermsError = false
                    callPermsLauncher.launch(PermissionManager.REQUIRED_RUNTIME_CALL_PERMISSIONS)
                },
                onSkip = { currentStage = 3 }
            )
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
fun RoleDialerExplanation(
    error: String?,
    onGrant: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onSkip: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Default Phone App", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To identify callers and manage your calls, InfoCaller must be set as your default Phone app.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(error, textAlign = TextAlign.Center, color = Color(0xFFFFB4A9))
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenDefaultApps) {
                Text("Open system settings", color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Set as Default")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = Color.White.copy(alpha = 0.7f)) }
    }
}

@Composable
fun CallPermissionsExplanation(
    showError: Boolean,
    onGrant: () -> Unit,
    onSkip: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Call Permissions", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Allow InfoCaller to place and answer calls and detect incoming numbers. Without this, caller ID and dialing can't work.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        if (showError) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Some permissions were denied. Caller ID needs them — try again, or skip and grant later when asked.",
                textAlign = TextAlign.Center,
                color = Color(0xFFFFB4A9)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Grant Permissions")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = Color.White.copy(alpha = 0.7f)) }
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
