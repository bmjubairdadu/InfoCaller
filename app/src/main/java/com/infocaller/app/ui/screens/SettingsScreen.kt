package com.infocaller.app.ui.screens

import android.content.Context
import android.content.Intent
import android.app.Activity
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
import com.infocaller.app.data.remote.CommunityConsent
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.Primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: com.infocaller.app.ui.viewmodel.CallerViewModel,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToDetails: (String) -> Unit = {},
    onNavigateToOwnerProfile: () -> Unit = {}
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
            SettingsSection("Identity Lookup") {
                var searchNumber by remember { mutableStateOf("") }
                
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = searchNumber,
                        onValueChange = { searchNumber = it },
                        label = { Text("Search Phone Number") },
                        placeholder = { Text("+880...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        trailingIcon = {
                            if (searchNumber.isNotBlank()) {
                                IconButton(onClick = { searchNumber = "" }) {
                                    Icon(Icons.Default.Clear, null)
                                }
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            if (searchNumber.isNotBlank()) {
                                viewModel.searchNumber(searchNumber)
                                viewModel.triggerThrottledSync(context)
                                onNavigateToDetails(searchNumber)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = searchNumber.length >= 7
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("IDENTIFY CALLER")
                    }
                }
            }

            SettingsSection("Calls") {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                var currentRingtoneUri by remember { mutableStateOf(prefs.getString("custom_ringtone_uri", null)) }
                
                val ringtoneLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                        if (uri != null) {
                            currentRingtoneUri = uri.toString()
                            prefs.edit { putString("custom_ringtone_uri", uri.toString()) }
                        }
                    }
                }

                SettingsClickRow(
                    title = "Incoming Call Ringtone",
                    subtitle = currentRingtoneUri?.let { android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(it)).getTitle(context) } ?: "Default System Ringtone",
                    icon = Icons.Default.MusicNote,
                    onClick = {
                        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Select InfoCaller Ringtone")
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri?.let { android.net.Uri.parse(it) })
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        }
                        ringtoneLauncher.launch(intent)
                    }
                )

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

            SettingsSection("My Caller ID") {
                SettingsClickRow(
                    title = "My Caller Profile",
                    subtitle = "Verify your number, publish only your own info",
                    icon = Icons.Default.VerifiedUser,
                    onClick = onNavigateToOwnerProfile
                )
            }

            SettingsSection("Community Contribution") {
                val consentDecision = remember {
                    mutableStateOf(
                        com.infocaller.app.data.local.ContributionConsentStore.getDecision(context)
                    )
                }
                SettingsToggleRow(
                    title = "Contribute caller-ID info",
                    subtitle = when (consentDecision.value) {
                        com.infocaller.app.data.local.ContributionPolicy.Decision.ACCEPTED ->
                            "On — one-by-one background uploads of permitted fields only"
                        com.infocaller.app.data.local.ContributionPolicy.Decision.DECLINED ->
                            "Off — no uploads, no background contribution"
                        else -> "Not asked yet — open Contacts to choose"
                    },
                    icon = Icons.Default.GroupAdd,
                    checked = consentDecision.value == com.infocaller.app.data.local.ContributionPolicy.Decision.ACCEPTED,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            com.infocaller.app.data.local.ContributionConsentStore.setAccepted(context)
                            consentDecision.value = com.infocaller.app.data.local.ContributionPolicy.Decision.ACCEPTED
                            com.infocaller.app.worker.ContributionWorker.scheduleOnConsent(context)
                        } else {
                            com.infocaller.app.data.local.ContributionConsentStore.setDeclined(context)
                            consentDecision.value = com.infocaller.app.data.local.ContributionPolicy.Decision.DECLINED
                            com.infocaller.app.worker.ContributionWorker.cancel(context)
                        }
                    }
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
