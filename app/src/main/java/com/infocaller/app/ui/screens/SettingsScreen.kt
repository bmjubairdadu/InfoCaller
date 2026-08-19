package com.infocaller.app.ui.screens

import android.content.Context
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.Primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: com.infocaller.app.ui.viewmodel.CallerViewModel,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToProviderAuth: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var recordingEnabled by remember { mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.RECORD_AUDIO_PERMISSION)) }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            SettingsSection("Calls") {
                SettingsToggleRow(
                    title = "Call Recording",
                    subtitle = "Automatically record calls",
                    icon = Icons.Default.FiberManualRecord,
                    checked = recordingEnabled,
                    onCheckedChange = { 
                        if (it) recordingLauncher.launch(PermissionManager.RECORD_AUDIO_PERMISSION)
                        else recordingEnabled = false
                    }
                )
            }

            SettingsSection("Security") {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                var spamProtection by remember { mutableStateOf(prefs.getBoolean("spam_protection_enabled", true)) }
                
                SettingsToggleRow(
                    title = "Spam Protection",
                    subtitle = "Block identified scammers",
                    icon = Icons.Default.Shield,
                    checked = spamProtection,
                    onCheckedChange = { 
                        spamProtection = it
                        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit {
                            putBoolean("spam_protection_enabled", it)
                        }
                    }
                )
            }

            SettingsSection("Appearance") {
                val darkTheme by viewModel.themeMode.collectAsState()
                
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text("Select your preferred visual style") },
                    leadingContent = { Icon(Icons.Default.Palette, null, tint = Primary) },
                    trailingContent = {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(if (darkTheme == null) "System" else if (darkTheme == true) "Dark" else "Light", color = Primary)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("Light") }, onClick = { viewModel.setThemeMode(false, context); expanded = false })
                                DropdownMenuItem(text = { Text("Dark") }, onClick = { viewModel.setThemeMode(true, context); expanded = false })
                                DropdownMenuItem(text = { Text("System Default") }, onClick = { viewModel.setThemeMode(null, context); expanded = false })
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            SettingsSection("Intelligence") {
                SettingsClickRow(
                    title = "Provider Authorization",
                    subtitle = "Truecaller, WhatsApp, etc.",
                    icon = Icons.Default.CloudSync,
                    onClick = onNavigateToProviderAuth
                )
            }

            SettingsSection("About") {
                SettingsInfoRow("Version", "1.7.5 (Gold)", Icons.Default.Info)
                SettingsClickRow(
                    title = "Privacy Policy",
                    subtitle = "Read our data policy",
                    icon = Icons.Default.Security,
                    onClick = onNavigateToPrivacy
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Primary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsToggleRow(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
        leadingContent = { Icon(icon, null, tint = Primary) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = Primary))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun SettingsClickRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
        leadingContent = { Icon(icon, null, tint = Primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun SettingsInfoRow(title: String, value: String, icon: ImageVector) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, color = Primary, fontWeight = FontWeight.Bold) },
        leadingContent = { Icon(icon, null, tint = Primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
