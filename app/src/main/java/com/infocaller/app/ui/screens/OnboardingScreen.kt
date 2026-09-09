package com.infocaller.app.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.contentPrimary
import com.infocaller.app.ui.theme.contentSecondary
import com.infocaller.app.util.UserLocationResolver
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    var currentStage by rememberSaveable { mutableIntStateOf(0) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var roleAttempted by rememberSaveable { mutableStateOf(false) }
    var roleError by rememberSaveable { mutableStateOf<String?>(null) }
    var spamRoleError by rememberSaveable { mutableStateOf<String?>(null) }
    var spamRoleAttempted by rememberSaveable { mutableStateOf(false) }
    var callPermsError by rememberSaveable { mutableStateOf(false) }
    val basicPermQueue = remember {
        listOf(
            PermissionManager.CALL_LOG_PERMISSIONS.toList() to "Call logs",
            PermissionManager.CONTACTS_PERMISSIONS.toList() to "Contacts",
            (PermissionManager.DIALER_PERMISSIONS + PermissionManager.CALLER_ID_PERMISSIONS).toList() to "Phone & call management",
            PermissionManager.WRITE_CONTACTS_PERMISSION.toList() to "Save caller photos",
            PermissionManager.SMS_PERMISSION.toList() to "SMS verification",
        )
    }
    var permQueueIndex by rememberSaveable { mutableIntStateOf(-1) }
    var showBasicPermsPopup by rememberSaveable { mutableStateOf(false) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        if (PermissionManager.isDefaultDialer(context)) {
            roleError = null
            currentStage = 3
        } else if (roleAttempted) {
            roleError = "Still not set — pick InfoCaller in the system list, then tap \"Check again\"."
        }
    }

    val spamRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        if (PermissionManager.isCallScreeningRoleHeld(context)) {
            spamRoleError = null
            showBasicPermsPopup = true
            currentStage = 1
        } else if (spamRoleAttempted) {
            spamRoleError = "Still not set — pick InfoCaller as the Caller ID & spam app, then tap \"Check again\"."
        }
    }

    var onePermLauncherRef: androidx.activity.result.ActivityResultLauncher<Array<String>>? by remember { mutableStateOf(null) }
    val onePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val next = permQueueIndex + 1
        if (next < basicPermQueue.size) {
            permQueueIndex = next
            try {
                onePermLauncherRef?.launch(basicPermQueue[next].first.toTypedArray())
            } catch (_: Exception) {
                currentStage = 2
            }
        } else {
            permQueueIndex = -1
            callPermsError = PermissionManager.missingPermissions(
                context, PermissionManager.REQUIRED_RUNTIME_CALL_PERMISSIONS
            ).isNotEmpty()
            currentStage = 2
        }
    }
    LaunchedEffect(onePermLauncher) { onePermLauncherRef = onePermLauncher }

    val callPermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            callPermsError = false
            currentStage = 3
        } else {
            callPermsError = true
            currentStage = 3
        }
    }

    LaunchedEffect(currentStage) {
        if (currentStage == 0 || currentStage == 2) {
            while (currentStage == 0 || currentStage == 2) {
                delay(1500)
                try {
                    if (currentStage == 0 && PermissionManager.isCallScreeningRoleHeld(context)) {
                        spamRoleError = null
                        showBasicPermsPopup = true
                        currentStage = 1
                    } else if (currentStage == 2 && PermissionManager.isDefaultDialer(context)) {
                        roleError = null
                        currentStage = 3
                    }
                } catch (_: Exception) { }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (currentStage == 2 && PermissionManager.isDefaultDialer(context)) {
            roleError = null
            currentStage = 3
        }
        if (currentStage == 0 && PermissionManager.isCallScreeningRoleHeld(context)) {
            spamRoleError = null
            showBasicPermsPopup = true
            currentStage = 1
        }
        if (currentStage == 3 && PermissionManager.canDrawOverlays(context)) {
            currentStage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6
        }
    }

    LaunchedEffect(Unit) {
        try {
            val screeningHeld = PermissionManager.isCallScreeningRoleHeld(context)
            val dialerHeld = PermissionManager.isDefaultDialer(context)
            if (screeningHeld && dialerHeld) {
                currentStage = 3
            } else if (screeningHeld) {
                currentStage = 1
                showBasicPermsPopup = false
            } else {
                currentStage = 0
            }
        } catch (_: Exception) { currentStage = 0 }
    }

    LaunchedEffect(currentStage) {
        if (currentStage == 6) {
            try {
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("onboarding_completed", true).apply()
            } catch (_: Exception) { }
            onComplete()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        currentStage = 6
    }

    val onboardingScope = rememberCoroutineScope()
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            onboardingScope.launch(Dispatchers.IO) {
                try {
                    val loc = UserLocationResolver.resolve(context)
                    if (loc != null && !loc.isBlank()) {
                        UserLocationResolver.bindToSimSlots(context, loc)
                    }
                } catch (_: Exception) { }
            }
        }
        currentStage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.Center) {
        when (currentStage) {
            0 -> SpamRoleExplanation(
                error = spamRoleError,
                onGrant = {
                    spamRoleAttempted = true
                    spamRoleError = null
                    try {
                        val intent = PermissionManager.createCallScreeningRoleIntent(context)
                        if (intent != null) spamRoleLauncher.launch(intent)
                        else spamRoleError = "Your system didn't return a request screen. Use system settings instead."
                    } catch (_: Exception) {
                        spamRoleError = "The system blocked the request. Use system settings instead."
                    }
                },
                onOpenDefaultApps = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) { PermissionManager.openAppSettings(context) }
                },
                onCheckAgain = {
                    if (PermissionManager.isCallScreeningRoleHeld(context)) {
                        spamRoleError = null
                        showBasicPermsPopup = true
                        currentStage = 1
                    } else {
                        spamRoleError = "Still not set — pick InfoCaller as the Caller ID & spam app, then tap \"Check again\"."
                    }
                },
                onSkip = { showBasicPermsPopup = true; currentStage = 1 }
            )
            1 -> BasicPermissionsStageBody(
                queue = basicPermQueue,
                queueIndex = permQueueIndex,
                showError = callPermsError,
                onStartQueue = { showBasicPermsPopup = true },
                onSkip = { currentStage = 2 },
            )
            2 -> RoleDialerExplanation(
                error = roleError,
                onGrant = {
                    roleAttempted = true
                    roleError = null
                    try {
                        val intent = PermissionManager.createDefaultDialerIntent(context)
                        if (intent != null) roleLauncher.launch(intent)
                        else roleError = "Your system didn't return a request screen. Use system settings instead."
                    } catch (_: Exception) {
                        roleError = "The system blocked the request. Use system settings instead."
                    }
                },
                onOpenDefaultApps = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) { PermissionManager.openAppSettings(context) }
                },
                onCheckAgain = {
                    if (PermissionManager.isDefaultDialer(context)) {
                        roleError = null
                        currentStage = 3
                    } else {
                        roleError = "Still not set — pick InfoCaller in the system list, then tap \"Check again\"."
                    }
                },
                onSkip = { currentStage = 3 }
            )
            3 -> OverlayPermissionRationale(
                onGrant = { PermissionManager.openOverlaySettings(context) },
                onSkip = { currentStage = 4 }
            )
            4 -> LocationRationale(
                onGrant = {
                    locationLauncher.launch(PermissionManager.LOCATION_PERMISSIONS)
                },
                onSkip = { currentStage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 6 }
            )
            5 -> NotificationRationale(onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS) else currentStage = 6
            })
            -1 -> BlockingErrorScreen(onOpenSettings = { PermissionManager.openAppSettings(context) })
        }
        if (permanentlyDenied && currentStage != -1) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = { permanentlyDenied = false; currentStage = -1 }) {
                Text("Trouble continuing? Open app settings", color = contentSecondary(0.7f))
            }
        }

        if (showBasicPermsPopup && currentStage == 1) {
            AlertDialog(
                onDismissRequest = { showBasicPermsPopup = false },
                icon = { Icon(Icons.Default.VerifiedUser, null, tint = Primary) },
                title = { Text("Required permissions") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "InfoCaller needs these to identify callers and protect you from spam. Tap OK and each is requested one after another:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        basicPermQueue.forEach { (_, label) ->
                            Text("• $label", style = MaterialTheme.typography.bodySmall, color = contentSecondary(0.85f))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showBasicPermsPopup = false
                        callPermsError = false
                        val firstMissing = basicPermQueue.indexOfFirst { (perms, _) ->
                            PermissionManager.missingPermissions(context, perms.toTypedArray()).isNotEmpty()
                        }
                        if (firstMissing < 0) {
                            currentStage = 2
                        } else {
                            permQueueIndex = firstMissing
                            try {
                                onePermLauncher.launch(basicPermQueue[firstMissing].first.toTypedArray())
                            } catch (_: Exception) {
                                currentStage = 2
                            }
                        }
                    }) { Text("OK", color = Primary) }
                },
                dismissButton = {
                    TextButton(onClick = { showBasicPermsPopup = false; currentStage = 2 }) {
                        Text("Skip", color = contentSecondary(0.7f))
                    }
                }
            )
        }
    }
}

