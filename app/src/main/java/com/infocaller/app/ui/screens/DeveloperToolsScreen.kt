package com.infocaller.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(
    viewModel: com.infocaller.app.ui.viewmodel.CallerViewModel,
    onBack: () -> Unit,
    onNavigateToWhatsAppLookup: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer Tools", color = Color.White) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            InfoSection("Device Info") {
                InfoRow("Android Version", Build.VERSION.RELEASE)
                InfoRow("API Level", Build.VERSION.SDK_INT.toString())
                InfoRow("Model", Build.MODEL)
            }
            
            InfoSection("App Status") {
                InfoRow("Default Dialer", PermissionManager.isDefaultDialer(context).toString())
                InfoRow("Permissions", if (PermissionManager.hasPermissions(context, PermissionManager.CORE_PERMISSIONS)) "Granted" else "Missing")
                
                Spacer(modifier = Modifier.height(16.dp))
                
                var autoLookup by remember { 
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    mutableStateOf(prefs.getBoolean("auto_lookup_enabled", true)) 
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Automatic Caller Lookup", color = Color.White)
                    Switch(checked = autoLookup, onCheckedChange = { 
                        autoLookup = it
                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("auto_lookup_enabled", it).apply()
                    })
                }

                var bgEnrichment by remember { 
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    mutableStateOf(prefs.getBoolean("background_enrichment_enabled", true)) 
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Background Enrichment", color = Color.White)
                    Switch(checked = bgEnrichment, onCheckedChange = { 
                        bgEnrichment = it
                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("background_enrichment_enabled", it).apply()
                    })
                }
            }

            InfoSection("Auth Credentials") {
                val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
                val truecallerProvider = app.providerManager.getHealthyProviders().find { it.id == "truecaller_v2" } as? com.infocaller.app.data.remote.TruecallerProviderImpl
                
                var phoneForAuth by remember { mutableStateOf("") }
                var requestId by remember { mutableStateOf<String?>(null) }
                var otpInput by remember { mutableStateOf("") }
                
                if (requestId == null) {
                    Text("Truecaller Authentication", style = MaterialTheme.typography.labelMedium, color = Primary)
                    OutlinedTextField(
                        value = phoneForAuth,
                        onValueChange = { phoneForAuth = it },
                        label = { Text("Phone Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                requestId = truecallerProvider?.startAuth(phoneForAuth)
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                    ) {
                        Text("Send OTP")
                    }
                } else {
                    Text("Enter OTP for $phoneForAuth", style = MaterialTheme.typography.labelMedium, color = Primary)
                    OutlinedTextField(
                        value = otpInput,
                        onValueChange = { otpInput = it },
                        label = { Text("OTP") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val success = truecallerProvider?.completeAuth(phoneForAuth, requestId!!, otpInput) == true
                                if (success) {
                                    requestId = null
                                    phoneForAuth = ""
                                    otpInput = ""
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                    ) {
                        Text("Verify OTP")
                    }
                    TextButton(onClick = { requestId = null }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(16.dp))

                var truecallerToken by remember { 
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    mutableStateOf(prefs.getString("truecaller_token", "") ?: "") 
                }
                var showToken by remember { mutableStateOf(false) }

                Text("Truecaller Auth Token", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                OutlinedTextField(
                    value = truecallerToken,
                    onValueChange = { 
                        truecallerToken = it
                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putString("truecaller_token", it).apply()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Color.White.copy(alpha = 0.4f))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Primary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                var devToken by remember { 
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    mutableStateOf(prefs.getString("apify_dev_token", "") ?: "") 
                }
                var showDevToken by remember { mutableStateOf(false) }

                Text("Dev Apify Token", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                OutlinedTextField(
                    value = devToken,
                    onValueChange = { 
                        devToken = it
                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putString("apify_dev_token", it).apply()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    visualTransformation = if (showDevToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showDevToken = !showDevToken }) {
                            Icon(if (showDevToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Color.White.copy(alpha = 0.4f))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Primary
                    ),
                    singleLine = true
                )
            }

            InfoSection("Contact Repair") {
                val recoveryState by viewModel.recoveryState.collectAsState()
                var showConfirm by remember { mutableStateOf(false) }

                Text(
                    text = recoveryState ?: "Restore contact names from verified system backups.",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { showConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f), contentColor = Color.Red),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Emergency: Fix Names")
                }

                if (showConfirm) {
                    AlertDialog(
                        onDismissRequest = { showConfirm = false },
                        title = { Text("Are you sure?") },
                        text = { Text("This will attempt to remove placeholder names and restore them from system metadata. This action is irreversible.") },
                        confirmButton = {
                            TextButton(onClick = { 
                                showConfirm = false
                                viewModel.runEmergencyCleanup() 
                            }) {
                                Text("Proceed", color = Color.Red)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }

            InfoSection("Server Configuration") {
                val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
                val registryUrl by app.providerManager.registryUrl.collectAsState()
                val backendUrl by app.providerManager.backendUrl.collectAsState()
                
                var newRegistryUrl by remember { mutableStateOf(registryUrl) }
                var newBackendUrl by remember { mutableStateOf(backendUrl) }
                
                OutlinedTextField(
                    value = newRegistryUrl,
                    onValueChange = { newRegistryUrl = it },
                    label = { Text("Registry URL", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = newBackendUrl,
                    onValueChange = { newBackendUrl = it },
                    label = { Text("Backend Base URL", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )

                Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(
                        onClick = { app.providerManager.setRegistryUrl(newRegistryUrl) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Registry", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { app.providerManager.setBackendUrl(newBackendUrl) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Backend", fontSize = 12.sp)
                    }
                }

                if (backendUrl.isBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Backend: Not Configured",
                        color = Color.Yellow,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            
            InfoSection("Diagnostics") {
                var testNumber by remember { mutableStateOf("01785917145") }
                OutlinedTextField(
                    value = testNumber,
                    onValueChange = { testNumber = it },
                    label = { Text("Test Number", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                val lookupResult by viewModel.fullLookupResult.collectAsState()
                
                Button(
                    onClick = { viewModel.performFullLookup(testNumber) },
                    modifier = Modifier.fillMaxWidth()
                ) { 
                    Text("RUN FULL LOOKUP TEST") 
                }
                
                lookupResult?.let { result ->
                    Spacer(modifier = Modifier.height(16.dp))
                    InfoRow("Name", result.name ?: "N/A")
                    val location = LocationUtils.formatCallerLocation(result.city, result.region, result.country)
                    InfoRow("Location", location.ifBlank { "N/A" })
                    InfoRow("Confidence", "${(result.confidence * 100).toInt()}%")
                    InfoRow("Spam Score", result.spamScore.toString())
                    
                    if (result.socialProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Social Profiles", style = MaterialTheme.typography.labelMedium, color = Primary)
                        result.socialProfiles.forEach { profile ->
                            InfoRow(profile.platform, profile.status.name)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Provider Performance", style = MaterialTheme.typography.labelMedium, color = Primary)
                    result.performance.forEach { perf ->
                        InfoRow(perf.providerName, "${perf.durationMs}ms")
                    }
                }
            }

            InfoSection("Provider Manager") {
                val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
                val providers by app.providerManager.providers.collectAsState()
                
                Button(
                    onClick = onNavigateToWhatsAppLookup,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("WhatsApp Profile Lookup")
                }
                
                providers.forEach { provider ->
                    val health = app.providerManager.getHealth(provider.id)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(provider.name, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("v${provider.version}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = health?.status?.name ?: "UNKNOWN",
                                    color = when(health?.status) {
                                        com.infocaller.app.domain.engine.ProviderStatus.HEALTHY -> Success
                                        com.infocaller.app.domain.engine.ProviderStatus.DEGRADED -> Secondary
                                        else -> Color.Red
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("Lat: ${health?.avgDurationMs ?: 0}ms", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Capabilities: ${provider.capabilities.joinToString(", ")}",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { 
                        scope.launch {
                            androidx.work.WorkManager.getInstance(context).enqueue(
                                androidx.work.OneTimeWorkRequestBuilder<com.infocaller.app.worker.ProviderUpdateWorker>().build()
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Force Provider Update Sync")
                }
            }
        }
    }
}

@Composable
fun InfoSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Primary)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f))
        Text(value, color = Color.White)
    }
}
