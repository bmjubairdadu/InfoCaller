package com.infocaller.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.components.InfoCallerLoading
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderAuthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
    val truecallerProvider = app.providerManager.getHealthyProviders().find { it.id == "truecaller_v2" } as? com.infocaller.app.data.remote.TruecallerProviderImpl

    val prefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    var isTruecallerAuthorized by remember { mutableStateOf(!prefs.getString("truecaller_token", "").isNullOrBlank()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provider Authorization", color = Color.White) },
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
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            Text("Authorize providers to enable more accurate caller identification.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Truecaller Section
            AuthSection("Truecaller", isAuthorized = isTruecallerAuthorized) {
                var phone by remember { mutableStateOf("") }
                var requestId by remember { mutableStateOf<String?>(null) }
                var otp by remember { mutableStateOf("") }
                var isLoading by remember { mutableStateOf(false) }

                if (isTruecallerAuthorized) {
                    Text("Connection Active", color = Success, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { 
                            prefs.edit().remove("truecaller_token").apply()
                            isTruecallerAuthorized = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect Account", color = Error)
                    }
                } else if (requestId == null) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Your Phone Number") },
                        placeholder = { Text("+8801...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Button(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                requestId = truecallerProvider?.startAuth(phone)
                                isLoading = false
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                        enabled = !isLoading && phone.isNotBlank()
                    ) {
                        if (isLoading) InfoCallerLoading(size = 20.dp) else Text("Send OTP")
                    }
                } else {
                    Text("Enter OTP sent to $phone", color = Primary, style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it },
                        label = { Text("OTP") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Button(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                val success = truecallerProvider?.completeAuth(phone, requestId!!, otp) == true
                                if (success) {
                                    requestId = null
                                    phone = ""
                                    otp = ""
                                    isTruecallerAuthorized = true
                                }
                                isLoading = false
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                        enabled = !isLoading && otp.isNotBlank()
                    ) {
                        if (isLoading) InfoCallerLoading(size = 20.dp) else Text("Verify & Connect")
                    }
                    TextButton(onClick = { requestId = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("Change Number", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
fun AuthSection(title: String, isAuthorized: Boolean, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (isAuthorized) Success else Primary)
                if (isAuthorized) {
                    Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))
            content()
        }
    }
}