@Composable
fun RoleDialerExplanation(
    error: String?,
    onGrant: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onSkip: () -> Unit,
    onCheckAgain: () -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Default Phone App", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To identify callers and manage your calls, InfoCaller must be set as your default Phone app. This allows the app to show caller identity and provide dialer features directly on your system permission screens.",
            textAlign = TextAlign.Center,
            color = contentSecondary(0.7f)
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(error, textAlign = TextAlign.Center, color = Color(0xFFFFB4A9))
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenDefaultApps) {
                Text("Open system settings", color = contentPrimary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onCheckAgain) {
                Text("Check again", color = contentPrimary)
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Set as Default")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = contentSecondary(0.7f)) }
    }
}

@Composable
fun CallPermissionsExplanation(
    showError: Boolean,
    onGrant: () -> Unit,
    onSkip: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Default Phone Permissions", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "As your default Phone app, InfoCaller needs to place calls, read the ringing number, answer and manage calls, plus access your call history and contacts so the dialer can show recents and names.",
            textAlign = TextAlign.Center,
            color = contentSecondary(0.7f)
        )
        if (showError) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Some permissions were denied. Caller ID needs them — try again, or continue and grant later when asked.",
                textAlign = TextAlign.Center,
                color = Color(0xFFFFB4A9)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Grant Permissions")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = contentSecondary(0.7f)) }
    }
}

