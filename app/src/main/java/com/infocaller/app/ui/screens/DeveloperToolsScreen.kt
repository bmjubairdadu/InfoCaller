package com.infocaller.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.Secondary
import com.infocaller.app.ui.theme.Success
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(
    viewModel: com.infocaller.app.ui.viewmodel.CallerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
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
                    label = { Text("Registry URL", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = newBackendUrl,
                    onValueChange = { newBackendUrl = it },
                    label = { Text("Backend Base URL", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )

                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = { app.providerManager.setRegistryUrl(newRegistryUrl) }
                    ) {
                        Text("Save Registry")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { app.providerManager.setBackendUrl(newBackendUrl) }
                    ) {
                        Text("Save Backend")
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

                Spacer(modifier = Modifier.height(16.dp))
                
                var devToken by remember { 
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    mutableStateOf(prefs.getString("apify_dev_token", "") ?: "") 
                }

                OutlinedTextField(
                    value = devToken,
                    onValueChange = { 
                        devToken = it
                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putString("apify_dev_token", it).apply()
                    },
                    label = { Text("Dev Apify Token", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                
                Text(
                    text = "Direct Apify lookup (Local Dev Only)",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            InfoSection("Diagnostics") {
                var testNumber by remember { mutableStateOf("01785917145") }
                OutlinedTextField(
                    value = testNumber,
                    onValueChange = { testNumber = it },
                    label = { Text("Test Number", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                val lookupResult by viewModel.fullLookupResult.collectAsState()
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { viewModel.performFullLookup(testNumber) }) { Text("RUN FULL TEST") }
                }
                
                lookupResult?.let { result ->
                    Spacer(modifier = Modifier.height(16.dp))
                    InfoRow("Name", result.name ?: "N/A")
                    InfoRow("Confidence", "${(result.confidence * 100).toInt()}%")
                    InfoRow("Spam Score", result.spamScore.toString())
                    
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
                    onClick = { /* TODO: Trigger Update Check */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check for Provider Updates")
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
