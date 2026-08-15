package com.infocaller.app.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.Primary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: com.infocaller.app.ui.viewmodel.CallerViewModel,
    onNavigateToWhatsAppLookup: () -> Unit = {},
    onNavigateToDeveloperTools: () -> Unit = {}
) {
    val context = LocalContext.current
    
    var bluetoothEnabled by remember { mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.BLUETOOTH_PERMISSION)) }
    var recordingEnabled by remember { mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.RECORD_AUDIO_PERMISSION)) }

    // STAGE 6: Contextual Bluetooth Permission
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        bluetoothEnabled = results.values.all { it }
    }
    
    // STAGE 7: Contextual Audio Recording Permission
    val recordingLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        recordingEnabled = results.values.all { it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
        containerColor = Background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            ListItem(
                headlineContent = { Text("Bluetooth Audio", color = Color.White) },
                supportingContent = { Text("Route call audio to Bluetooth devices", color = Color.White.copy(alpha = 0.6f)) },
                trailingContent = {
                    Switch(
                        checked = bluetoothEnabled,
                        onCheckedChange = { 
                            if (it) {
                                if (PermissionManager.BLUETOOTH_PERMISSION.isNotEmpty()) {
                                    bluetoothLauncher.launch(PermissionManager.BLUETOOTH_PERMISSION)
                                } else {
                                    bluetoothEnabled = true
                                }
                            } else {
                                bluetoothEnabled = false
                            }
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            
            ListItem(
                headlineContent = { Text("Call Recording", color = Color.White) },
                supportingContent = { Text("Record your calls automatically", color = Color.White.copy(alpha = 0.6f)) },
                trailingContent = {
                    Switch(
                        checked = recordingEnabled,
                        onCheckedChange = { 
                            if (it) {
                                recordingLauncher.launch(PermissionManager.RECORD_AUDIO_PERMISSION)
                            } else {
                                recordingEnabled = false
                            }
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            ListItem(
                headlineContent = { Text("WhatsApp Profile Lookup", color = Color.White) },
                supportingContent = { Text("Search profile details by number", color = Color.White.copy(alpha = 0.6f)) },
                trailingContent = {
                    IconButton(onClick = onNavigateToWhatsAppLookup) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = "Lookup", tint = Color.White.copy(alpha = 0.5f))
                    }
                },
                modifier = Modifier.clickable { onNavigateToWhatsAppLookup() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            val recoveryState by viewModel.recoveryState.collectAsState()

            ListItem(
                headlineContent = { Text("Emergency: Fix Names", color = Color.Red) },
                supportingContent = { 
                    Text(
                        text = recoveryState ?: "Remove placeholder names from system contacts", 
                        color = Color.White.copy(alpha = 0.6f)
                    ) 
                },
                trailingContent = {
                    Button(
                        onClick = { viewModel.runEmergencyCleanup() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f), contentColor = Color.Red)
                    ) {
                        Text("Fix Now")
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            var truecallerToken by remember { 
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                mutableStateOf(prefs.getString("truecaller_token", "") ?: "") 
            }

            ListItem(
                headlineContent = { Text("Truecaller Auth Token", color = Color.White) },
                supportingContent = { 
                    OutlinedTextField(
                        value = truecallerToken,
                        onValueChange = { 
                            truecallerToken = it
                            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                .edit().putString("truecaller_token", it).apply()
                        },
                        placeholder = { Text("Enter token from truecallerpy/web", color = Color.White.copy(alpha = 0.4f)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Primary
                        )
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            var testResult by remember { mutableStateOf("") }
            var isTesting by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            ListItem(
                headlineContent = { Text("Diagnostic: Offline ID", color = Color.White) },
                supportingContent = { 
                    Text(
                        text = if (testResult.isEmpty()) "Test local identification" else testResult, 
                        color = if (testResult.contains("Error")) Color.Red.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.6f)
                    ) 
                },
                trailingContent = {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Primary)
                    } else {
                        Button(onClick = {
                            isTesting = true
                            testResult = "Testing..."
                            coroutineScope.launch {
                                try {
                                    val scraper = com.infocaller.app.data.remote.CallerScraper(context)
                                    val result = scraper.fetchCallerInfo("8801731421373")
                                    testResult = "Local ID: Success (${result?.alias ?: "Unknown"})"
                                } catch (e: Exception) {
                                    testResult = "Error: ${e.message}"
                                } finally {
                                    isTesting = false
                                }
                            }
                        }) {
                            Text("Test")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            ListItem(
                headlineContent = { Text("Advanced Developer Tools", color = Primary) },
                supportingContent = { Text("Diagnostics, lookup tests, and system logs", color = Color.White.copy(alpha = 0.6f)) },
                modifier = Modifier.clickable { onNavigateToDeveloperTools() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}