@Composable
fun BasicPermissionsStageBody(
    queue: List<Pair<List<String>, String>>,
    queueIndex: Int,
    showError: Boolean,
    onStartQueue: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Basic Permissions", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "InfoCaller needs call logs, contacts, phone state, and message access for caller ID, spam protection, and verification. Tap OK in the popup and each permission is requested one after another.",
            textAlign = TextAlign.Center,
            color = contentSecondary(0.7f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        queue.forEachIndexed { i, (_, label) ->
            val state = when {
                queueIndex > i -> "✓"
                queueIndex == i -> "…"
                else -> "○"
            }
            Text(
                "$state $label",
                color = if (queueIndex == i) contentPrimary else contentSecondary(0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (showError) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Some permissions were denied. Caller ID needs them — try again, or continue and grant later when asked.",
                textAlign = TextAlign.Center,
                color = Color(0xFFFFB4A9)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onStartQueue, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text(if (queueIndex >= 0) "Continue Permissions" else "Show Permissions")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = contentSecondary(0.7f)) }
    }
}

@Composable
fun SpamRoleExplanation(
    error: String?,
    onGrant: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onCheckAgain: () -> Unit,
    onSkip: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Caller ID & Spam App", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To detect spam and identify unknown callers before the phone rings, InfoCaller must ALSO show as your Caller ID & spam app. InfoCaller helps you block unwanted calls and identify numbers.",
            textAlign = TextAlign.Center,
            color = contentSecondary(0.7f)
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(error, textAlign = TextAlign.Center, color = Color(0xFFFFB4A9))
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenDefaultApps) {
                Text("Open system settings", color = contentPrimary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onCheckAgain) {
                Text("Check again", color = contentPrimary)
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Set as Spam App")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = contentSecondary(0.7f)) }
    }
}

@Composable
fun NotificationRationale(onGrant: () -> Unit) {    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Notifications", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "InfoCaller needs notification access to alert you of incoming calls while you're using other apps.",
            textAlign = TextAlign.Center,
            color = contentSecondary(0.7f)
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
        Text("Display Over Apps", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("To show caller ID on top of other apps, we need 'Display over other apps'. You can grant it later when a call arrives.", textAlign = TextAlign.Center, color = contentSecondary(0.7f))
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Go to Settings") }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = contentSecondary(0.7f)) }
    }
}

@Composable
fun LocationRationale(onGrant: () -> Unit, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Location Access", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To show where you are calling from and to map village names inside caller names, InfoCaller needs your location. It uses your SIM and IP as fallback, so maps still work when precise location is denied.",
            textAlign = TextAlign.Center,
            color = contentSecondary(0.7f),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Allow Location") }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Skip for now", color = contentSecondary(0.7f)) }
    }
}

@Composable
fun BlockingErrorScreen(onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Action Required", style = MaterialTheme.typography.headlineLarge, color = contentPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Essential permissions were permanently denied. Please enable them in App Settings to continue.",
            textAlign = TextAlign.Center,
            color = contentSecondary(0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Open App Settings")
        }
    }
}
